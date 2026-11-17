package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * The Halloween skin's greeting for the first Home of the day: a colony of bats rising from the bottom.
 */
public final class BatFlightView extends View implements Runnable {

    private static final double TAU = 2 * Math.PI;
    private static final int STRIDE = 8;

    private final float density;
    private final Bitmap bat;
    private final Random rnd = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();

    private int count;
    private float[] data = new float[0];
    private boolean playing;
    private long start, duration;
    private Runnable onDone;

    public BatFlightView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        bat = BitmapFactory.decodeResource(context.getResources(), R.drawable.halloween_bat);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
    }

    public void play(int bats, long durationMs, Runnable done) {
        if (playing) {
            removeCallbacks(this);
            run();
        }
        int n = Math.max(0, bats);
        if (data.length < n * STRIDE) data = new float[n * STRIDE];
        count = n;
        duration = Math.max(1, durationMs);
        onDone = done;
        for (int i = 0; i < n; i++) {
            int o = i * STRIDE;
            data[o] = (i + rnd.nextFloat()) / n;
            data[o + 1] = (rnd.nextFloat() - 0.5f) * 0.5f;
            data[o + 2] = rnd.nextFloat() * 0.35f;
            data[o + 3] = 0.55f + rnd.nextFloat() * (1f - data[o + 2] - 0.55f);
            data[o + 4] = 26f + rnd.nextFloat() * 30f;
            data[o + 5] = 5f + rnd.nextFloat() * 3f;
            data[o + 6] = rnd.nextFloat() * (float) TAU;
            data[o + 7] = 8f + rnd.nextFloat() * 18f;
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
        }
        postInvalidateOnAnimation();
    }
}
