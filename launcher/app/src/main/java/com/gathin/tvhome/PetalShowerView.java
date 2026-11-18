package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.util.Random;

/**
 * Full-screen festive overlay: plays a one-off shower of marigold petals or a colony of Halloween bats.
 */
public final class PetalShowerView extends View implements Runnable {

    private static final int[] COLORS = {0xFFFF8C1A, 0xFFFFB627, 0xFFF26A0D, 0xFFFFC93C, 0xFFE85D04};
    private static final int EDGE = 0xFF7A2800;
    private static final double TAU = 2 * Math.PI;

    private final boolean bats;
    private final float density;
    private final Random rnd = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Bitmap bat;
    private final RectF dst;
    private final Path[] shapes, veins;

    private int count;
    private float[] data = new float[0];
    private boolean playing;
    private long start, duration;

    public PetalShowerView(Context context) {
        this(context, false);
    }

    public PetalShowerView(Context context, boolean bats) {
        super(context);
        this.bats = bats;
        density = context.getResources().getDisplayMetrics().density;
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
        if (bats) {
            bat = BitmapFactory.decodeResource(context.getResources(), R.drawable.halloween_bat);
            dst = new RectF();
            shapes = null;
            veins = null;
        } else {
            bat = null;
            dst = null;
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            shapes = new Path[3];
            veins = new Path[3];
            Random seeded = new Random(0x9E7A1L);
            for (int i = 0; i < shapes.length; i++) {
                shapes[i] = petalShape(seeded);
                veins[i] = veinPath(seeded);
            }
        }
    }

    public void play(int items, long durationMs) {
        removeCallbacks(this);
        int n = Math.max(0, items), stride = bats ? 8 : 14;
        if (data.length < n * stride) data = new float[n * stride];
        count = n;
        duration = Math.max(1, durationMs);
        for (int i = 0; i < n; i++) {
            int o = i * stride;
            data[o] = (i + rnd.nextFloat()) / n;
            if (bats) {
                data[o + 1] = (rnd.nextFloat() - 0.5f) * 0.5f;
                data[o + 2] = rnd.nextFloat() * 0.35f;
                data[o + 3] = 0.55f + rnd.nextFloat() * (1f - data[o + 2] - 0.55f);
                data[o + 4] = 26f + rnd.nextFloat() * 30f;
                data[o + 5] = 5f + rnd.nextFloat() * 3f;
                data[o + 6] = rnd.nextFloat() * (float) TAU;
                data[o + 7] = 8f + rnd.nextFloat() * 18f;
            } else {
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
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(this);
        playing = false;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!playing) return;
        long now = SystemClock.uptimeMillis();
        float elapsed = (now - start) / (float) duration, secs = (now - start) / 1000f;
        float w = getWidth(), h = getHeight();
        int stride = bats ? 8 : 14;
        for (int i = 0; i < count; i++) {
            int o = i * stride;
            if (bats) {
                float p = (elapsed - data[o + 2]) / data[o + 3];
                if (p <= 0f || p >= 1f) continue;
                float s = data[o + 4] * density, rise = p * (0.6f + 0.4f * p);
                float y = h + s - (h + 2.5f * s) * rise;
                float x = (data[o] + data[o + 1] * p) * w + data[o + 7] * density * (float) Math.sin(TAU * 0.8 * secs + data[o + 6]);
                float sy = 0.62f + 0.38f * Math.abs((float) Math.sin(TAU * data[o + 5] * secs + data[o + 6]));
                int a = p < 0.12f ? (int) (255 * p / 0.12f) : p > 0.8f ? (int) (255 * (1f - (p - 0.8f) / 0.2f)) : 255;
                paint.setAlpha(a);
                c.save();
                c.translate(x, y);
                c.scale(data[o + 1] < 0f ? -1f : 1f, sy);
                dst.set(-s / 2f, -s / 2f, s / 2f, s / 2f);
                c.drawBitmap(bat, null, dst, paint);
                c.restore();
                continue;
            }
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
            paint.setColor(Sky.lerpColor(0xFF000000, base, shade));
            paint.setAlpha(a);
            c.drawPath(shapes[sh], paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.045f);
            paint.setColor(Sky.lerpColor(0xFF000000, base, 0.55f));
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
}
