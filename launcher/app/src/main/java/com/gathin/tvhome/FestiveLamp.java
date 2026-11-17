package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Shared 24dp icon drawable base for festive lamps (diya, jack-o'-lantern) and pill icons (bell, marigold, bat, ghost).
 */
public abstract class FestiveLamp extends Drawable
        implements Animatable, Runnable, ValueAnimator.AnimatorUpdateListener, Animator.AnimatorListener {

    static final float BOX = 24f;
    static final long FRAME_MS = 66;

    final float density;
    final Paint paint;
    int alpha = 255;
    boolean running, steady, reduced;

    FestiveLamp(float density, int paintFlags) {
        this.density = density;
        paint = new Paint(paintFlags);
    }

    int beginBox(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return -1;
        float s = Math.min(b.width(), b.height()) / BOX;
        int save = alpha < 255 ? c.saveLayerAlpha(b.left, b.top, b.right, b.bottom, alpha) : c.save();
        c.clipRect(b);
        c.translate(b.exactCenterX() - 12f * s, b.exactCenterY() - 12f * s);
        c.scale(s, s);
        return save;
    }

    void kick() {
        unscheduleSelf(this);
        if (wantsFrames()) scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_MS);
        invalidateSelf();
    }

    boolean wantsFrames() { return false; }

    public void setFlame(int level) {}

    public void setSteady(boolean s) {
        if (steady != s) {
            steady = s;
            invalidateSelf();
        }
    }

    public void setReducedMotion(boolean r) {
        reduced = r;
    }

    void onStopped() {}

    @Override public void start() { if (!running) { running = true; kick(); } }
    @Override public void stop() { if (running) { running = false; onStopped(); kick(); } }
    @Override public boolean isRunning() { return running; }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (changed) {
            if (!visible) onStopped();
            kick();
        }
        return changed;
    }

    @Override
    public void run() {
        invalidateSelf();
        if (wantsFrames()) scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_MS);
    }

    @Override public void onAnimationUpdate(@NonNull ValueAnimator a) {}
    @Override public void onAnimationEnd(@NonNull Animator a) {}
    @Override public void onAnimationStart(@NonNull Animator a) {}
    @Override public void onAnimationCancel(@NonNull Animator a) {}
    @Override public void onAnimationRepeat(@NonNull Animator a) {}

    @Override public int getIntrinsicWidth() { return Math.round(BOX * density); }
    @Override public int getIntrinsicHeight() { return Math.round(BOX * density); }

    @Override
    public void setAlpha(int alpha) {
        if (this.alpha != alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }
    }

    @Override public int getAlpha() { return alpha; }

    @Override
    public void setColorFilter(@Nullable ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
