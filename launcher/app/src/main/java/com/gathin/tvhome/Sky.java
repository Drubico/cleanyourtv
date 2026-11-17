package com.gathin.tvhome;

/**
 * Time-of-day sky palette for the optional "sunset" backdrop (config.xml, sky_intensity).
 *
 * Pure maths, no Android types and no clock of its own: the caller passes minutes since midnight,
 * so the palette can be tested on the JVM.
 */
final class Sky {

    /** The palette at one instant: the three gradient colours plus the glow. */
    static final class Palette {
        final int top, mid, bottom, sun;
        final float sunX;

        Palette(int top, int mid, int bottom, int sun, float sunX) {
            this.top = top;
            this.mid = mid;
            this.bottom = bottom;
            this.sun = sun;
            this.sunX = sunX;
        }
    }

    private static final int NIGHT_TOP = 0xFF04060E;
    private static final int NIGHT_MID = 0xFF070C1A;
    private static final int NIGHT_BOTTOM = 0xFF0B1326;

    static final int REF_SUNRISE = 450;
    static final int REF_SUNSET = 1140;
    /** Packed anchors: minute, top, mid, bottom, sun (0..255), sunXPercent (0..100). */
    private static final int[] STOPS = {
            0, NIGHT_TOP, NIGHT_MID, NIGHT_BOTTOM, 0, 30,
            320, NIGHT_TOP, NIGHT_MID, NIGHT_BOTTOM, 0, 30,
            390, 0xFF160F2E, 0xFF3B2352, 0xFF8A4A6B, 100, 24,
            450, 0xFF12203F, 0xFF5C3A63, 0xFFD3674F, 160, 30,
            570, 0xFF0E1A33, 0xFF1F3557, 0xFF43648B, 90, 38,
            780, 0xFF0B1524, 0xFF17304F, 0xFF31587F, 60, 50,
            1020, 0xFF0D1526, 0xFF20395C, 0xFF4C5470, 90, 62,
            1140, 0xFF1B1030, 0xFF6C2E52, 0xFFE2704A, 190, 76,
            1185, 0xFF140C27, 0xFF4A2049, 0xFFA54A5C, 130, 82,
            1260, 0xFF0A0A1C, 0xFF191230, 0xFF3A1F49, 60, 86,
            1350, NIGHT_TOP, NIGHT_MID, NIGHT_BOTTOM, 0, 30,
            1440, NIGHT_TOP, NIGHT_MID, NIGHT_BOTTOM, 0, 30,
    };

    private Sky() {
    }

    static Palette at(int minuteOfDay, int sunrise, int sunset) {
        if (sunrise <= 0 || sunset >= 1440 || sunrise >= sunset) return at(minuteOfDay);
        int m = Math.max(0, Math.min(1440, minuteOfDay));
        float ref;
        if (m < sunrise) ref = m * REF_SUNRISE / (float) sunrise;
        else if (m < sunset) ref = REF_SUNRISE + (m - sunrise) * (REF_SUNSET - REF_SUNRISE) / (float) (sunset - sunrise);
        else ref = REF_SUNSET + (m - sunset) * (1440 - REF_SUNSET) / (float) (1440 - sunset);
        return at(Math.round(ref));
    }

    static Palette at(int minuteOfDay) {
        int m = Math.max(0, Math.min(1440, minuteOfDay));
        int a = 0, b = STOPS.length - 6;
        for (int i = 0; i <= STOPS.length - 12; i += 6) {
            if (m >= STOPS[i] && m <= STOPS[i + 6]) {
                a = i;
                b = i + 6;
                break;
            }
        }
        float t = STOPS[b] == STOPS[a] ? 0f : (m - STOPS[a]) / (float) (STOPS[b] - STOPS[a]);
        return new Palette(
                lerpColor(STOPS[a + 1], STOPS[b + 1], t),
                lerpColor(STOPS[a + 2], STOPS[b + 2], t),
                lerpColor(STOPS[a + 3], STOPS[b + 3], t),
                lerp(STOPS[a + 4], STOPS[b + 4], t),
                lerp(STOPS[a + 5] * 0.01f, STOPS[b + 5] * 0.01f, t));
    }

    static int minuteOfDay(int hour, int minute) {
        return Math.max(0, Math.min(23, hour)) * 60 + Math.max(0, Math.min(59, minute));
    }

    static int lerpColor(int from, int to, float t) {
        float f = Math.max(0f, Math.min(1f, t));
        int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * f);
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * f);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * f);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerp(int from, int to, float t) {
        return Math.round(from + (to - from) * Math.max(0f, Math.min(1f, t)));
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * Math.max(0f, Math.min(1f, t));
    }
}
