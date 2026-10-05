package com.pacebeat.app;

public final class Cadence {
    public static final int MIN = 120;
    public static final int MAX = 220;
    public static final int DEFAULT = 170;

    private Cadence() {}

    public static int clamp(int spm) {
        return Math.max(MIN, Math.min(MAX, spm));
    }

    public static long samplesPerBeat(int sampleRate, int spm) {
        return Math.round(sampleRate * 60.0 / clamp(spm));
    }
}
