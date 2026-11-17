package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;

import java.util.Random;

/**
 * A marigold (genda phool) seen from the front: four rings of ruffled petals, deep saffron outside to golden
 * inside, around a dark centre.
 */
public final class MarigoldDrawable extends FestiveLamp {

    private static final float C = 12f, CORE_SPIN = 1.2f, CORE_R = 2.9f;
    private static final float LIGHT_DEG = 225f, SHADOW_DX = 0.35f, SHADOW_DY = 0.45f;
    private static final int[] COUNT = {13, 11, 9, 7};
    private static final float[] RADIUS = {11.6f, 9.4f, 7.2f, 5.0f}, SPIN = {1f, 1.04f, 1.09f, 1.15f};
    private static final int[][] SHADES = {
            {0xFFE5600A, 0xFFF0800E}, {0xFFF0800E, 0xFFF79A16}, {0xFFF9A31B, 0xFFFFB524}, {0xFFFFB524, 0xFFFFC63E}};
    private static final int LIT = 0xFFFFE9A6, SHADOWED = 0xFFA43E00, EDGE = 0xFF6E2400;
    private static final long TURN_MS = 900;

    private final Path[][] petals = new Path[COUNT.length][];
    private final float[][] angles = new float[COUNT.length][];
    private final int[][] colors = new int[COUNT.length][], edges = new int[COUNT.length][];
    private final float[] shadowR = new float[COUNT.length], seeds = new float[18];
    private final Shader[] shadowShaders = new Shader[COUNT.length];
    private final Shader coreShader;
    private final Path core = new Path();
    private final ValueAnimator animator;

    private double rotation, from, target;

    public MarigoldDrawable(float density) {
        super(density, Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeWidth(0.3f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        Random rnd = new Random(0x6E7DAL);
        for (int i = 0; i < COUNT.length; i++) buildRing(i, rnd);
        buildCore(rnd);

        for (int i = 0; i < COUNT.length; i++) {
            float inner = i + 1 < COUNT.length ? RADIUS[i + 1] : CORE_R;
            float sr = inner + 0.6f * (RADIUS[i] - inner), q = inner / sr;
            shadowR[i] = sr;
            shadowShaders[i] = new RadialGradient(C + SHADOW_DX, C + SHADOW_DY, sr,
                    new int[]{0x805A1E00, 0x805A1E00, 0x665A1E00, 0x005A1E00},
                    new float[]{0f, q * 0.9f, q, 1f}, Shader.TileMode.CLAMP);
        }
        coreShader = new RadialGradient(C - 0.7f, C - 0.7f, 3.6f,
                new int[]{0xFFE0821A, 0xFFB04E08, 0xFF7A3004}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(TURN_MS);
        animator.setInterpolator(new DecelerateInterpolator(1.6f));
        animator.addUpdateListener(this);
    }

    @Override
    public void onAnimationUpdate(@NonNull ValueAnimator a) {
        rotation = from + (target - from) * (float) a.getAnimatedValue();
        invalidateSelf();
    }

    public void turn() {
        if (reduced) return;
        from = rotation;
        target += 60.0;
        animator.cancel();
        animator.start();
    }

    @Override
    public void setReducedMotion(boolean reducedMotion) {
        reduced = reducedMotion;
        if (reduced && animator.isRunning()) animator.end();
    }

    private void buildRing(int ring, Random rnd) {
        int n = COUNT[ring];
        petals[ring] = new Path[n];
        angles[ring] = new float[n];
        colors[ring] = new int[n];
        edges[ring] = new int[n];
        float offset = ring * (180f / n) + (rnd.nextFloat() - 0.5f) * 8f;
        float edgeDepth = ring == 0 ? 0.55f : 0.45f;
        for (int j = 0; j < n; j++) {
            float deg = offset + j * 360f / n + (rnd.nextFloat() - 0.5f) * 10f;
            float r = RADIUS[ring] * (0.96f + 0.04f * rnd.nextFloat());
            float hw = (float) (Math.PI / n) * (1.18f + 0.15f * rnd.nextFloat());
            petals[ring][j] = petal((float) Math.toRadians(deg), hw, r * 0.25f, r, 3 + rnd.nextInt(2), rnd);
            angles[ring][j] = deg;
            int base = Sky.lerpColor(SHADES[ring][0], SHADES[ring][1], rnd.nextFloat());
            colors[ring][j] = base;
            edges[ring][j] = Sky.lerpColor(base, EDGE, edgeDepth);
        }
    }

    private static Path petal(float a, float hw, float r0, float r, int scallops, Random rnd) {
        Path p = new Path();
        float a0 = a - hw, a1 = a + hw;
        p.moveTo(px(r0, a - hw * 0.35f), py(r0, a - hw * 0.35f));
        float sideA = a0 - hw * 0.08f;
        p.quadTo(px(r * 0.62f, sideA), py(r * 0.62f, sideA), px(r * 0.86f, a0), py(r * 0.86f, a0));
        int steps = scallops * 2;
        for (int k = 1; k < steps; k += 2) {
            float ta = a0 + (a1 - a0) * k / steps + (rnd.nextFloat() - 0.5f) * hw * 0.12f;
            float na = a0 + (a1 - a0) * (k + 1) / steps;
            float tr = r * (1.0f + 0.06f * rnd.nextFloat());
            float nr = k + 1 >= steps ? r * 0.86f : r * (0.83f + 0.06f * rnd.nextFloat());
            p.quadTo(px(tr, ta), py(tr, ta), px(nr, na), py(nr, na));
        }
        float sideB = a1 + hw * 0.08f, endA = a + hw * 0.35f;
        p.quadTo(px(r * 0.62f, sideB), py(r * 0.62f, sideB), px(r0, endA), py(r0, endA));
        p.close();
        return p;
    }

    private void buildCore(Random rnd) {
        int bumps = 18;
        for (int k = 0; k <= bumps * 2; k++) {
            float a = (float) (Math.PI * k / bumps);
            float r = (k % 2 == 1) ? CORE_R * (1.0f + 0.06f * rnd.nextFloat()) : CORE_R * 0.84f;
            if (k == 0) core.moveTo(px(r, a), py(r, a));
            else if (k % 2 == 1) {
                float na = (float) (Math.PI * (k + 1) / bumps), nr = CORE_R * 0.84f;
                core.quadTo(px(r, a), py(r, a), px(nr, na), py(nr, na));
            }
        }
        core.close();
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int k = 0; k < seeds.length / 2; k++) {
            float r = 0.62f * (float) Math.sqrt(k + 0.5), a = (float) (k * golden);
            seeds[k * 2] = px(r, a);
            seeds[k * 2 + 1] = py(r, a);
        }
    }

    @Override
    public void draw(@NonNull Canvas c) {
        int save = beginBox(c);
        if (save < 0) return;

        for (int i = 0; i < COUNT.length; i++) {
            float rot = (float) ((rotation * SPIN[i]) % 360.0);
            c.save();
            c.rotate(rot, C, C);
            Path[] ring = petals[i];
            paint.setShader(null);
            for (int j = 0; j < ring.length; j++) {
                float l = (float) Math.cos(Math.toRadians(angles[i][j] + rot - LIGHT_DEG));
                int base = colors[i][j];
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(l >= 0f ? Sky.lerpColor(base, LIT, 0.3f * l) : Sky.lerpColor(base, SHADOWED, -0.24f * l));
                c.drawPath(ring[j], paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(edges[i][j]);
                c.drawPath(ring[j], paint);
            }
            c.restore();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(shadowShaders[i]);
            c.drawCircle(C + SHADOW_DX, C + SHADOW_DY, shadowR[i], paint);
        }

        c.save();
        c.rotate((float) ((rotation * CORE_SPIN) % 360.0), C, C);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(coreShader);
        c.drawPath(core, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xFF5C2403);
        c.drawPath(core, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFB84A);
        for (int k = 0; k < seeds.length; k += 2) c.drawCircle(seeds[k], seeds[k + 1], 0.26f, paint);
        c.restore();

        c.restoreToCount(save);
    }

    private static float px(float r, float a) { return C + r * (float) Math.cos(a); }
    private static float py(float r, float a) { return C + r * (float) Math.sin(a); }
}
