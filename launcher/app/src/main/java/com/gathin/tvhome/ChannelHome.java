package com.gathin.tvhome;

import android.content.Context;
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
import android.widget.TextClock;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * channel_home: the light "channels" Home. The header is gone; the apps are one plain grid of light tiles, with
 * empty slots drawn where the grid has no app yet, and a bar with a curved top edge runs along the bottom of the
 * screen: the clock above its dip, the date inside it, a profile button and the store on the left shoulder and
 * Settings on the right one.
 *
 * Everything here is drawn in code (no new layout or drawable), so a launcher without the key pays nothing for it.
 */
final class ChannelHome {

    /** What the bar needs from MainActivity. */
    interface Host {
        /** Focus goes back to the tile it left. */
        void focusLastTile();

        void openIntent(Intent intent);

        /** What the header's Settings button does on OK, and on a held OK. */
        void openSettings();

        void openSettingsMenu();

        /** What the header's network pill does on OK. */
        void openNetwork();
    }

    /** The bar's height, the height of its two shoulders' top edge and of the dip between them, from the bar's top. */
    static final float BAR_DP = 150, SHOULDER_DP = 38, DIP_DP = 88;
    /** Padding of the content above the bar: the rows end where the shoulders begin. */
    static final float CONTENT_BOTTOM_DP = BAR_DP - SHOULDER_DP, CONTENT_TOP_DP = 10;
    /** Where the edge leaves the shoulder and where it reaches the dip, as shares of the width (mirrored on the right). */
    private static final float CURVE_FROM = 0.215f, CURVE_TO = 0.385f;

    /** Card behind an icon tile and the fill of an empty slot: a soft light-grey sheen. */
    static final int[] TILE_COLORS = {0xFFF7F7F9, 0xFFE0E2E7, 0xFFEBEDF1};
    /** Names and labels on the light background. */
    static final int INK = 0xFF2B3A55;

    private final Host host;
    private final int columns;
    private final List<RoundButton> buttons = new ArrayList<>();
    private RoundButton network;
    private TextView date;
    private java.time.format.DateTimeFormatter dateFormat;

    private ChannelHome(Host host, int columns) {
        this.host = host;
        this.columns = columns;
    }

    /** Side padding of the content, so four tiles sit close together in the middle instead of spread over the width. */
    static int sidePaddingPx(int screenW, int screenH, int columns, int rowsBelow, float density) {
        int gridH = screenH - Math.round((CONTENT_TOP_DP + 10 + CONTENT_BOTTOM_DP) * density);
        int widest = screenW - Math.round((2 * 56 + 20) * density);
        int tileW = AppAdapter.tileWidthPx(widest, gridH, columns, rowsBelow, false, false, density);
        int cell = Math.round(tileW * AppAdapter.FOCUS_SCALE + 16 * density);
        int side = (screenW - cell * columns - Math.round(20 * density)) / 2;
        return Math.max(Math.round(56 * density), side);
    }

    /** The light card of a tile. */
    static Drawable tileBackground() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, TILE_COLORS);
    }

    static ChannelHome install(Context ctx, Config cfg, FrameLayout root, RecyclerView grid, AppAdapter adapter,
                               String clockFormat, int sidePadding, Host host) {
        float density = ctx.getResources().getDisplayMetrics().density;
        ChannelHome home = new ChannelHome(host, cfg.columns);
        int accent = cfg.channelAccent;

        FrameLayout bar = new FrameLayout(ctx);
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.addView(new BarView(ctx, accent), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Typeface medium = ctx.getResources().getFont(R.font.outfit_medium);
        TextClock clock = new TextClock(ctx);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(INK);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f);
        clock.setTypeface(medium);
        clock.setIncludeFontPadding(false);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        clockLp.topMargin = Math.round((SHOULDER_DP + 4) * density);
        bar.addView(clock, clockLp);

        TextView date = new TextView(ctx);
        date.setTextColor(INK);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        date.setTypeface(medium);
        date.setSingleLine(true);
        date.setIncludeFontPadding(false);
        home.date = date;
        home.dateFormat = dateFormat(cfg.channelDateFormat);
        home.updateDate();
        FrameLayout.LayoutParams dateLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        dateLp.topMargin = Math.round((DIP_DP + 12) * density);
        bar.addView(date, dateLp);
        ClockFormat.apply(clock, clockFormat);

        int big = Math.round(72 * density), small = Math.round(56 * density);
        int edge = Math.round(46 * density);
        // The buttons sit in the middle of the shoulder's height.
        int shoulderMid = Math.round((BAR_DP + SHOULDER_DP) / 2f * density);

        RoundButton profile = new RoundButton(ctx, accent);
        profile.setProfile(cfg.profilePicture == null ? null : decode(cfg.profilePicture.getPath(), big), cfg.profileName);
        profile.setContentDescription(cfg.profileName.isEmpty() ? ctx.getString(R.string.settings) : cfg.profileName);
        profile.setOnClickListener(v -> host.openIntent(new Intent(Settings.ACTION_SYNC_SETTINGS)));
        home.add(bar, profile, big, Gravity.START, edge, shoulderMid);

        PackageManager pm = ctx.getPackageManager();
        Intent store = cfg.storePackage.isEmpty() ? null : pm.getLeanbackLaunchIntentForPackage(cfg.storePackage);
        if (store == null && !cfg.storePackage.isEmpty()) store = pm.getLaunchIntentForPackage(cfg.storePackage);
        if (store != null) {
            try {
                RoundButton shop = new RoundButton(ctx, accent);
                shop.setIcon(pm.getApplicationIcon(cfg.storePackage), 0.56f, false);
                shop.setContentDescription(pm.getApplicationLabel(pm.getApplicationInfo(cfg.storePackage, 0)));
                Intent target = store;
                shop.setOnClickListener(v -> host.openIntent(new Intent(target)));
                home.add(bar, shop, small, Gravity.START, edge + big + Math.round(16 * density), shoulderMid);
            } catch (PackageManager.NameNotFoundException ignored) {
                // uninstalled between the two calls: the bar goes without the button
            }
        }

        RoundButton settings = new RoundButton(ctx, accent);
        settings.setIcon(ctx.getDrawable(R.drawable.ic_settings).mutate(), 0.50f, true);
        settings.setContentDescription(ctx.getString(R.string.settings));
        settings.setOnClickListener(v -> host.openSettings());
        settings.setOnLongClickListener(v -> {
            host.openSettingsMenu();
            return true;
        });
        RoundButton network = new RoundButton(ctx, accent);
        network.setContentDescription(ctx.getString(R.string.wifi));
        network.setOnClickListener(v -> host.openNetwork());
        network.setOnLongClickListener(v -> {
            host.openIntent(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        network.setNetwork(true, false, 3);
        home.network = network;
        home.add(bar, network, small, Gravity.END, edge + big + Math.round(16 * density), shoulderMid);
        home.add(bar, settings, big, Gravity.END, edge, shoulderMid);

        root.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(BAR_DP * density), Gravity.BOTTOM));
        PageDots dots = new PageDots(ctx, accent);
        int dotsW = Math.round(20 * density);
        FrameLayout.LayoutParams dotsLp = new FrameLayout.LayoutParams(dotsW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        dotsLp.rightMargin = Math.max(0, (sidePadding - dotsW) / 2);
        dotsLp.bottomMargin = Math.round(CONTENT_BOTTOM_DP * density);
        root.addView(dots, dotsLp);
        grid.addItemDecoration(new EmptySlots(adapter, cfg.columns, cfg.rowsBelowDock + 1, density, dots));
        return home;
    }

    private void add(FrameLayout bar, RoundButton button, int size, int side, int margin, int centerY) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | side);
        lp.topMargin = centerY - size / 2;
        if (side == Gravity.START) lp.leftMargin = margin;
        else lp.rightMargin = margin;
        bar.addView(button, lp);
        int index = buttons.size();
        buttons.add(button);
        button.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    host.focusLastTile();
                    v.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    int next = index + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    if (next >= 0 && next < buttons.size()) {
                        buttons.get(next).requestFocus();
                        v.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                                ? SoundEffectConstants.NAVIGATION_LEFT : SoundEffectConstants.NAVIGATION_RIGHT);
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    /** The network button: Wi-Fi with its signal in four bars, Ethernet, or offline. level is 0 to 4. */
    void showNetwork(boolean online, boolean ethernet, int level) {
        if (network != null) network.setNetwork(online, ethernet, level);
    }

    /** The full date, "Lunes 16 de noviembre de 2026": once a minute and when the clock or time zone changes. */
    void updateDate() {
        if (date == null) return;
        String text = java.time.LocalDate.now().format(dateFormat);
        if (!text.isEmpty()) text = text.substring(0, 1).toUpperCase(Locale.getDefault()) + text.substring(1);
        if (!text.contentEquals(date.getText())) date.setText(text);
    }

    /**
     * channel_date_format as a java.time pattern, or the language's own long date when it is empty or broken:
     * Spanish gets "EEEE d 'de' MMMM 'de' y" (no comma after the weekday), the others Android's best pattern.
     */
    private static java.time.format.DateTimeFormatter dateFormat(String pattern) {
        Locale locale = Locale.getDefault();
        if (pattern != null && !pattern.isEmpty()) {
            try {
                return java.time.format.DateTimeFormatter.ofPattern(pattern, locale);
            } catch (IllegalArgumentException ignored) {
                // a broken pattern falls back to the language's own
            }
        }
        String own = "es".equals(locale.getLanguage()) ? "EEEE d 'de' MMMM 'de' y"
                : android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMMy");
        return java.time.format.DateTimeFormatter.ofPattern(own, locale);
    }

    /** Down from the last row: the left half of the grid lands on the profile button, the right half on Settings. */
    void focusFrom(int column) {
        if (buttons.isEmpty()) return;
        RoundButton target = column * 2 < columns ? buttons.get(0) : buttons.get(buttons.size() - 1);
        target.requestFocus();
        target.playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN);
    }

    /** A picture scaled down to about the button's size, or null when the file is not a picture. */
    private static Bitmap decode(String path, int size) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.min(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= size) options.inSampleSize *= 2;
        return BitmapFactory.decodeFile(path, options);
    }

    /** The bar: a light-grey band whose top edge dips in the middle, with the accent line along that edge. */
    private static final class BarView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path band = new Path();
        private final Path edge = new Path();
        private final float density;

        BarView(Context ctx, int accent) {
            super(ctx);
            density = getResources().getDisplayMetrics().density;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(4f * density);
            line.setColor(accent);
            sheen.setStyle(Paint.Style.STROKE);
            sheen.setStrokeWidth(2f * density);
            sheen.setColor(0xCCFFFFFF);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float shoulder = SHOULDER_DP * density, dip = DIP_DP * density;
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
            fill.setShader(new LinearGradient(0, shoulder, 0, h, 0xFFE4E7ED, 0xFFCDD1DA, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawPath(band, fill);
            // A white hairline just under the accent line: the edge reads as a lit lip, not a flat cut.
            canvas.save();
            canvas.translate(0, 3f * density);
            canvas.drawPath(edge, sheen);
            canvas.restore();
            canvas.drawPath(edge, line);
        }
    }

    /** A round button of the bar: a white disc with the accent ring, holding a picture, an initial or an icon. */
    private static final class RoundButton extends View {
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint photo = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix photoMatrix = new Matrix();
        private final RectF oval = new RectF();
        private final float density;
        private final int accent;
        private Drawable icon;
        private float iconShare;
        private Bitmap picture;
        private String initial = "";
        private boolean person;
        /** setNetwork: drawn as Wi-Fi bars or the Ethernet plug instead of an icon. */
        private boolean networkMode, online, ethernet;
        private int level;
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Drawable plug;

        RoundButton(Context ctx, int accent) {
            super(ctx);
            this.accent = accent;
            density = getResources().getDisplayMetrics().density;
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(accent);
            shadow.setColor(0x33203050);
            ink.setColor(accent);
            ink.setTextAlign(Paint.Align.CENTER);
            ink.setTypeface(ctx.getResources().getFont(R.font.outfit_medium));
            setFocusable(true);
            setClickable(true);
            setOnFocusChangeListener((v, focused) -> {
                v.animate().scaleX(focused ? 1.14f : 1f).scaleY(focused ? 1.14f : 1f).setDuration(140).start();
                invalidate();
            });
        }

        void setNetwork(boolean online, boolean ethernet, int level) {
            level = Math.max(0, Math.min(4, level));
            if (networkMode && online == this.online && ethernet == this.ethernet && level == this.level) return;
            networkMode = true;
            this.online = online;
            this.ethernet = ethernet;
            this.level = level;
            if (ethernet && plug == null) {
                plug = getContext().getDrawable(R.drawable.ic_ethernet).mutate();
                plug.setTint(accent);
            }
            invalidate();
        }

        void setIcon(Drawable drawable, float share, boolean tint) {
            icon = drawable;
            iconShare = share;
            if (tint) icon.setTint(accent);
        }

        /** The profile button: the picture if there is one, else the name's initial, else a plain figure. */
        void setProfile(Bitmap bitmap, String name) {
            picture = bitmap;
            initial = name == null || name.isEmpty() ? ""
                    : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(Locale.getDefault());
            person = picture == null && initial.isEmpty();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            disc.setShader(new LinearGradient(0, 0, 0, h, 0xFFFFFFFF, 0xFFECEEF3, Shader.TileMode.CLAMP));
            if (picture != null) {
                BitmapShader shader = new BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                float scale = Math.max(w / (float) picture.getWidth(), h / (float) picture.getHeight());
                photoMatrix.setScale(scale, scale);
                photoMatrix.postTranslate((w - picture.getWidth() * scale) / 2f, (h - picture.getHeight() * scale) / 2f);
                shader.setLocalMatrix(photoMatrix);
                photo.setShader(shader);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            boolean focused = isFocused();
            float stroke = (focused ? 5f : 3f) * density;
            float r = Math.min(cx, cy) - 3f * density;
            canvas.drawCircle(cx, cy + 2f * density, r + 1f * density, shadow);
            canvas.drawCircle(cx, cy, r, disc);
            float inner = r - stroke;
            if (picture != null) {
                canvas.drawCircle(cx, cy, inner, photo);
            } else if (!initial.isEmpty()) {
                ink.setStyle(Paint.Style.FILL);
                ink.setTextSize(r * 1.05f);
                canvas.drawText(initial, cx, cy - (ink.ascent() + ink.descent()) / 2f, ink);
            } else if (person) {
                // A head and shoulders, clipped by the disc.
                ink.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy - inner * 0.22f, inner * 0.30f, ink);
                oval.set(cx - inner * 0.56f, cy + inner * 0.18f, cx + inner * 0.56f, cy + inner * 1.30f);
                canvas.save();
                canvas.clipRect(cx - inner, cy - inner, cx + inner, cy + inner * 0.72f);
                canvas.drawOval(oval, ink);
                canvas.restore();
            } else if (networkMode) {
                drawNetwork(canvas, cx, cy, inner);
            } else if (icon != null) {
                int half = Math.round(r * iconShare);
                icon.setBounds(Math.round(cx) - half, Math.round(cy) - half, Math.round(cx) + half, Math.round(cy) + half);
                icon.draw(canvas);
            }
            ring.setStrokeWidth(stroke);
            canvas.drawCircle(cx, cy, r - stroke / 2f, ring);
        }

        /**
         * Wi-Fi as a dot and three arcs: the dot and as many arcs as the signal has bars beyond the first are in the
         * accent, the rest pale. Offline, everything is pale with a slash through it. Ethernet is the plug icon.
         */
        private void drawNetwork(Canvas canvas, float cx, float cy, float inner) {
            if (online && ethernet && plug != null) {
                int half = Math.round(inner * 0.55f);
                plug.setBounds(Math.round(cx) - half, Math.round(cy) - half, Math.round(cx) + half, Math.round(cy) + half);
                plug.draw(canvas);
                return;
            }
            int pale = 0x40203050;
            float base = cy + inner * 0.42f;
            float width = inner * 0.16f;
            arc.setStyle(Paint.Style.FILL);
            arc.setColor(online && level > 0 ? accent : pale);
            canvas.drawCircle(cx, base - width * 0.3f, width * 0.75f, arc);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setStrokeWidth(width);
            for (int i = 1; i <= 3; i++) {
                float radius = inner * (0.30f + 0.26f * (i - 1));
                arc.setColor(online && level > i ? accent : pale);
                oval.set(cx - radius, base - radius, cx + radius, base + radius);
                canvas.drawArc(oval, 225f, 90f, false, arc);
            }
            if (!online) {
                arc.setColor(accent);
                arc.setStrokeWidth(width * 0.8f);
                canvas.drawLine(cx - inner * 0.55f, cy + inner * 0.45f, cx + inner * 0.55f, cy - inner * 0.55f, arc);
            }
        }
    }

    /** One dot per page of the grid, down the right margin, the shown page in the accent colour. Hidden for one page. */
    private static final class PageDots extends View {
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final int accent;
        private int pages = 1, page;

        PageDots(Context ctx, int accent) {
            super(ctx);
            this.accent = accent;
            density = getResources().getDisplayMetrics().density;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void show(int pages, int page) {
            page = Math.max(0, Math.min(pages - 1, page));
            if (pages == this.pages && page == this.page) return;
            this.pages = pages;
            this.page = page;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (pages <= 1) return;
            float step = 18f * density, radius = 4.5f * density;
            float y = getHeight() / 2f - step * (pages - 1) / 2f;
            for (int i = 0; i < pages; i++) {
                dot.setColor(i == page ? accent : 0x33203050);
                canvas.drawCircle(getWidth() / 2f, y + i * step, i == page ? radius * 1.25f : radius, dot);
            }
        }
    }

    /**
     * The grid's free places as empty light tiles: the last row is filled out, and so are the rows the screen shows
     * at once, so a Home with five apps still reads as a full board of channels.
     */
    private static final class EmptySlots extends RecyclerView.ItemDecoration {
        private final AppAdapter adapter;
        private final int columns;
        private final int rows;
        private final float density;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int shaderHeight = -1;
        private final PageDots dots;

        EmptySlots(AppAdapter adapter, int columns, int rows, float density, PageDots dots) {
            this.dots = dots;
            this.adapter = adapter;
            this.columns = Math.max(1, columns);
            this.rows = rows;
            this.density = density;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(density);
            rim.setColor(0x14203050);
        }

        @Override
        public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            int count = adapter.appCount();
            // Whole pages: the grid turns a page of rows at a time, and the last page is filled out too.
            int pages = Math.max(1, ((count + columns - 1) / columns + rows - 1) / rows);
            int total = pages * rows * columns;
            View cell = null, card = null;
            int position = RecyclerView.NO_POSITION;
            for (int i = 0; i < parent.getChildCount() && card == null; i++) {
                cell = parent.getChildAt(i);
                position = parent.getChildAdapterPosition(cell);
                card = position == RecyclerView.NO_POSITION || position >= count ? null : cell.findViewById(R.id.card);
            }
            if (card == null || card.getWidth() <= 0 || cell.getHeight() <= 0) {
                dots.show(pages, 0);
                return;
            }
            int cellW = cell.getWidth(), cellH = cell.getHeight();
            // One page of rows and nothing of the next, measured on a real cell: the room the plain grid keeps
            // under its last row would show the top of the following page above the bar. Set again only when
            // it changes, since setting it redraws the grid and lands back here.
            View host = (View) parent.getParent();
            int pageBottom = parent.getTop() + parent.getPaddingTop() + rows * cellH + Math.round(2 * density);
            Rect clip = host.getClipBounds();
            if (clip == null || clip.bottom != pageBottom || clip.right != host.getWidth()) {
                host.setClipBounds(new Rect(0, 0, host.getWidth(), pageBottom));
            }
            int offset = parent.getPaddingTop() + (position / columns) * cellH - cell.getTop();
            dots.show(pages, Math.round(offset / (float) (rows * cellH)));
            if (count >= total) return;
            float left0 = cell.getLeft() - (position % columns) * cellW + card.getLeft();
            float top0 = cell.getTop() - (position / columns) * cellH + card.getTop();
            float radius = AppAdapter.cornerRadius(card.getWidth(), density);
            if (shaderHeight != card.getHeight()) {
                shaderHeight = card.getHeight();
                fill.setShader(new LinearGradient(0, 0, 0, shaderHeight, TILE_COLORS, null, Shader.TileMode.CLAMP));
            }
            for (int i = count; i < total; i++) {
                float left = left0 + (i % columns) * cellW, top = top0 + (i / columns) * cellH;
                if (top > parent.getHeight() || top + card.getHeight() < 0) continue;
                canvas.save();
                canvas.translate(left, top);
                rect.set(0, 0, card.getWidth(), card.getHeight());
                canvas.drawRoundRect(rect, radius, radius, fill);
                canvas.drawRoundRect(rect, radius, radius, rim);
                canvas.restore();
            }
        }
    }
}
