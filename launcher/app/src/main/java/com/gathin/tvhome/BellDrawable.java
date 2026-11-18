package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;

/**
 * A brass temple bell (ghanta): a loop and knob on top, a domed body with two engraved bands and a flared lip,
 * and the clapper hanging below.
 */
public final class BellDrawable extends FestiveLamp {

    private static final float PX = 12f, PY = 1.3f, AMPLITUDE = 16f;
    private static final float OMEGA = (float) (2 * Math.PI * 3.4), TAU = 0.3f, LAG = 0.055f, CLAPPER_MAX = 7f;
    private static final long RING_MS = 1000;

    private final Path body = new Path(), lip = new Path(), lipTop = new Path();
    private final Path band1 = new Path(), band2 = new Path(), sheen = new Path();
    private final RectF crown = new RectF(10.3f, 3.1f, 13.7f, 5.5f);
    private final Shader bodyShader, lipShader, crownShader, ballShader;
    private final ValueAnimator animator;

    private float angle, clapperAngle, startAngle, amplitude;
    private int direction = -1;

    public BellDrawable(float density) {
        super(density, Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        buildPaths();

        int[] brass = {0xFF6B430E, 0xFFB98424, 0xFFF0CF72, 0xFFD6A13A, 0xFF9C6A1A, 0xFF5C380A};
        float[] stops = {0f, 0.16f, 0.33f, 0.52f, 0.8f, 1f};
        bodyShader = new LinearGradient(5f, 0f, 19f, 0f, brass, stops, Shader.TileMode.CLAMP);
        lipShader = new LinearGradient(4.8f, 0f, 19.2f, 0f,
                new int[]{0xFF7A4E12, 0xFFD9A43A, 0xFFFBE08A, 0xFFE3B04A, 0xFFA87420, 0xFF60400E},
                stops, Shader.TileMode.CLAMP);
        crownShader = new LinearGradient(crown.left, 0f, crown.right, 0f, brass, stops, Shader.TileMode.CLAMP);
        ballShader = new RadialGradient(11.6f, 20.7f, 1.7f,
                new int[]{0xFFF2D27A, 0xFFB07E22, 0xFF5A3A0A}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(RING_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(this);
        animator.addListener(this);
    }

    @Override
    public void onAnimationUpdate(@NonNull ValueAnimator a) {
        float t = (float) a.getAnimatedValue() * RING_MS / 1000f;
        angle = swing(t);
        float lagged = swing(Math.max(0f, t - LAG));
        clapperAngle = Math.max(angle - CLAPPER_MAX, Math.min(angle + CLAPPER_MAX, lagged));
        invalidateSelf();
    }

    @Override public void onAnimationEnd(@NonNull Animator a) { angle = clapperAngle = 0f; invalidateSelf(); }

    public void ring() {
        if (reduced) return;
        startAngle = angle;
        direction = -direction;
        amplitude = AMPLITUDE * direction;
        animator.cancel();
        animator.start();
    }

    @Override
    public void setReducedMotion(boolean reducedMotion) {
        reduced = reducedMotion;
        if (reduced && animator.isRunning()) animator.cancel();
    }

    private float swing(float t) {
        float wt = OMEGA * t;
        float a = (float) Math.exp(-t / TAU) * (startAngle * (float) Math.cos(wt) + amplitude * (float) Math.sin(wt));
        if (t > 0.85f) {
            float f = Math.min(1f, (t - 0.85f) / 0.15f);
            a *= 1f - f * f * (3f - 2f * f);
        }
        return Math.max(-AMPLITUDE, Math.min(AMPLITUDE, a));
    }

    private void buildPaths() {
        cubics(body, false, 9.8f, 5.0f, 7.6f, 5.3f, 7.0f, 7.8f, 7.0f, 10.6f,
                7.0f, 13.6f, 6.6f, 15.6f, 5.3f, 16.9f);
        body.quadTo(5.0f, 17.5f, 5.5f, 18.0f);
        body.cubicTo(8.6f, 18.9f, 15.4f, 18.9f, 18.5f, 18.0f);
        body.quadTo(19.0f, 17.5f, 18.7f, 16.9f);
        body.cubicTo(17.4f, 15.6f, 17.0f, 13.6f, 17.0f, 10.6f);
        body.cubicTo(17.0f, 7.8f, 16.4f, 5.3f, 14.2f, 5.0f);
        body.quadTo(12.0f, 4.6f, 9.8f, 5.0f);
        body.close();

        lip.moveTo(5.3f, 16.9f);
        lip.quadTo(5.0f, 17.5f, 5.5f, 18.0f);
        lip.cubicTo(8.6f, 18.9f, 15.4f, 18.9f, 18.5f, 18.0f);
        lip.quadTo(19.0f, 17.5f, 18.7f, 16.9f);
        lip.cubicTo(15.4f, 17.7f, 8.6f, 17.7f, 5.3f, 16.9f);
        lip.close();
        cubics(lipTop, false, 5.3f, 16.9f, 8.6f, 17.7f, 15.4f, 17.7f, 18.7f, 16.9f);
        cubics(band1, false, 7.15f, 9.0f, 9.5f, 9.7f, 14.5f, 9.7f, 16.85f, 9.0f);
        cubics(band2, false, 6.8f, 14.9f, 9.2f, 15.7f, 14.8f, 15.7f, 17.2f, 14.9f);
        cubics(sheen, false, 9.2f, 6.2f, 8.3f, 7.8f, 8.2f, 11.4f, 8.0f, 15.0f);
    }

    private Paint fill(Shader s) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(s);
        paint.setColor(0xFFFFFFFF);
        return paint;
    }

    private Paint stroke(int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setShader(null);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        return paint;
    }

    @Override
    public void draw(@NonNull Canvas c) {
        int save = beginBox(c);
        if (save < 0) return;

        c.save();
        c.rotate(clapperAngle, PX, PY);
        c.drawLine(12f, 8f, 12f, 20.1f, stroke(0xFF4E3208, 0.75f));
        c.drawCircle(12f, 21.15f, 1.15f, fill(ballShader));
        c.drawCircle(12f, 21.15f, 1.15f, stroke(0xFF3E2604, 0.4f));
        c.restore();

        c.save();
        c.rotate(angle, PX, PY);
        c.drawCircle(12f, 2.35f, 1.0f, stroke(0xFF452A05, 1.15f));
        c.drawCircle(12f, 2.35f, 1.0f, stroke(0xFFD6A13A, 0.55f));
        c.drawRoundRect(crown, 1.2f, 1.2f, fill(crownShader));
        c.drawRoundRect(crown, 1.2f, 1.2f, stroke(0xFF452A05, 0.55f));
        c.drawPath(body, fill(bodyShader));
        drawBand(c, band1);
        drawBand(c, band2);
        c.drawPath(lip, fill(lipShader));
        c.drawPath(lipTop, stroke(0xFF5A3608, 0.35f));
        float shift = -angle * 0.035f;
        c.translate(shift, 0f);
        c.drawPath(sheen, stroke(0xB3FFF4D2, 0.9f));
        c.drawLine(7.0f, 17.55f, 8.6f, 17.95f, paint);
        c.translate(-shift, 0f);
        c.drawPath(body, stroke(0xFF452A05, 0.55f));
        c.restore();

        c.restoreToCount(save);
    }

    private void drawBand(Canvas c, Path band) {
        c.drawPath(band, stroke(0x995A3608, 0.38f));
        c.translate(0f, 0.38f);
        c.drawPath(band, stroke(0x66FFF2C0, 0.25f));
        c.translate(0f, -0.38f);
    }
}
