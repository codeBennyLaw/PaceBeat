package com.pacebeat.app;

public final class Cadence {
    public static final int MIN = 120;
    public static final int MAX = 220;
    public static final int DEFAULT = 170;

    public static final int MODE_STEADY = 0;
    public static final int MODE_FOUR_BEAT = 1;
    public static final int MODE_ALTERNATING = 2;

    private Cadence() {}

    public static int clamp(int spm) {
        return Math.max(MIN, Math.min(MAX, spm));
    }

    public static long samplesPerBeat(int sampleRate, int spm) {
        return Math.round(sampleRate * 60.0 / clamp(spm));
    }

    public static int normalizeRhythm(int mode) {
        return mode < MODE_STEADY || mode > MODE_ALTERNATING ? MODE_STEADY : mode;
    }

    public static int toneFrequency(int mode, long beatIndex) {
        int safeMode = normalizeRhythm(mode);
        if (safeMode == MODE_FOUR_BEAT) return beatIndex % 4 == 0 ? 1380 : 880;
        if (safeMode == MODE_ALTERNATING) return beatIndex % 2 == 0 ? 1220 : 760;
        return 980;
    }
}
