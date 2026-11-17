package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * A transparent full-screen overlay that plays a one-off shower of marigold petals.
 */
public final class PetalShowerView extends View implements Runnable {

    private static final int[] COLORS = {0xFFFF8C1A, 0xFFFFB627, 0xFFF26A0D, 0xFFFFC93C, 0xFFE85D04};
    private static final int EDGE = 0xFF7A2800, STRIDE = 14;
    private static final double TAU = 2 * Math.PI;

    private final float density;
    private final Random rnd = new Random();
    private final Path[] shapes = new Path[3], veins = new Path[3];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int count;
    private float[] data = new float[0];
    private boolean playing;
    private long start, duration;
    private Runnable onDone;

    public PetalShowerView(Context context) { this(context, null, 0); }
    public PetalShowerView(Context context, AttributeSet attrs) { this(context, attrs, 0); }

    public PetalShowerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = context.getResources().getDisplayMetrics().density;
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        Random seeded = new Random(0x9E7A1L);
        for (int i = 0; i < shapes.length; i++) {
            shapes[i] = petalShape(seeded);
            veins[i] = veinPath(seeded);
        }
    }

    public void play(int petals, long durationMs, Runnable done) {
        if (playing) {
            removeCallbacks(this);
            run();
        }
        int n = Math.max(0, petals);
        if (data.length < n * STRIDE) data = new float[n * STRIDE];
        count = n;
        duration = Math.max(1, durationMs);
        onDone = done;
        for (int i = 0; i < n; i++) {
            int o = i * STRIDE;
            data[o] = (i + rnd.nextFloat()) / n;
            data[o + 1] = rnd.nextFloat() * 0.32f;
            data[o + 2] = 0.6f + rnd.nextFloat() * (1f - data[o + 1] - 0.6f);
            data[o + 3] = 0.8f + rnd.nextFloat() * 0.35f;
            data[o + 4] = 9f + rnd.nextFloat() * 7f;
            data[o + 5] = 10f + rnd.nextFloat() * 24f;
            data[o + 6] = 0.5f + rnd.nextFloat() * 0.6f;
            data[o + 7] = rnd.nextFloat() * (float) TAU;
            data[o + 8] = rnd.nextFloat() * 360f;
            data[o + 9] = (40f + rnd.nextFloat() * 120f) * (rnd.nextBoolean() ? 1f : -1f);
            data[o + 10] = 0.4f + rnd.nextFloat();
            data[o + 11] = rnd.nextFloat() * (float) TAU;
            data[o + 12] = rnd.nextInt(COLORS.length);
            data[o + 13] = rnd.nextInt(shapes.length);
        }
        playing = true;
        start = SystemClock.uptimeMillis();
        postDelayed(this, duration);
        postInvalidateOnAnimation();
    }

    @Override
    public void run() {
        if (!playing) return;
        playing = false;
        invalidate();
        Runnable done = onDone;
        onDone = null;
        if (done != null) done.run();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (playing) {
            removeCallbacks(this);
            run();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!playing) return;
        long now = SystemClock.uptimeMillis();
        float elapsed = (now - start) / (float) duration, secs = (now - start) / 1000f;
        float w = getWidth(), h = getHeight();
        for (int i = 0; i < count; i++) {
            int o = i * STRIDE;
            float p = (elapsed - data[o + 1]) / data[o + 2];
            if (p <= 0f || p >= 1f) continue;
            float len = data[o + 4] * density, fall = (p + p * p) * 0.5f;
            float y = -len * 1.5f + (h * data[o + 3] + len * 1.5f) * fall;
            double sway = TAU * data[o + 6] * secs + data[o + 7];
            float x = data[o] * w + data[o + 5] * density * (float) Math.sin(sway);
            float angle = data[o + 8] + data[o + 9] * secs + 22f * (float) Math.cos(sway);
            float flip = (float) Math.cos(TAU * data[o + 10] * secs + data[o + 11]);
            float sx = Math.abs(flip) < 0.08f ? (flip < 0f ? -0.08f : 0.08f) : flip;
            int a = p > 0.75f ? (int) (255 * (1f - (p - 0.75f) / 0.25f)) : 255;
            float shade = (flip < 0f ? 0.78f : 1f) * (0.8f + 0.2f * Math.abs(flip));
            int base = COLORS[(int) data[o + 12]], sh = (int) data[o + 13];

            c.save();
            c.translate(x, y);
            c.rotate(angle);
            c.scale(len * sx, len);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(scale(base, shade));
            paint.setAlpha(a);
            c.drawPath(shapes[sh], paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.045f);
            paint.setColor(scale(base, 0.55f));
            paint.setAlpha(a * 3 / 4);
            c.drawPath(shapes[sh], paint);
            paint.setStrokeWidth(0.035f);
            paint.setColor(EDGE);
            paint.setAlpha(a / 4);
            c.drawPath(veins[sh], paint);
            c.restore();
        }
        postInvalidateOnAnimation();
    }

    private static Path petalShape(Random r) {
        Path p = new Path();
        float wTop = 0.36f + 0.08f * r.nextFloat();
        p.moveTo(0f, 0.5f);
        p.cubicTo(-0.16f, 0.36f, -wTop - 0.04f, 0.02f, -wTop, -0.3f);
        int scallops = 4 + r.nextInt(2);
        for (int k = 0; k < scallops; k++) {
            float tx = -wTop + 2 * wTop * (k + 0.5f) / scallops, nx = -wTop + 2 * wTop * (k + 1f) / scallops;
            float tipY = -0.5f + 0.2f * (tx / wTop) * (tx / wTop) - 0.06f - 0.05f * r.nextFloat();
            float notchY = k == scallops - 1 ? -0.3f : -0.5f + 0.2f * (nx / wTop) * (nx / wTop) + 0.05f + 0.03f * r.nextFloat();
            p.quadTo(tx, tipY, nx, notchY);
        }
        p.cubicTo(wTop + 0.04f, 0.02f, 0.16f, 0.36f, 0f, 0.5f);
        p.close();
        return p;
    }

    private static Path veinPath(Random r) {
        Path p = new Path();
        float bend = (r.nextFloat() - 0.5f) * 0.06f;
        p.moveTo(0f, 0.42f);
        p.quadTo(bend, 0f, 0f, -0.36f);
        p.moveTo(0f, 0.3f);
        p.quadTo(-0.12f, -0.05f, -0.2f, -0.3f);
        p.moveTo(0f, 0.3f);
        p.quadTo(0.12f, -0.05f, 0.2f, -0.3f);
        return p;
    }

    private static int scale(int c, float f) {
        int r = Math.min(255, (int) (((c >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((c >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((c & 0xFF) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
