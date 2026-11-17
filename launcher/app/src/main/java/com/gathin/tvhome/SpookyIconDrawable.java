package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;

/**
 * A Halloween pill icon from a picture: a bat for Inputs or a ghost for Settings.
 */
public final class SpookyIconDrawable extends FestiveLamp {

    static final int BAT = 0, GHOST = 1;
    private static final long BAT_MS = 520, GHOST_MS = 900;

    private final int kind;
    private final Bitmap bitmap;
    private final Rect dst = new Rect();
    private final ValueAnimator animator;
    private float t = -1f;

    public SpookyIconDrawable(Resources res, float density, int kind) {
        super(density, Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        this.kind = kind;
        bitmap = BitmapFactory.decodeResource(res, kind == BAT ? R.drawable.halloween_bat : R.drawable.halloween_ghost);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(kind == BAT ? BAT_MS : GHOST_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(this);
        animator.addListener(this);
    }

    @Override public void onAnimationUpdate(@NonNull ValueAnimator a) { t = (float) a.getAnimatedValue(); invalidateSelf(); }
    @Override public void onAnimationEnd(@NonNull Animator a) { t = -1f; invalidateSelf(); }

    public void nudge() {
        if (reduced) return;
        animator.cancel();
        animator.start();
    }

    @Override
    public void setReducedMotion(boolean reducedMotion) {
        reduced = reducedMotion;
        if (reduced && animator.isRunning()) animator.cancel();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        float sx = 1f, sy = 1f, dy = 0f, rot = 0f;
        if (t >= 0f) {
            if (kind == BAT) {
                float beat = (float) Math.sin(t * 2 * Math.PI * 2);
                sy = 1f - 0.28f * Math.abs(beat);
                sx = 1f + 0.06f * Math.abs(beat);
                dy = -1.5f * density * (float) Math.sin(t * Math.PI);
            } else {
                dy = -3f * density * (float) Math.sin(t * Math.PI);
                rot = 6f * (float) Math.sin(t * 2 * Math.PI) * (1f - t);
            }
        }
        int save = canvas.save();
        canvas.translate(b.exactCenterX(), b.exactCenterY() + dy);
        canvas.rotate(rot);
        canvas.scale(sx, sy);
        int hw = b.width() / 2, hh = b.height() / 2;
        dst.set(-hw, -hh, b.width() - hw, b.height() - hh);
        paint.setAlpha(alpha);
        canvas.drawBitmap(bitmap, null, dst, paint);
        canvas.restoreToCount(save);
    }
}
