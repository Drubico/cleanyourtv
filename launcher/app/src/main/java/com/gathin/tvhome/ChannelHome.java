package com.gathin.tvhome;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * channel_home: the light "channels" Home. The header is gone; the apps are one plain grid of light tiles, with
 * empty slots drawn where the grid has no app yet, and a bar with a curved top edge runs along the bottom of the
 * screen: the clock above its dip, the date inside it, a profile button and the store on the left shoulder and
 * the network and Settings on the right one.
 */
final class ChannelHome extends FrameLayout implements View.OnKeyListener, View.OnFocusChangeListener,
        View.OnClickListener, View.OnLongClickListener, View.OnLayoutChangeListener, View.OnScrollChangeListener {

    static final float BAR_DP = 150, SHOULDER_DP = 38, DIP_DP = 88;
    static final float CONTENT_BOTTOM_DP = BAR_DP - SHOULDER_DP, CONTENT_TOP_DP = 10;
    private static final float CURVE_FROM = 0.215f, CURVE_TO = 0.385f;
    static final int[] TILE_COLORS = {0xFFF7F7F9, 0xFFE0E2E7, 0xFFEBEDF1};
    static final int INK = 0xFF2B3A55;

    private final MainActivity host;
    private final RecyclerView grid;
    private final AppAdapter adapter;
    private final View btnSettings;
    private final int columns, rows, sidePadding, accent;
    private final float density;
    private final List<View> buttons = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path band = new Path(), edge = new Path();
    private final Rect tmpRect = new Rect();
    private final RectF rect = new RectF();
    private Shader bandShader, tileShader;
    private int shaderHeight = -1, netState = Integer.MIN_VALUE;
    private final boolean appLibrary;
    private View profile, shop, library, settings;
    private ImageView network;
    private Intent storeIntent;
    private TextView date;
    private java.time.format.DateTimeFormatter dateFormat;

    private ChannelHome(MainActivity host, RecyclerView grid, AppAdapter adapter, View btnSettings,
                        int columns, int rows, int sidePadding, int accent, boolean appLibrary) {
        super(host);
        this.host = host;
        this.grid = grid;
        this.adapter = adapter;
        this.btnSettings = btnSettings;
        this.columns = Math.max(1, columns);
        this.rows = rows;
        this.sidePadding = sidePadding;
        this.accent = accent;
        this.appLibrary = appLibrary;
        density = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(false);
        setClickable(false);
    }

    static int sidePaddingPx(int screenW, int screenH, int columns, int rowsBelow, float density) {
        int gridH = screenH - Math.round((CONTENT_TOP_DP + 10 + CONTENT_BOTTOM_DP) * density);
        int widest = screenW - Math.round((2 * 56 + 20) * density);
        int tileW = AppAdapter.tileWidthPx(widest, gridH, columns, rowsBelow, false, false, density);
        int cell = Math.round(tileW * AppAdapter.FOCUS_SCALE + 16 * density);
        int side = (screenW - cell * columns - Math.round(20 * density)) / 2;
        return Math.max(Math.round(56 * density), side);
    }

    static Drawable tileBackground() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, TILE_COLORS);
    }

    static ChannelHome install(MainActivity host, Config cfg, FrameLayout root, RecyclerView grid, AppAdapter adapter,
                               String clockFormat, int sidePadding, View btnSettings) {
        float density = host.getResources().getDisplayMetrics().density;
        int accent = cfg.channelAccent;
        ChannelHome home = new ChannelHome(host, grid, adapter, btnSettings, cfg.columns, cfg.rowsBelowDock + 1,
                sidePadding, accent, cfg.appLibrary);

        Typeface medium = UiFont.get(host, R.string.font_medium);
        TextClock clock = new TextClock(host);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(INK);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f);
        clock.setTypeface(medium);
        clock.setIncludeFontPadding(false);
        LayoutParams clockLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        clockLp.topMargin = Math.round((SHOULDER_DP + 4) * density);
        home.addView(clock, clockLp);

        TextView date = new TextView(host);
        date.setTextColor(INK);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        date.setTypeface(medium);
        date.setSingleLine(true);
        date.setIncludeFontPadding(false);
        home.date = date;
        home.dateFormat = dateFormat(cfg.channelDateFormat, Locale.getDefault());
        home.updateDate();
        LayoutParams dateLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        dateLp.topMargin = Math.round((DIP_DP + 12) * density);
        home.addView(date, dateLp);
        ClockFormat.apply(clock, clockFormat);

        int big = Math.round(72 * density), small = Math.round(56 * density);
        int edgePx = Math.round(46 * density);
        int shoulderMid = Math.round((BAR_DP + SHOULDER_DP) / 2f * density);

        Bitmap pic = cfg.profilePicture == null ? null : circlePhoto(cfg.profilePicture.getPath(), big - Math.round(6 * density));
        View profile;
        if (pic != null || cfg.profileName.isEmpty()) {
            ImageView iv = new ImageView(host);
            iv.setScaleType(ImageView.ScaleType.CENTER);
            iv.setImageBitmap(pic != null ? pic : personIcon(big - Math.round(6 * density), accent));
            profile = iv;
        } else {
            TextView tv = new TextView(host);
            tv.setGravity(Gravity.CENTER);
            tv.setIncludeFontPadding(false);
            tv.setTypeface(medium);
            tv.setTextColor(accent);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, (big / 2f - 3f * density) * 1.05f);
            tv.setText(cfg.profileName.substring(0, cfg.profileName.offsetByCodePoints(0, 1)).toUpperCase(Locale.getDefault()));
            profile = tv;
        }
        profile.setContentDescription(cfg.profileName.isEmpty() ? host.getString(R.string.settings) : cfg.profileName);
        home.profile = profile;
        home.add(profile, big, Gravity.START, edgePx, shoulderMid, cfg.appLibrary);

        PackageManager pm = host.getPackageManager();
        Intent store = cfg.storePackage.isEmpty() ? null : pm.getLeanbackLaunchIntentForPackage(cfg.storePackage);
        if (store == null && !cfg.storePackage.isEmpty()) store = pm.getLaunchIntentForPackage(cfg.storePackage);
        if (store != null) {
            try {
                ImageView shop = home.iconButton(pm.getApplicationIcon(cfg.storePackage), small, 0.24f, false);
                shop.setContentDescription(pm.getApplicationLabel(pm.getApplicationInfo(cfg.storePackage, 0)));
                home.storeIntent = store;
                home.shop = shop;
                home.add(shop, small, Gravity.START, edgePx + big + Math.round(16 * density), shoulderMid, false);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        } else if (cfg.appLibrary) {
            ImageView lib = home.iconButton(host.getDrawable(R.drawable.ic_apps_pill).mutate(), small, 0.27f, true);
            lib.setContentDescription(host.getString(R.string.app_library));
            home.library = lib;
            home.add(lib, small, Gravity.START, edgePx + big + Math.round(16 * density), shoulderMid, false);
        }

        ImageView settings = home.iconButton(host.getDrawable(R.drawable.ic_settings).mutate(), big, 0.27f, true);
        settings.setContentDescription(host.getString(R.string.settings));
        ImageView network = new ImageView(host);
        network.setScaleType(ImageView.ScaleType.CENTER);
        network.setContentDescription(host.getString(R.string.wifi));
        home.network = network;
        home.settings = settings;
        home.showNetwork(true, false, 3);
        home.add(network, small, Gravity.END, edgePx + big + Math.round(16 * density), shoulderMid, true);
        home.add(settings, big, Gravity.END, edgePx, shoulderMid, true);

        grid.addOnLayoutChangeListener(home);
        grid.setOnScrollChangeListener(home);
        root.addView(home, root.getChildCount() - 1,
                new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return home;
    }

    private ImageView iconButton(Drawable d, int size, float padShare, boolean tint) {
        ImageView iv = new ImageView(host);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = Math.round(size * padShare);
        iv.setPadding(p, p, p, p);
        if (tint) d.setTint(accent);
        iv.setImageDrawable(d);
        return iv;
    }

    private Drawable disc(boolean focused) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFFFFFFF, 0xFFECEEF3});
        d.setShape(GradientDrawable.OVAL);
        d.setStroke(Math.round((focused ? 5f : 3f) * density), accent);
        return d;
    }

    View settingsButton() {
        return settings;
    }

    @Override
    public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
        invalidate();
    }

    @Override
    public void onScrollChange(View v, int sx, int sy, int osx, int osy) {
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int top = Math.max(0, MeasureSpec.getSize(heightMeasureSpec) - Math.round(BAR_DP * density));
        if (getPaddingTop() != top) setPadding(0, top, 0, 0);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float top = h - Math.round(BAR_DP * density);
        float shoulder = top + SHOULDER_DP * density, dip = top + DIP_DP * density;
        float x1 = w * CURVE_FROM, x2 = w * CURVE_TO, bend = (x2 - x1) * 0.5f;
        edge.rewind();
        edge.moveTo(-8 * density, shoulder);
        edge.lineTo(x1, shoulder);
        edge.cubicTo(x1 + bend, shoulder, x2 - bend, dip, x2, dip);
        edge.lineTo(w - x2, dip);
        edge.cubicTo(w - x2 + bend, dip, w - x1 - bend, shoulder, w - x1, shoulder);
        edge.lineTo(w + 8 * density, shoulder);
        band.set(edge);
        band.lineTo(w + 8 * density, h);
        band.lineTo(-8 * density, h);
        band.close();
        bandShader = new LinearGradient(0, shoulder, 0, h, 0xFFE4E7ED, 0xFFCDD1DA, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int count = adapter.appCount();
        int pages = Math.max(1, ((count + columns - 1) / columns + rows - 1) / rows);
        int page = 0;
        View cell = null, card = null;
        int pos = RecyclerView.NO_POSITION;
        for (int i = 0; i < grid.getChildCount() && card == null; i++) {
            cell = grid.getChildAt(i);
            pos = grid.getChildAdapterPosition(cell);
            card = pos == RecyclerView.NO_POSITION || pos >= count ? null : cell.findViewById(R.id.card);
        }
        if (card != null && card.getWidth() > 0 && cell.getHeight() > 0) {
            int cellW = cell.getWidth(), cellH = cell.getHeight();
            View gridHost = (View) grid.getParent();
            int pageBottom = grid.getTop() + grid.getPaddingTop() + rows * cellH + Math.round(2 * density);
            Rect clip = gridHost.getClipBounds();
            if (clip == null || clip.bottom != pageBottom || clip.right != gridHost.getWidth()) {
                gridHost.setClipBounds(new Rect(0, 0, gridHost.getWidth(), pageBottom));
            }
            int offset = grid.getPaddingTop() + (pos / columns) * cellH - cell.getTop();
            page = Math.max(0, Math.min(pages - 1, Math.round(offset / (float) (rows * cellH))));
            int total = pages * rows * columns;
            if (count < total) {
                tmpRect.set(0, 0, card.getWidth(), card.getHeight());
                ((ViewGroup) getParent()).offsetDescendantRectToMyCoords(card, tmpRect);
                float left0 = tmpRect.left - (pos % columns) * cellW;
                float top0 = tmpRect.top - (pos / columns) * cellH;
                tmpRect.set(0, 0, gridHost.getWidth(), pageBottom);
                ((ViewGroup) getParent()).offsetDescendantRectToMyCoords(gridHost, tmpRect);
                float radius = AppAdapter.cornerRadius(card.getWidth(), density);
                if (shaderHeight != card.getHeight()) {
                    shaderHeight = card.getHeight();
                    tileShader = new LinearGradient(0, 0, 0, shaderHeight, TILE_COLORS, null, Shader.TileMode.CLAMP);
                }
                canvas.save();
                canvas.clipRect(tmpRect);
                for (int i = count; i < total; i++) {
                    float left = left0 + (i % columns) * cellW, top = top0 + (i / columns) * cellH;
                    if (top > tmpRect.bottom || top + card.getHeight() < tmpRect.top) continue;
                    canvas.save();
                    canvas.translate(left, top);
                    rect.set(0, 0, card.getWidth(), card.getHeight());
                    paint.setStyle(Paint.Style.FILL);
                    paint.setShader(tileShader);
                    canvas.drawRoundRect(rect, radius, radius, paint);
                    paint.setShader(null);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(density);
                    paint.setColor(0x14203050);
                    canvas.drawRoundRect(rect, radius, radius, paint);
                    canvas.restore();
                }
                canvas.restore();
            }
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(bandShader);
        canvas.drawPath(band, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * density);
        paint.setColor(0xCCFFFFFF);
        canvas.save();
        canvas.translate(0, 3f * density);
        canvas.drawPath(edge, paint);
        canvas.restore();
        paint.setStrokeWidth(4f * density);
        paint.setColor(accent);
        canvas.drawPath(edge, paint);
        if (pages > 1) {
            paint.setStyle(Paint.Style.FILL);
            int dotsW = Math.round(20 * density);
            float cx = getWidth() - Math.max(0, (sidePadding - dotsW) / 2) - dotsW / 2f;
            float step = 18f * density, radius = 4.5f * density;
            float y = (getHeight() - Math.round(CONTENT_BOTTOM_DP * density)) / 2f - step * (pages - 1) / 2f;
            for (int i = 0; i < pages; i++) {
                paint.setColor(i == page ? accent : 0x33203050);
                canvas.drawCircle(cx, y + i * step, i == page ? radius * 1.25f : radius, paint);
            }
        }
    }

    private void add(View button, int size, int side, int margin, int centerY, boolean longClick) {
        button.setFocusable(true);
        button.setClickable(true);
        button.setBackground(disc(false));
        button.setElevation(3f * density);
        button.setOnFocusChangeListener(this);
        button.setOnKeyListener(this);
        button.setOnClickListener(this);
        if (longClick) button.setOnLongClickListener(this);
        LayoutParams lp = new LayoutParams(size, size, Gravity.TOP | side);
        lp.topMargin = centerY - size / 2;
        if (side == Gravity.START) lp.leftMargin = margin;
        else lp.rightMargin = margin;
        addView(button, lp);
        buttons.add(button);
    }

    @Override
    public void onClick(View v) {
        if (v == profile) host.open(SystemSettings.intent(host, Settings.ACTION_SYNC_SETTINGS));
        else if (v == shop && storeIntent != null) host.open(new Intent(storeIntent));
        else if (v == library) host.onLibraryRequested();
        else if (v == network) host.openNetworkPill();
        else if (v == settings) btnSettings.performClick();
    }

    @Override
    public boolean onLongClick(View v) {
        if (v == settings) btnSettings.performLongClick();
        else if (v == profile && appLibrary) host.onLibraryRequested();
        else if (v == network) host.open(SystemSettings.intent(host, Settings.ACTION_WIFI_SETTINGS));
        else return false;
        return true;
    }

    @Override
    public void onFocusChange(View v, boolean focused) {
        v.setBackground(disc(focused));
        v.animate().scaleX(focused ? 1.14f : 1f).scaleY(focused ? 1.14f : 1f).setDuration(140).start();
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (RemoteKeys.menuReleased(event.getAction(), event.getFlags())) btnSettings.performLongClick();
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                host.focusTile(MainActivity.lastFocused);
                v.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                int next = buttons.indexOf(v) + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                if (next >= 0 && next < buttons.size()) {
                    buttons.get(next).requestFocus();
                    v.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                            ? SoundEffectConstants.NAVIGATION_LEFT : SoundEffectConstants.NAVIGATION_RIGHT);
                }
                return true;
            default:
                return false;
        }
    }

    void showNetwork(boolean online, boolean ethernet, int level) {
        if (network == null) return;
        level = Math.max(0, Math.min(4, level));
        int state = !online ? -1 : ethernet ? 5 : level;
        if (state == netState) return;
        netState = state;
        int inner = Math.round(44 * density);
        if (state == 5) {
            Drawable plug = host.getDrawable(R.drawable.ic_ethernet).mutate();
            plug.setTint(accent);
            int pad = Math.round(15 * density);
            network.setPadding(pad, pad, pad, pad);
            network.setScaleType(ImageView.ScaleType.FIT_CENTER);
            network.setImageDrawable(plug);
            return;
        }
        network.setPadding(0, 0, 0, 0);
        network.setScaleType(ImageView.ScaleType.CENTER);
        Bitmap bmp = Bitmap.createBitmap(inner, inner, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = inner / 2f, cy = inner / 2f, r = inner / 2f;
        int pale = 0x40203050;
        float base = cy + r * 0.42f, w = r * 0.16f;
        p.setColor(online && level > 0 ? accent : pale);
        c.drawCircle(cx, base - w * 0.3f, w * 0.75f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(w);
        RectF oval = new RectF();
        for (int i = 1; i <= 3; i++) {
            float rad = r * (0.30f + 0.26f * (i - 1));
            p.setColor(online && level > i ? accent : pale);
            oval.set(cx - rad, base - rad, cx + rad, base + rad);
            c.drawArc(oval, 225f, 90f, false, p);
        }
        if (!online) {
            p.setColor(accent);
            p.setStrokeWidth(w * 0.8f);
            c.drawLine(cx - r * 0.55f, cy + r * 0.45f, cx + r * 0.55f, cy - r * 0.55f, p);
        }
        network.setImageBitmap(bmp);
    }

    void updateDate() {
        if (date == null) return;
        String text = java.time.LocalDate.now().format(dateFormat);
        if (!text.isEmpty()) text = text.substring(0, 1).toUpperCase(Locale.getDefault()) + text.substring(1);
        if (!text.contentEquals(date.getText())) date.setText(text);
    }

    static java.time.format.DateTimeFormatter dateFormat(String pattern, Locale locale) {
        if (pattern != null && !pattern.isEmpty()) {
            try {
                return java.time.format.DateTimeFormatter.ofPattern(pattern, locale);
            } catch (IllegalArgumentException ignored) {
            }
        }
        String own = "es".equals(locale.getLanguage()) ? "EEEE d 'de' MMMM 'de' y"
                : android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMMy");
        return java.time.format.DateTimeFormatter.ofPattern(own, locale);
    }

    static int buttonIndexForColumn(int column, int columns, int buttonCount) {
        if (buttonCount <= 1 || columns <= 1 || column <= 0) return 0;
        if (column >= columns - 1) return buttonCount - 1;
        return column * 2 < columns ? Math.max(0, buttonCount - 3) : Math.max(0, buttonCount - 2);
    }

    void focusFrom(int column) {
        if (buttons.isEmpty()) return;
        View target = buttons.get(buttonIndexForColumn(column, columns, buttons.size()));
        target.requestFocus();
        target.playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN);
    }

    private static Bitmap circlePhoto(String path, int size) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (Math.min(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= size) o.inSampleSize *= 2;
        Bitmap raw = BitmapFactory.decodeFile(path, o);
        if (raw == null) return null;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader s = new BitmapShader(raw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        float scale = Math.max(size / (float) raw.getWidth(), size / (float) raw.getHeight());
        m.setScale(scale, scale);
        m.postTranslate((size - raw.getWidth() * scale) / 2f, (size - raw.getHeight() * scale) / 2f);
        s.setLocalMatrix(m);
        p.setShader(s);
        new Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, p);
        return out;
    }

    private static Bitmap personIcon(int size, int accent) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(accent);
        float r = size / 2f;
        c.drawCircle(r, r - r * 0.22f, r * 0.30f, p);
        c.clipRect(0, 0, size, r + r * 0.72f);
        c.drawOval(r - r * 0.56f, r + r * 0.18f, r + r * 0.56f, r + r * 1.30f, p);
        return out;
    }
}
