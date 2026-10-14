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

    @Test public void supportsDistinctBeatPatterns() {
        assertEquals(980, Cadence.toneFrequency(Cadence.MODE_STEADY, 0));
        assertEquals(1380, Cadence.toneFrequency(Cadence.MODE_FOUR_BEAT, 4));
        assertEquals(880, Cadence.toneFrequency(Cadence.MODE_FOUR_BEAT, 3));
        assertEquals(1220, Cadence.toneFrequency(Cadence.MODE_ALTERNATING, 0));
        assertEquals(760, Cadence.toneFrequency(Cadence.MODE_ALTERNATING, 1));
    }

    @Test public void normalizesUnknownBeatPatterns() {
        assertEquals(Cadence.MODE_STEADY, Cadence.normalizeRhythm(-1));
        assertEquals(Cadence.MODE_STEADY, Cadence.normalizeRhythm(99));
        assertEquals(Cadence.MODE_ALTERNATING, Cadence.normalizeRhythm(Cadence.MODE_ALTERNATING));
    }
}
