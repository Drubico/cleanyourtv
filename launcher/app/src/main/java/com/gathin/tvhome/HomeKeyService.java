package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * With stock_home_redirect set, it also sends the stock launcher's home screen back here, for firmware that turns
 * that launcher on again after it was disabled (XGIMI's xrmservice does, on its own remote keys), or that cannot be
 * disabled at all, and a short press of Home opens this launcher even when the stock home is already on screen.
 *
 * With power_on_home set, a Live TV window (live_tv_package) that the firmware opens by itself at power-on is sent
 * back here too, until the first key press after boot or after the screen turns on (Changhong / Homwee boards).
 * With power_on_home_always set, Home comes up at every power-on whatever is in front, for TVs that resume their
 * last source: a Philips back on an HDMI input sends no window event until the input has a picture.
 *
 * With home_long_press_home set, it takes every Home press, so one held past the long-press timeout opens this launcher
 * instead of Android's ALL_APPS action, which nothing answers once the stock launcher is disabled (Philips TPM191E).
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService implements Handler.Callback {

    /** restore_key_services was on when the config was read: this helper is noted for ServiceRepair. */
    private boolean repairRecord;
    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    private boolean settingsClearTask;
    /** consume_volume_keys: Volume Up/Down go to VolumeKeys instead of on to the firmware. */
    private boolean consumeVolumeKeys;
    /** app_keys, the same maps InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();

    /** Waits for the user to unlock when Android binds this service at boot; null once the config is read. */
    private BroadcastReceiver unlockReceiver;
    /** The stock launcher's HOME activities (stock_home_redirect), disabled ones included. */
    private final Set<String> stockHome = new HashSet<>();
    /** stock_home_redirect, the stock launcher's package. */
    private String stockHomePackage = "";
    /** power_on_home: the Live TV package to send back to Home, empty when off; armed until this uptime or a key. */
    private String powerOnLiveTv = "";
    private long powerOnUntil;
    /** home_long_press_home: this service takes every Home press, held ones included. */
    private boolean homeLongPressHome;
    /** app_keys "front:" entries; the package in front goes to RemoteKeys.frontPackage, for both helpers. */
    private Map<Integer, String> frontAppKeys = Collections.emptyMap();
    private Map<Integer, String> frontAppScancodes = Collections.emptyMap();
    /** power_on_home_always: Home at every power-on, not only over Live TV. */
    private boolean powerOnAlways;
    /** power_on_home: re-arms after the screen turns on; null unless the key is on. */
    private BroadcastReceiver screenOnReceiver;
    /** Stops key filtering while a game controller is connected; null unless gamepad_passthrough and key_filter, or before the unlock. */
    private GamepadPassthrough gamepads;
    /** Screen-off receiver while guard_off_in_standby is set; null otherwise. */
    private BroadcastReceiver standbyExit;
    /** disableSelf is how this service leaves for standby, not the user turning it off. */
    private boolean steppingOut;
    /** The bound helper, so the opt-in broadcast can arm it without waiting for a new connection. */
    static HomeKeyService running;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = this;
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        // The service is directBootAware: Android 11 skips a service that is not while the user is still
        // locked at boot, writes accessibility_enabled=0 and, on TCL G03 firmware, never binds it after the
        // unlock. Config.load reads credential-protected storage, so wait for the unlock before reading it.
        UserManager users = getSystemService(UserManager.class);
        if (users.isUserUnlocked()) {
            loadConfig();
            return;
        }
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopWaitingForUnlock();
                loadConfig();
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // The unlock may have happened between the check and the registration.
        if (users.isUserUnlocked()) {
            stopWaitingForUnlock();
            loadConfig();
        }
    }

    private void stopWaitingForUnlock() {
        if (unlockReceiver == null) return;
        unregisterReceiver(unlockReceiver);
        unlockReceiver = null;
    }

    /** The opt-in broadcast: arm this binding now, or drop the receiver if the opt-in was turned off. */
    static void applyStandbyOptIn() {
        HomeKeyService service = running;
        if (service == null) return;
        if (GuardKeep.offInStandby(service)) service.armStandbyExit();
        else service.disarmStandbyExit();
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        RemoteKeys.frontPackage = "";
        RemoteKeys.release(this);
        stopWaitingForUnlock();
        if (screenOnReceiver != null) unregisterReceiver(screenOnReceiver);
        if (standbyExit != null) {
            try {
                unregisterReceiver(standbyExit);
            } catch (RuntimeException ignored) {
            }
            standbyExit = null;
        }
        if (gamepads != null) gamepads.stop();
        if (consumeVolumeKeys) VolumeKeys.stop();
        abortDeepClean();
        stopMonitor();
        super.onDestroy();
    }

    /** Some TCL boards never wake from the remote while this helper stays bound with the screen off. */
    private void armStandbyExit() {
        if (standbyExit != null || !GuardKeep.offInStandby(this)) return;
        standbyExit = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) return;
                leaveForStandby();
            }
        };
        registerReceiver(standbyExit, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && !power.isInteractive()) leaveForStandby();
    }

    private void disarmStandbyExit() {
        if (standbyExit == null) return;
        try {
            unregisterReceiver(standbyExit);
        } catch (RuntimeException ignored) {
        }
        standbyExit = null;
    }

    private void leaveForStandby() {
        steppingOut = true;
        GuardKeep.stepOut(this);
    }

    private void loadConfig() {
        // Keycodes and the panel choice can also come from a theme; theme.sh restarts this service.
        Config config = Config.load(this);
        // restore_key_services: note that this helper runs, so Home can put it back after a force stop. Here, not
        // in onServiceConnected: the note is in credential-protected storage too, readable only after the unlock.
        repairRecord = config.restoreKeyServices;
        if (repairRecord) ServiceRepair.connected(this, getClass());
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        settingsClearTask = config.settingsKeyClearTask;
        consumeVolumeKeys = config.consumeVolumeKeys;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        RemoteKeys.applyKeyFilter(this, config.keyFilter);
        homeLongPressHome = config.homeLongPressHome;
        frontAppKeys = RemoteKeys.parseFrontAppKeys(config.appKeys);
        frontAppScancodes = RemoteKeys.parseFrontAppScancodes(config.appKeys);
        boolean watchFront = !frontAppKeys.isEmpty() || !frontAppScancodes.isEmpty();
        stockHome.clear();
        stockHomePackage = config.stockHomeRedirect;
        if (!config.stockHomeRedirect.isEmpty()) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(config.stockHomeRedirect);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                stockHome.add(ri.activityInfo.name);
            }
        }
        // power_on_home, off by default. Not with boot_to_live_tv, or with Live TV picked as the Open on start app:
        // those open Live TV on purpose at boot.
        String openOnStart = config.openOnStartChoice
                ? getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getString(OpenOnStart.KEY, "") : "";
        powerOnLiveTv = config.powerOnHome && !config.bootToLiveTv && !config.liveTvPackage.equals(openOnStart)
                ? config.liveTvPackage : "";
        powerOnAlways = config.powerOnHomeAlways && !config.bootToLiveTv && openOnStart.isEmpty();
        if (!powerOnLiveTv.isEmpty() || powerOnAlways) {
            armPowerOnHome();
        } else if (screenOnReceiver != null) {
            unregisterReceiver(screenOnReceiver);
            screenOnReceiver = null;
            powerOnUntil = 0;
        }
        // home_key_service.xml subscribes to no events, so the redirects ask for window changes only when one is on.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null && (!stockHome.isEmpty() || !powerOnLiveTv.isEmpty() || watchFront)) {
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            setServiceInfo(info);
        }
        if (!stockHome.isEmpty() || !powerOnLiveTv.isEmpty()) probeAtBoot();
        armStandbyExit();
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
        // gamepad_passthrough, off by default. Only with key_filter on: off, the service never filters keys.
        if (config.gamepadPassthrough && config.keyFilter && gamepads == null) {
            gamepads = new GamepadPassthrough(this);
            gamepads.start();
        }
    }

    /** A tap's target: the "front:" entry while the button's own app is already in front, else the usual one. */
    private String inFront(Map<Integer, String> keys, Map<Integer, String> front, int code) {
        return RemoteKeys.tap(keys.get(code), front.get(code));
    }

    private boolean isSettingsScancode(KeyEvent event) {
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        return false;
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        if (isSettingsScancode(event)) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (cancelDeepCleanIfNeeded(event)) return true;
        // power_on_home: the first key after power-on hands the TV to the user (power and wake keys don't count).
        if (powerOnUntil != 0 && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() != KeyEvent.KEYCODE_POWER && event.getKeyCode() != KeyEvent.KEYCODE_WAKEUP) {
            powerOnUntil = 0;
        }
        if (consumeVolumeKeys && VolumeKeys.handle(this, event)) return true;
        // A scancode entry is more specific than any keycode, so it goes first.
        if (RemoteKeys.handle(this, event, inFront(appScancodes, frontAppScancodes, event.getScanCode()),
                holdAppScancodes.get(event.getScanCode()))) return true;
        // A keycode app_keys entry with a tap target wins over the Settings keycodes (InputKeyService opens it too, and
        // RemoteKeys answers a press once), but not over a button named in settings_key_scancodes. A hold-only entry
        // leaves the tap to Settings and still runs its hold.
        boolean settingsKey = isSettingsKey(event);
        String tap = inFront(appKeys, frontAppKeys, event.getKeyCode());
        String hold = holdAppKeys.get(event.getKeyCode());
        if ((!settingsKey || (tap != null && !isSettingsScancode(event))) && RemoteKeys.handle(this, event, tap, hold)) {
            return true;
        }
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (settingsKey) {
            RemoteKeys.handle(this, event, null, hold);
            boolean held = hold != null && event.getEventTime() - event.getDownTime() >= RemoteKeys.HOLD_MS;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled() && !held) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : SystemSettings.intent(this, Settings.ACTION_SETTINGS);
                // settings_key_clear_task: on MediaTek boards KEYCODE_SETTINGS is also a global key that opens Display &
                // Sound in the Settings task before this helper sees it; cleared, Back from Settings goes Home, not there.
                if (settingsClearTask && !settingsPanel) open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                try {
                    startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        // home_long_press_home: Android never sees the Home key, so a held press cannot turn into ALL_APPS.
        if (homeLongPressHome && event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHome();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME && !stockHome.isEmpty()) {
            redirectHomeKey(event);
            return false;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHome();
        return true;
    }

    /** Unbound by Android: the user turned the helper off, so Home doesn't put it back (ServiceRepair). */
    @Override
    public boolean onUnbind(Intent intent) {
        stopMonitor();
        RemoteKeys.release(this);
        if (repairRecord && !steppingOut) ServiceRepair.disconnected(this, getClass());
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        // app_keys "front:" entries: only activities count, so a toast or the volume bar doesn't change the app in front.
        if ((!frontAppKeys.isEmpty() || !frontAppScancodes.isEmpty())
                && event.getPackageName() != null && event.getClassName() != null) {
            try {
                ComponentName window = new ComponentName(event.getPackageName().toString(), event.getClassName().toString());
                getPackageManager().getActivityInfo(window, 0);
                RemoteKeys.frontPackage = window.getPackageName();
            } catch (PackageManager.NameNotFoundException notAnActivity) {
                // a dialog, panel or overlay: the app under it is still in front
            }
        }
        // power_on_home: the firmware opened Live TV by itself at power-on, before anyone pressed a key.
        if (powerOnRedirect(powerOnLiveTv, event.getPackageName(), SystemClock.elapsedRealtime(), powerOnUntil)) {
            openHome();
            return;
        }
        // stock_home_redirect: the firmware turned the stock launcher back on and its home screen came up.
        CharSequence cls = event.getClassName();
        if (!stockHome.isEmpty() && cls != null && stockHome.contains(cls.toString())) openHome();
    }

    /** How long power_on_home stays armed after the screen turns on without a reboot. */
    static final long SCREEN_ON_WINDOW_MS = 60 * 1000L;

    /** power_on_home: armed for the boot window (if this bind is part of the boot) and after every screen-on. */
    private void armPowerOnHome() {
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) {
            powerOnUntil = BOOT_WINDOW_MS;
            if (powerOnAlways) handler.postDelayed(this::homeAtPowerOn, POWER_ON_ALWAYS_DELAY_MS);
        }
        if (screenOnReceiver != null) return;
        screenOnReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                powerOnUntil = SystemClock.elapsedRealtime() + SCREEN_ON_WINDOW_MS;
                if (powerOnAlways) handler.postDelayed(HomeKeyService.this::homeAtPowerOn, POWER_ON_ALWAYS_DELAY_MS);
            }
        };
        registerReceiver(screenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    /** power_on_home_always: after the firmware has brought its last source back (Philips: within 0.5 s). */
    static final long POWER_ON_ALWAYS_DELAY_MS = 800L;

    /** power_on_home_always: Home, unless a key was pressed since the screen came on (the user has the TV). */
    private void homeAtPowerOn() {
        if (stillArmed(SystemClock.elapsedRealtime(), powerOnUntil)) openHome();
    }

    static boolean stillArmed(long now, long armedUntil) {
        return now < armedUntil;
    }

    /** The settings menu offers Restart only while this helper is bound: it opens the system power menu. */
    static boolean canOpenPowerMenu() {
        return running != null;
    }

    /** The system power menu (Restart, and Power off where the firmware has it); the TV asks before it acts. */
    static void openPowerMenu() {
        HomeKeyService service = running;
        if (service != null) service.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }

    static boolean powerOnRedirect(String liveTv, CharSequence pkg, long now, long armedUntil) {
        return !liveTv.isEmpty() && pkg != null && liveTv.contentEquals(pkg) && now < armedUntil;
    }

    /** How long after boot the first bind still counts as "at boot" for probeAtBoot. */
    static final long BOOT_WINDOW_MS = 3 * 60 * 1000L;

    /** stock_home_redirect at boot: the stock home is often on screen before Android binds this service, so no
     *  window event for it arrives (#1116). Once per boot, open ProbeActivity: when it closes, whatever was in
     *  front comes back and sends a window event, and onAccessibilityEvent covers it only if it is the stock home.
     *  An HDMI input or an app that the TV resumed at boot is left alone. */
    private void probeAtBoot() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences prefs = getSharedPreferences("boot", MODE_PRIVATE);
        if (!firstBindAfterBoot(boot, prefs.getInt("probe_boot", -1), SystemClock.elapsedRealtime())) return;
        prefs.edit().putInt("probe_boot", boot).apply();
        try {
            startActivity(new Intent(this, ProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    static boolean firstBindAfterBoot(int boot, int lastBoot, long uptimeMs) {
        return boot >= 0 && boot != lastBoot && uptimeMs < BOOT_WINDOW_MS;
    }

    /** Lets the system see the key, so a long press still does what the firmware gives it. */
    private static final long HOME_KEY_DELAY_MS = 250;
    private static final int MSG_CLEAN = 1, MSG_HUD = 2;
    private final Handler handler = new Handler(Looper.getMainLooper(), this);

    /** stock_home_redirect on the Home key: when the stock home is already on screen (it came up at boot, before
     *  Android bound this service), Home brings it up again with no window change, so the redirect above never
     *  fires and the TV stays on the stock home until another app opens (#1116). A short press opens this
     *  launcher shortly after the system has handled the key; a long press (Google TV's quick settings) is left
     *  alone. Held time, not repeats: key repeats are made after accessibility filtering, so none arrive here. */
    private void redirectHomeKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()
                && isShortPress(event.getDownTime(), event.getEventTime(), ViewConfiguration.getLongPressTimeout())) {
            handler.postDelayed(this::openHome, HOME_KEY_DELAY_MS);
        }
    }

    static boolean isShortPress(long downTime, long upTime, long longPressTimeout) {
        return upTime - downTime < longPressTimeout;
    }

    private void openHome() {
        abortDeepClean();
        Intent open = stockHomeStays() ? new Intent(this, MainActivity.class)
                : new Intent(Intent.ACTION_MAIN).setPackage(getPackageName());
        startActivity(open.addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    /** stock_home_redirect while Android's Home is still the stock launcher (launcherx on Google TV outranks this
     *  one). A Home intent would then put this launcher in the home task and make it the home process, and from
     *  Android 10 the stock launcher, no longer exempt from the limits on background activity starts, can't open
     *  apps for the remote's YouTube and Netflix buttons (#1274). openHome starts it like an app instead:
     *  CATEGORY_HOME without ACTION_MAIN is not a Home intent, but still sends MainActivity back to the first tile.
     *  A MainActivity already in the home task, as the install scripts' first start leaves it, is reused as it is
     *  until that task goes (a restart or a reinstall). With this launcher or Android's chooser as Home, openHome
     *  keeps the Home intent. */
    private boolean stockHomeStays() {
        if (stockHome.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return home != null && stockHomePackage.equals(home.activityInfo.packageName);
    }

    private List<String> cleanQueue;
    private int cleanIndex, cleanStage, cleanTicks, cleanStopped, savedEventTypes = -1;
    private String forceStopText, okText;
    private TextView hudView;
    private WindowManager.LayoutParams hudLp;
    private boolean hudLeft;
    private long lastRxBytes, lastHudTime;
    private BroadcastReceiver hudScreenOff;

    @Override
    public boolean handleMessage(Message msg) {
        if (msg.what == MSG_CLEAN) stepDeepClean();
        else if (msg.what == MSG_HUD) updateHud();
        return true;
    }

    static boolean isMonitorActive() {
        return running != null && running.hudView != null;
    }

    static boolean toggleMonitor() {
        HomeKeyService service = running;
        if (service == null) return false;
        if (service.hudView != null) {
            service.stopMonitor();
            return false;
        }
        return service.startMonitor();
    }

    static void flipMonitorCorner() {
        HomeKeyService service = running;
        if (service == null) return;
        service.hudLeft = !service.hudLeft;
        if (service.hudView == null) {
            service.startMonitor();
            return;
        }
        service.hudLp.gravity = Gravity.TOP | (service.hudLeft ? Gravity.START : Gravity.END);
        WindowManager wm = service.getSystemService(WindowManager.class);
        if (wm != null) wm.updateViewLayout(service.hudView, service.hudLp);
    }

    private boolean startMonitor() {
        if (hudView != null) return true;
        WindowManager wm = getSystemService(WindowManager.class);
        if (wm == null) return false;
        float d = getResources().getDisplayMetrics().density;
        TextView tv = new TextView(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD20F172A);
        bg.setCornerRadius(100f * d);
        bg.setStroke(Math.max(1, Math.round(d)), 0x5538BDF8);
        tv.setBackground(bg);
        int ph = Math.round(12 * d), pv = Math.round(5 * d);
        tv.setPadding(ph, pv, ph, pv);
        tv.setTextColor(0xFFF8FAFC);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTypeface(UiFont.get(this, R.string.font_medium));
        tv.setSingleLine(true);
        tv.setIncludeFontPadding(false);
        hudLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        hudLp.gravity = Gravity.TOP | (hudLeft ? Gravity.START : Gravity.END);
        hudLp.x = Math.round(24 * d);
        hudLp.y = Math.round(20 * d);
        try {
            wm.addView(tv, hudLp);
        } catch (RuntimeException e) {
            return false;
        }
        hudView = tv;
        lastRxBytes = TrafficStats.getTotalRxBytes();
        lastHudTime = SystemClock.elapsedRealtime();
        if (hudScreenOff == null) {
            hudScreenOff = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) stopMonitor();
                }
            };
            registerReceiver(hudScreenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        }
        updateHud();
        return true;
    }

    private void updateHud() {
        if (hudView == null) return;
        long now = SystemClock.elapsedRealtime(), rx = TrafficStats.getTotalRxBytes(), dt = now - lastHudTime;
        long tenths = dt > 0 && rx >= 0 && lastRxBytes >= 0 && rx >= lastRxBytes
                ? Math.round((rx - lastRxBytes) * 8000.0 / dt / 100_000.0) : 0L;
        lastRxBytes = rx;
        lastHudTime = now;
        ActivityManager am = getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(mi);
        long freeMb = mi.availMem / 1048576L;
        hudView.setText("↓ " + (tenths / 10) + "." + (tenths % 10) + " Mbps  ·  " + freeMb + " MB");
        handler.removeMessages(MSG_HUD);
        handler.sendEmptyMessageDelayed(MSG_HUD, 1500);
    }

    private void stopMonitor() {
        handler.removeMessages(MSG_HUD);
        if (hudScreenOff != null) {
            try {
                unregisterReceiver(hudScreenOff);
            } catch (RuntimeException ignored) {
            }
            hudScreenOff = null;
        }
        if (hudView != null) {
            WindowManager wm = getSystemService(WindowManager.class);
            if (wm != null) {
                try {
                    wm.removeView(hudView);
                } catch (RuntimeException ignored) {
                }
            }
            hudView = null;
        }
    }

    static boolean startDeepClean(List<String> pkgs) {
        HomeKeyService service = running;
        if (service == null || pkgs == null || pkgs.isEmpty()) return false;
        service.beginDeepClean(pkgs);
        return true;
    }

    private void beginDeepClean(List<String> pkgs) {
        abortDeepClean();
        forceStopText = settingsForceStopLabel();
        okText = getString(android.R.string.ok);
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            savedEventTypes = info.eventTypes;
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            setServiceInfo(info);
        }
        cleanQueue = new ArrayList<>(pkgs);
        cleanIndex = -1;
        cleanStopped = 0;
        nextCleanApp();
    }

    private String settingsForceStopLabel() {
        try {
            Resources r = getPackageManager().getResourcesForApplication("com.android.tv.settings");
            int id = r.getIdentifier("device_apps_app_management_force_stop", "string", "com.android.tv.settings");
            if (id != 0) return r.getString(id);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return null;
    }

    private boolean isAppStopped(String pkg) {
        try {
            return (getPackageManager().getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_STOPPED) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private void nextCleanApp() {
        while (cleanQueue != null && ++cleanIndex < cleanQueue.size()) {
            String pkg = cleanQueue.get(cleanIndex);
            if (isAppStopped(pkg)) continue;
            Intent intent = SystemSettings.appInfo(this, pkg);
            if (intent == null) continue;
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            } catch (ActivityNotFoundException | SecurityException e) {
                continue;
            }
            cleanStage = 0;
            cleanTicks = 0;
            handler.sendEmptyMessageDelayed(MSG_CLEAN, 260);
            return;
        }
        finishDeepClean(false, cleanStopped);
    }

    private void stepDeepClean() {
        if (cleanQueue == null || cleanIndex >= cleanQueue.size()) return;
        String pkg = cleanQueue.get(cleanIndex);
        if (isAppStopped(pkg)) {
            cleanStopped++;
            nextCleanApp();
            return;
        }
        if (++cleanTicks > 16) {
            nextCleanApp();
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null && !getPackageName().contentEquals(root.getPackageName())) {
            if (cleanStage == 0) {
                AccessibilityNodeInfo label = findCleanTarget(root, true);
                if (label != null) {
                    AccessibilityNodeInfo target = clickableAncestor(label);
                    if (target == null || !label.isEnabled() || !target.isEnabled()) {
                        nextCleanApp();
                        return;
                    }
                    if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        cleanStage = 1;
                        cleanTicks = 0;
                    }
                }
            } else if (cleanStage == 1) {
                AccessibilityNodeInfo btn = findCleanTarget(root, false);
                AccessibilityNodeInfo target = clickableAncestor(btn);
                if (target != null && btn.isEnabled() && target.isEnabled()
                        && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    cleanStage = 2;
                    cleanTicks = 0;
                }
            } else if (cleanTicks > 5) {
                nextCleanApp();
                return;
            }
        }
        handler.sendEmptyMessageDelayed(MSG_CLEAN, 220);
    }

    private AccessibilityNodeInfo findCleanTarget(AccessibilityNodeInfo node, boolean stageZero) {
        if (node == null) return null;
        String id = node.getViewIdResourceName();
        CharSequence cs = node.getText();
        String text = cs != null ? cs.toString().trim() : "";
        if (stageZero) {
            if ((forceStopText != null && text.equalsIgnoreCase(forceStopText))
                    || text.equalsIgnoreCase("Forzar detención")
                    || text.equalsIgnoreCase("Forzar detencion")
                    || text.equalsIgnoreCase("Force stop")
                    || (id != null && id.endsWith("force_stop") && clickableAncestor(node) != null)) {
                return node;
            }
        } else if ("android:id/button1".equals(id)
                || (okText != null && text.equalsIgnoreCase(okText))
                || text.equalsIgnoreCase("Aceptar")
                || text.equalsIgnoreCase("OK")) {
            return node;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo hit = findCleanTarget(node.getChild(i), stageZero);
            if (hit != null) return hit;
        }
        return null;
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        for (int i = 0; node != null && i < 5; i++, node = node.getParent()) {
            if (node.isClickable()) return node;
        }
        return null;
    }

    private boolean cancelDeepCleanIfNeeded(KeyEvent event) {
        if (cleanQueue != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME) {
                finishDeepClean(true, 0);
                return true;
            }
        }
        return false;
    }

    private void abortDeepClean() {
        handler.removeMessages(MSG_CLEAN);
        cleanQueue = null;
        if (savedEventTypes >= 0) {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.eventTypes = savedEventTypes;
                setServiceInfo(info);
            }
            savedEventTypes = -1;
        }
    }

    private void finishDeepClean(boolean canceled, int stopped) {
        abortDeepClean();
        openHome();
        if (!canceled) {
            boolean es = "es".equals(Locale.getDefault().getLanguage());
            String msg = es
                    ? (stopped > 0 ? "Cerradas de raíz: " + stopped + (stopped == 1 ? " app" : " apps")
                            : "Las apps ya estaban detenidas")
                    : (stopped > 0 ? "Force-stopped: " + stopped + (stopped == 1 ? " app" : " apps")
                            : "Apps were already stopped");
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onInterrupt() {
    }
}
