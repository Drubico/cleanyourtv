package com.gathin.tvhome;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.util.Random;

/**
 * A carved jack-o'-lantern for the network pill while the Halloween skin is on.
 */
public final class JackOLanternDrawable extends FestiveLamp {

    private static final long FADE_MS = 350;
    private static final float[] LEVEL_GLOW = {0f, 0.55f, 0.72f, 0.88f, 1f};

    private final Bitmap dark, lit;
    private final float[] noise = new float[64];
    private final long epoch = SystemClock.uptimeMillis();

    private int level = 4;
    private boolean primed;
    private float glowFrom = 1f, glowTo = 1f;
    private long fadeStart;

    public JackOLanternDrawable(Resources res, float density) {
        super(density, Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        dark = BitmapFactory.decodeResource(res, R.drawable.halloween_pumpkin_dark);
        lit = BitmapFactory.decodeResource(res, R.drawable.halloween_pumpkin_lit);
        Random rnd = new Random(0xC731L);
        for (int i = 0; i < noise.length; i++) noise[i] = rnd.nextFloat();
    }

    @Override
    public void setFlame(int newLevel) {
        int lvl = Math.max(0, Math.min(4, newLevel));
        long now = SystemClock.uptimeMillis();
        if (!primed || reduced) {
            primed = true;
            level = lvl;
            glowFrom = glowTo = LEVEL_GLOW[lvl];
            kick();
            return;
        }
        if (lvl == level) return;
        glowFrom = glow(now);
        glowTo = LEVEL_GLOW[lvl];
        fadeStart = now;
        level = lvl;
        kick();
    }

    @Override
    public void setReducedMotion(boolean r) {
        if (reduced == r) return;
        reduced = r;
        if (reduced) glowFrom = glowTo;
        kick();
    }

    @Override void onStopped() { glowFrom = glowTo; }

    @Override
    boolean wantsFrames() {
        return running && isVisible() && !reduced
                && (level > 0 || (glowFrom != glowTo && SystemClock.uptimeMillis() - fadeStart < FADE_MS));
    }

    private float glow(long now) {
        float p = Math.min(1f, Math.max(0f, (now - fadeStart) / (float) FADE_MS));
        return glowFrom + (glowTo - glowFrom) * (1f - (1f - p) * (1f - p));
    }

    private float flicker(long now) {
        if (!running || reduced || level == 0) return 1f;
        float t = (now - epoch) / 1000f;
        if (steady) return 0.97f + 0.03f * (float) Math.sin(t * 1.6f);
        float x = t * 7f;
        int i = (int) x;
        float f = x - i, s = f * f * (3f - 2f * f);
        return 0.84f + 0.16f * (noise[i & 63] + (noise[(i + 1) & 63] - noise[i & 63]) * s);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        paint.setAlpha(alpha);
        canvas.drawBitmap(dark, null, getBounds(), paint);
        float g = glow(now) * flicker(now);
        if (g <= 0.01f) return;
        paint.setAlpha(Math.round(alpha * Math.min(1f, g)));
        canvas.drawBitmap(lit, null, getBounds(), paint);
    }
}
