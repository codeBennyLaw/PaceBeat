package com.pacebeat.app;

public final class RunMetrics {
    private RunMetrics() {}

    public static boolean reliableFix(float accuracyMeters, long ageMs, boolean mock) {
        return !mock && accuracyMeters > 0 && accuracyMeters <= 35 && ageMs >= 0 && ageMs <= 12000;
    }

    public static boolean reliableSegment(float meters, float accuracyA, float accuracyB, long deltaMs) {
        return deltaMs > 0 && deltaMs <= 30000
                && meters >= Math.max(3f, (accuracyA + accuracyB) * .35f)
                && meters <= 12f * deltaMs / 1000f + Math.max(accuracyA, accuracyB);
    }

    public static long paceSecondsPerKm(double meters, long activeMs) {
        return meters >= 100 && activeMs > 0 ? Math.round(activeMs / meters) : 0;
    }
}
