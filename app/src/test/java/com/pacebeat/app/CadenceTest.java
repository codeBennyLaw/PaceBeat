package com.pacebeat.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CadenceTest {
    @Test public void clampsToSupportedRange() {
        assertEquals(120, Cadence.clamp(10));
        assertEquals(170, Cadence.clamp(170));
        assertEquals(220, Cadence.clamp(900));
    }

    @Test public void convertsStepsPerMinuteToSampleAccurateInterval() {
        assertEquals(11025, Cadence.samplesPerBeat(22050, 120));
        assertEquals(7350, Cadence.samplesPerBeat(22050, 180));
        assertEquals(6014, Cadence.samplesPerBeat(22050, 220));
    }
}
