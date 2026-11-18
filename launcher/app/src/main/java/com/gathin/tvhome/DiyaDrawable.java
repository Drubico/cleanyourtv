package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.util.Random;

/**
 * A clay diya whose flame shows the network: the flame's height follows the signal level (1 to 4), it goes out
 * with a wisp of smoke when the level drops to 0, and relights with a spark when it comes back.
 */
public final class DiyaDrawable extends FestiveLamp {

    private static final float WICK_X = 18.5f, WICK_Y = 12.1f, FLAME_H = 10f, FLAME_W = 2f;
    private static final long LEVEL_MS = 300, OUT_MS = 350, SMOKE_DELAY_MS = 260, SMOKE_MS = 900;
    private static final long OUT_END_MS = SMOKE_DELAY_MS + SMOKE_MS, SPARK_MS = 200, GROW_DELAY_MS = 110, GROW_MS = 500;
    private static final int IDLE = 0, OUT = 1, RELIGHT = 2, SMOKE_POINTS = 16;
    private static final float[] LEVEL_SCALE = {0f, 0.46f, 0.64f, 0.82f, 1f};
    private static final float[] DOTS = {5.0f, 17.6f, 7.4f, 18.5f, 9.9f, 18.8f, 12.4f, 18.4f, 14.6f, 17.3f};

    private final float[] noise = new float[256];
    private final Path body = new Path(), opening = new Path(), rimLip = new Path(), sheen = new Path();
    private final Path flame = new Path(), core = new Path(), smoke = new Path();
    private final RectF foot = new RectF(7.2f, 20.0f, 12.4f, 22.4f), oval = new RectF();
    private final Matrix matrix = new Matrix();
    private final Shader bodyShader, warmShader, openingShader, glowShader, flameShader, coreShader, smokeShader, sparkShader;

    private int level = 4, phase = IDLE;
    private boolean primed;
    private float scaleFrom = 1f, scaleTo = 1f, outFrom;
    private long scaleStart, phaseStart;
    private final long epoch = SystemClock.uptimeMillis();

    public DiyaDrawable(float density) {
        super(density, Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        Random rnd = new Random(0xD1A5L);
        for (int i = 0; i < noise.length; i++) noise[i] = rnd.nextFloat() * 2f - 1f;
        buildLamp();

        bodyShader = new RadialGradient(7.5f, 14.5f, 13f,
                new int[]{0xFFE58C57, 0xFFC4612F, 0xFF9A4220, 0xFF6E2C14},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP);
        RadialGradient warm = new RadialGradient(0f, 0f, 1f,
                new int[]{0xA6FFB347, 0x40FF9A2A, 0x00FF8A1A}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        matrix.setScale(9f, 9f);
        matrix.postTranslate(WICK_X, WICK_Y);
        warm.setLocalMatrix(matrix);
        warmShader = warm;
        openingShader = new LinearGradient(2.6f, 0f, 19f, 0f,
                new int[]{0xFF3B170A, 0xFF5A2A10, 0xFF7A4416}, null, Shader.TileMode.CLAMP);
        glowShader = new RadialGradient(0f, 0f, 1f,
                new int[]{0x99FFC45A, 0x44FF9A2A, 0x00FF8A1A}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        flameShader = new LinearGradient(0f, 0f, 0f, 1f,
                new int[]{0xFFD9430B, 0xFFFF7A14, 0xFFFFA62B, 0xFFFFB83F, 0xFFF08A24},
                new float[]{0f, 0.22f, 0.55f, 0.85f, 1f}, Shader.TileMode.CLAMP);
        coreShader = new LinearGradient(0f, 0f, 0f, 1f,
                new int[]{0xFFFFD25A, 0xFFFFF0B8, 0xFFFFF8E1, 0xFFFFE9A6},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP);
        smokeShader = new LinearGradient(0f, 0f, 0f, 1f,
                new int[]{0x00A0A0A0, 0xB09A9A9A, 0xFF8E8E8E}, new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP);
        sparkShader = new RadialGradient(0f, 0f, 1f,
                new int[]{0xFFFFF4C8, 0xCCFFC24A, 0x00FF9A20}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    public void run() {
        update(SystemClock.uptimeMillis());
        super.run();
    }

    @Override
    public void setFlame(int newLevel) {
        int lvl = Math.max(0, Math.min(4, newLevel));
        if (!primed) {
            primed = true;
            level = lvl;
            onStopped();
            kick();
            return;
        }
        if (lvl == level) return;
        long now = SystemClock.uptimeMillis();
        update(now);
        float current = flameScale(now);
        int old = level;
        level = lvl;
        if (!running || reduced || !isVisible()) {
            onStopped();
        } else if (lvl == 0) {
            outFrom = current;
            phase = OUT;
            phaseStart = now;
        } else if (old == 0) {
            phase = RELIGHT;
            phaseStart = now;
            scaleFrom = scaleTo = LEVEL_SCALE[lvl];
            scaleStart = now;
        } else {
            scaleFrom = baseScale(now);
            scaleTo = LEVEL_SCALE[lvl];
            scaleStart = now;
        }
        kick();
    }

    @Override
    public void setReducedMotion(boolean reducedMotion) {
        if (reduced == reducedMotion) return;
        reduced = reducedMotion;
        if (reduced) onStopped();
        kick();
    }

    @Override boolean wantsFrames() { return running && isVisible() && ((level > 0 && !reduced) || phase != IDLE); }
    @Override void onStopped() { phase = IDLE; scaleFrom = scaleTo = LEVEL_SCALE[level]; }

    private void update(long now) {
        if (phase == OUT && now - phaseStart >= OUT_END_MS) phase = IDLE;
        else if (phase == RELIGHT && now - phaseStart >= GROW_DELAY_MS + GROW_MS) phase = IDLE;
    }

    private float baseScale(long now) {
        float p = clamp01((now - scaleStart) / (float) LEVEL_MS);
        return scaleFrom + (scaleTo - scaleFrom) * decel(p);
    }

    private float flameScale(long now) {
        if (phase == OUT) {
            float e = (now - phaseStart) / (float) OUT_MS;
            return e >= 1f ? 0f : outFrom * (1f - e * e);
        }
        if (phase == RELIGHT) {
            float g = (now - phaseStart - GROW_DELAY_MS) / (float) GROW_MS;
            return g <= 0f ? 0f : baseScale(now) * backOut(Math.min(1f, g));
        }
        return level == 0 ? 0f : baseScale(now);
    }

    private float flameBright(long now) {
        if (phase != OUT) return 1f;
        float e = clamp01((now - phaseStart) / (float) OUT_MS);
        return 1f - e * (float) Math.sqrt(e);
    }

    private Paint fill(int color, Shader s, int a) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(s);
        paint.setColor(color);
        paint.setAlpha(a);
        return paint;
    }

    private Paint stroke(int color, float width, Shader s, int a) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setShader(s);
        paint.setColor(color);
        paint.setAlpha(a);
        return paint;
    }

    @Override
    public void draw(@NonNull Canvas c) {
        int save = beginBox(c);
        if (save < 0) return;
        long now = SystemClock.uptimeMillis();
        update(now);

        float scale = flameScale(now), bright = flameBright(now);
        float hMul = 1f, wMul = 1f, sway = 0f;
        if (running && !reduced && isVisible()) {
            float t = (now - epoch) / 1000f;
            if (steady) {
                float breath = sin(t / 4.2f);
                hMul = 1f + 0.035f * breath;
                wMul = 1f + 0.02f * breath;
            } else {
                hMul = 1f + 0.055f * sin(0.83f * t) + 0.035f * sin(1.71f * t + 0.21f)
                        + 0.025f * sin(2.63f * t + 0.33f) + 0.03f * noise(t * 1.9f);
                wMul = 1f + 0.045f * sin(1.37f * t + 0.14f) + 0.03f * noise(t * 2.2f + 31f);
                sway = 0.45f * sin(0.47f * t + 0.1f) + 0.28f * sin(1.13f * t + 0.38f) + 0.2f * noise(t * 1.3f + 17f);
            }
        }
        float h = FLAME_H * scale * hMul;
        float w = FLAME_W * Math.min(1f, scale * 2.2f) * (0.5f + 0.5f * scale) * wMul;
        float lean = sway * scale, vis = scale * bright;

        if (vis > 0.01f) {
            float cx = WICK_X + lean * 0.4f, cy = WICK_Y - h * 0.45f, rx = 2.6f + 3.4f * scale, ry = rx * 1.2f;
            matrix.setScale(rx, ry);
            matrix.postTranslate(cx, cy);
            glowShader.setLocalMatrix(matrix);
            oval.set(cx - rx, cy - ry, cx + rx, cy + ry);
            c.drawOval(oval, fill(0xFFFFB040, glowShader, (int) (255 * bright * (0.5f + 0.5f * scale))));
        }

        c.drawOval(foot, fill(0xFF8E3E1E, null, 255));
        c.drawOval(foot, stroke(0xFF3D1408, 0.7f, null, 255));
        c.drawPath(body, fill(0xFFC4612F, bodyShader, 255));
        if (vis > 0.01f) c.drawPath(body, fill(0xFFFFB347, warmShader, (int) (190 * vis)));
        c.drawPath(opening, fill(0xFF4A1E0E, openingShader, 255));
        if (vis > 0.01f) c.drawPath(opening, fill(0xFFFFB347, warmShader, (int) (255 * vis)));
        c.drawPath(opening, stroke(0xFF4A1A0A, 0.3f, null, 255));
        fill(0xFFFFE0A3, null, 255);
        for (int i = 0; i < DOTS.length; i += 2) c.drawCircle(DOTS[i], DOTS[i + 1], 0.42f, paint);
        c.drawPath(rimLip, stroke(0xFFEE9A68, 0.6f, null, 255));
        c.drawPath(sheen, stroke(0x73FFE8D2, 0.75f, null, 0x73));
        c.drawPath(body, stroke(0xFF3D1408, 0.7f, null, 255));
        c.drawLine(WICK_X - 0.9f, WICK_Y + 0.7f, WICK_X, WICK_Y, stroke(0xFF2B140A, 0.6f, null, 255));

        if (phase == OUT) drawSmoke(c, now);
        if (phase == RELIGHT) drawSpark(c, now);

        if (h > 0.15f && bright > 0.01f) {
            float by = WICK_Y + 0.2f;
            teardrop(flame, WICK_X, by, h, w, lean);
            matrix.setScale(1f, h);
            matrix.postTranslate(0f, by - h);
            flameShader.setLocalMatrix(matrix);
            c.drawPath(flame, fill(0xFFFF9A2A, flameShader, (int) (255 * bright)));
            c.drawPath(flame, stroke(0xFFB8400A, 0.28f, null, (int) (150 * bright)));

            float cby = WICK_Y - h * 0.06f, ch = h * 0.58f, cw = w * 0.5f;
            teardrop(core, WICK_X + lean * 0.05f, cby, ch, cw, lean * 0.55f);
            matrix.setScale(1f, ch);
            matrix.postTranslate(0f, cby - ch);
            coreShader.setLocalMatrix(matrix);
            c.drawPath(core, fill(0xFFFFF0B8, coreShader, (int) (255 * bright * bright)));
        }
        c.restoreToCount(save);
    }

    private void drawSmoke(Canvas c, long now) {
        long since = now - phaseStart;
        float ember = 1f - since / (float) (SMOKE_DELAY_MS + SMOKE_MS * 0.6f);
        if (ember > 0f) c.drawCircle(WICK_X, WICK_Y, 0.5f, fill(0xFFFF5A1A, null, (int) (255 * ember)));
        if (reduced) return;
        float sp = (since - SMOKE_DELAY_MS) / (float) SMOKE_MS;
        if (sp <= 0f || sp >= 1f) return;
        float head = 10.5f * decel(sp), tp = (sp - 0.3f) / 0.7f;
        float tail = tp <= 0f ? 0f : Math.min(head - 0.6f, 8.5f * tp * tp);
        if (head - tail < 0.3f) return;
        smoke.reset();
        for (int i = 0; i < SMOKE_POINTS; i++) {
            float d = tail + (head - tail) * i / (SMOKE_POINTS - 1);
            float curl = (0.2f + 0.17f * d) * (float) Math.sin(d * 0.9f - sp * 5.5f);
            float x = WICK_X - 0.28f * d + curl, y = WICK_Y - 0.3f - d;
            if (i == 0) smoke.moveTo(x, y);
            else smoke.lineTo(x, y);
        }
        matrix.setScale(1f, head - tail);
        matrix.postTranslate(0f, WICK_Y - 0.3f - head);
        smokeShader.setLocalMatrix(matrix);
        c.drawPath(smoke, stroke(0xFFA0A0A0, 0.55f + 0.35f * sp, smokeShader, (int) (230 * (1f - sp))));
    }

    private void drawSpark(Canvas c, long now) {
        float k = (now - phaseStart) / (float) SPARK_MS;
        if (k >= 1.5f) return;
        float r = 0.5f + 2.8f * decel(Math.min(1f, k));
        float a = k < 0.25f ? k / 0.25f : 1f - (k - 0.25f) / 1.25f;
        float cx = WICK_X, cy = WICK_Y - 0.5f;
        matrix.setScale(r, r);
        matrix.postTranslate(cx, cy);
        sparkShader.setLocalMatrix(matrix);
        c.drawCircle(cx, cy, r, fill(0xFFFFF4C8, sparkShader, (int) (255 * a)));
        stroke(0xFFF2A12E, 0.35f, null, (int) (255 * a));
        for (int i = 0; i < 4; i++) {
            double ang = Math.toRadians(45 + 90 * i + 30 * k);
            float cs = (float) Math.cos(ang), sn = (float) Math.sin(ang);
            c.drawLine(cx + cs * r * 0.55f, cy + sn * r * 0.55f, cx + cs * r * 1.15f, cy + sn * r * 1.15f, paint);
        }
    }

    private static void teardrop(Path p, float bx, float by, float h, float w, float lean) {
        float tx = bx + lean, ty = by - h, ml = lean * 0.35f;
        float wy = by - Math.max(w * 1.1f, h * 0.24f);
        float up = (wy - ty) * 0.35f, down = (by - wy) * 0.55f;
        p.reset();
        p.moveTo(tx, ty);
        p.cubicTo(tx + w * 0.25f, ty + h * 0.3f, bx + ml + w, wy - up, bx + w, wy);
        p.cubicTo(bx + w, wy + down, bx + w * 0.55f, by, bx, by);
        p.cubicTo(bx - w * 0.55f, by, bx - w, wy + down, bx - w, wy);
        p.cubicTo(bx + ml - w, wy - up, tx - w * 0.25f, ty + h * 0.3f, tx, ty);
        p.close();
    }

    private void buildLamp() {
        cubics(body, false, 1.6f, 14.2f, 1.8f, 18.6f, 5.4f, 21.0f, 9.6f, 21.0f,
                13.2f, 21.0f, 15.6f, 19.4f, 16.8f, 16.6f, 17.6f, 15.0f, 18.6f, 13.6f, 20.2f, 12.4f);
        body.quadTo(20.5f, 12.0f, 19.9f, 11.9f);
        body.cubicTo(18.2f, 12.1f, 16.8f, 12.6f, 15.4f, 13.0f);
        body.cubicTo(12.0f, 11.6f, 5.0f, 11.8f, 1.6f, 14.2f);
        body.close();
        cubics(opening, true, 2.6f, 14.2f, 5.6f, 12.6f, 11.8f, 12.5f, 15.2f, 13.4f,
                16.6f, 13.0f, 17.8f, 12.7f, 19.2f, 12.4f, 17.6f, 13.4f, 16.6f, 14.0f, 15.4f, 14.6f,
                11.6f, 16.4f, 5.0f, 16.2f, 2.6f, 14.2f);
        cubics(rimLip, false, 2.7f, 14.65f, 5.1f, 16.65f, 11.6f, 16.85f, 15.5f, 15.0f,
                16.7f, 14.4f, 17.7f, 13.8f, 19.4f, 12.75f);
        sheen.moveTo(3.2f, 15.4f);
        sheen.quadTo(4.0f, 18.4f, 6.9f, 19.7f);
    }

    private float noise(float x) {
        int i = (int) Math.floor(x);
        float f = x - i, s = f * f * (3f - 2f * f);
        return noise[i & 255] + (noise[(i + 1) & 255] - noise[i & 255]) * s;
    }

    private static float sin(float turns) { return (float) Math.sin(turns * 2.0 * Math.PI); }
    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
    private static float decel(float p) { float q = 1f - p; return 1f - q * q; }
    private static float backOut(float x) { float s = 1.4f, q = x - 1f; return 1f + (s + 1f) * q * q * q + s * q * q; }
}
