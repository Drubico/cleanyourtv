package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.ImageView;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The clock screensaver's scene (layout dream_clock): the thin clock and date on black over a slow ambient glow.
 */
final class ClockScene implements Runnable, View.OnLayoutChangeListener {

    private static final long MOVE_MS = 60_000L, GLOW_STEP_MS = 500L;
    private static final int GLOW_STEPS = 16;
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final ArgbEvaluator mix = new ArgbEvaluator();
    private final View content;
    private final ClockRefresh clockRefresh;
    private final ImageView ambient;
    private final GradientDrawable glow;
    private int colorIndex, glowColor, glowFrom, glowTo, glowStep;
    private boolean wallpaperShown;
    private File wallpaperFile;
    private int wallpaperShade, wallpaperGeneration;
    private Resources wallpaperRes;
    private List<File> rotation;
    private long rotationMs, shownMs;
    private String order, latitude, longitude;
    private Map<String, Set<String>> moods;

    ClockScene(View root) {
        content = root.findViewById(R.id.dreamContent);
        clockRefresh = new ClockRefresh(content);
        ambient = root.findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener(this);
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
    }

    @Override
    public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
        glow.setGradientRadius((r - l) * 0.55f);
    }

    @Override
    public void run() {
        if (glowStep > 0) {
            setGlow((int) mix.evaluate((float) glowStep / GLOW_STEPS, glowFrom, glowTo));
            if (++glowStep <= GLOW_STEPS) handler.postDelayed(this, GLOW_STEP_MS);
            else {
                glowStep = 0;
                handler.postDelayed(this, MOVE_MS - GLOW_STEPS * GLOW_STEP_MS);
            }
            return;
        }
        moveClock();
        advanceWallpaper();
        if (!wallpaperShown) {
            glowFrom = glowColor;
            colorIndex = (colorIndex + 1) % PALETTE.length;
            glowTo = PALETTE[colorIndex];
            glowStep = 1;
            handler.postDelayed(this, GLOW_STEP_MS);
        } else {
            handler.postDelayed(this, MOVE_MS);
        }
    }

    void start() {
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.removeCallbacksAndMessages(null);
        glowStep = 0;
        handler.postDelayed(this, MOVE_MS);
        clockRefresh.start();
        if (wallpaperFile != null && !wallpaperShown) loadWallpaper();
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        glowStep = 0;
        clockRefresh.stop();
        content.animate().cancel();
        wallpaperGeneration++;
    }

    void useHomeWallpaper(Context ctx) {
        Config cfg;
        try {
            cfg = Config.load(ctx);
        } catch (RuntimeException e) {
            return;
        }
        if (!cfg.clockWallpaper) {
            if (!cfg.clockGlow) {
                wallpaperShown = true;
                ambient.setBackground(null);
            }
            return;
        }
        if (cfg.wallpapers.isEmpty()) {
            wallpaperFile = null;
        } else {
            rotation = new ArrayList<>(cfg.wallpapers);
            String shown = ctx.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE).getString("image", "");
            File current = WallpaperRotation.find(rotation, shown);
            wallpaperFile = current != null ? current : rotation.get(0);
            rotationMs = cfg.wallpaperRotationMinutes * 60_000L;
            order = cfg.wallpaperOrder;
            moods = cfg.wallpaperMoods;
            latitude = moods.isEmpty() ? "" : ctx.getString(R.string.latitude);
            longitude = moods.isEmpty() ? "" : ctx.getString(R.string.longitude);
        }
        wallpaperShade = (cfg.clockWallpaperDim * 255 / 100) << 24;
        wallpaperRes = ctx.getResources();
    }

    private void loadWallpaper() {
        File file = wallpaperFile;
        int shade = wallpaperShade;
        Resources res = wallpaperRes;
        DisplayMetrics dm = res.getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels, generation = ++wallpaperGeneration;
        new Thread(() -> {
            Bitmap bmp = WallpaperPlayer.decode(file, w, h, Bitmap.Config.RGB_565, 0, false);
            if (bmp == null) return;
            handler.post(() -> {
                if (generation != wallpaperGeneration || wallpaperShown) {
                    bmp.recycle();
                    return;
                }
                wallpaperShown = true;
                ambient.setBackground(null);
                ambient.setImageDrawable(new LayerDrawable(new Drawable[]{
                        new BitmapDrawable(res, bmp), new ColorDrawable(shade)}));
            });
        }, "clock-wallpaper").start();
    }

    private void advanceWallpaper() {
        if (rotation == null || rotationMs <= 0 || rotation.size() < 2) return;
        shownMs += MOVE_MS;
        if (shownMs < rotationMs) return;
        shownMs = 0;
        File next = WallpaperRotation.pick(playable(), wallpaperFile.getName(), order, random);
        if (next.equals(wallpaperFile)) return;
        wallpaperFile = next;
        wallpaperShown = false;
        loadWallpaper();
    }

    private List<File> playable() {
        if (moods.isEmpty()) return rotation;
        LocalDateTime now = LocalDateTime.now();
        int[] sun = WallpaperMood.sun(now.toLocalDate(), latitude, longitude, ZoneId.systemDefault());
        return WallpaperMood.select(rotation, moods,
                WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withLayer().withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).withLayer().start();
        }).start();
    }

    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
