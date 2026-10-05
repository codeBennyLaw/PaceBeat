package com.pacebeat.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class RunMetricsTest {
    @Test public void acceptsRecentAccurateGps() {
        assertTrue(RunMetrics.reliableFix(8, 2000, false));
        assertTrue(RunMetrics.reliableFix(35, 12000, false));
    }

    @Test public void rejectsStaleFutureMockAndInaccurateFixes() {
        assertFalse(RunMetrics.reliableFix(8, 12001, false));
        assertFalse(RunMetrics.reliableFix(8, -1, false));
        assertFalse(RunMetrics.reliableFix(36, 1000, false));
        assertFalse(RunMetrics.reliableFix(0, 1000, false));
        assertFalse(RunMetrics.reliableFix(8, 1000, true));
        assertFalse(RunMetrics.reliableFix(Float.NaN, 1000, false));
    }

    @Test public void acceptsPlausibleRunningSegment() {
        assertTrue(RunMetrics.reliableSegment(12, 5, 5, 3000));
    }

    @Test public void rejectsDriftAndTeleports() {
        assertFalse(RunMetrics.reliableSegment(2, 5, 5, 3000));
        assertFalse(RunMetrics.reliableSegment(10, 25, 25, 3000));
        assertFalse(RunMetrics.reliableSegment(200, 5, 5, 3000));
    }

    @Test public void neverConnectsLongGapsOrOutOfOrderFixes() {
        assertFalse(RunMetrics.reliableSegment(30, 5, 5, 30001));
        assertFalse(RunMetrics.reliableSegment(10, 5, 5, 0));
        assertFalse(RunMetrics.reliableSegment(10, 5, 5, -3000));
    }

    @Test public void paceRequiresEnoughMeasuredDistanceAndTime() {
        assertEquals(0, RunMetrics.paceSecondsPerKm(99, 30000));
        assertEquals(0, RunMetrics.paceSecondsPerKm(100, 0));
        assertEquals(300, RunMetrics.paceSecondsPerKm(1000, 300000));
        assertEquals(360, RunMetrics.paceSecondsPerKm(100, 36000));
    }
}
