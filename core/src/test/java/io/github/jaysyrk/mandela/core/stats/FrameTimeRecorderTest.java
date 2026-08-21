package io.github.jaysyrk.mandela.core.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FrameTimeRecorderTest {

    private static long millis(double value) {
        return (long) (value * 1_000_000);
    }

    private static FrameTimeRecorder warmedUp(double frameMillis, int frames) {
        FrameTimeRecorder recorder = new FrameTimeRecorder(240);
        for (int i = 0; i < frames; i++) {
            recorder.record(millis(frameMillis));
        }
        return recorder;
    }

    @Test
    void treatsEarlyFramesAsLoadWhileTheWindowFills() {
        FrameTimeRecorder recorder = new FrameTimeRecorder(240);
        for (int i = 0; i < 10; i++) {
            assertFalse(recorder.record(millis(50)),
                    "with no median established yet there is nothing to call a spike against");
        }
    }

    @Test
    void flagsAnIsolatedHitchAsASpike() {
        FrameTimeRecorder recorder = warmedUp(16.7, 200);
        assertTrue(recorder.record(millis(120)), "a 120ms frame against a 16.7ms median is a spike");
        assertEquals(0, recorder.framesSinceSpike());
        assertEquals(millis(120) / 1_000, recorder.lastSpikeMicros());
    }

    @Test
    void doesNotFlagOrdinaryVariation() {
        FrameTimeRecorder recorder = warmedUp(16.7, 200);
        assertFalse(recorder.record(millis(22)), "normal frame-to-frame variation is not a spike");
    }

    @Test
    void doesNotFlagFastFramesOnAFastMachine() {
        // A machine running at 500fps has a 2ms median, so a 7ms frame is more than 3x the median
        // and yet entirely unremarkable. The absolute floor is what stops that being called a spike.
        FrameTimeRecorder recorder = warmedUp(2.0, 200);
        assertFalse(recorder.record(millis(7)), "7ms is not a stutter however it compares to 2ms");
    }

    /**
     * The distinction that matters most. A hitch is noise and must not cost quality; a sustained
     * slowdown is load and must. Classifying the second as the first leaves the governor frozen
     * while the frame rate sits on the floor, because the median it compares against never updates.
     */
    @Test
    void treatsASustainedSlowdownAsLoadRatherThanEndlessSpikes() {
        FrameTimeRecorder recorder = warmedUp(8.0, 300);

        int flagged = 0;
        for (int i = 0; i < 400; i++) {
            if (recorder.record(millis(45))) {
                flagged++;
            }
        }

        assertTrue(flagged <= 5,
                "only the first few frames of a slowdown are a hitch, but " + flagged
                        + " frames were dismissed as spikes");
        assertTrue(recorder.snapshot().p95Micros() > 40_000,
                "the new, slower reality must reach the statistics");
        assertTrue(recorder.framesSinceSpike() > 100,
                "the classifier should have stopped calling the new normal a spike");
    }

    @Test
    void spikesStayOutOfThePercentiles() {
        FrameTimeRecorder recorder = warmedUp(10.0, 500);
        for (int i = 0; i < 20; i++) {
            for (int j = 0; j < 50; j++) {
                recorder.record(millis(10));
            }
            recorder.record(millis(200));
        }
        FrameStats stats = recorder.snapshot();
        assertTrue(stats.p99Micros() < 20_000,
                "p99 was " + stats.p99Micros() + "us; hitches must not be folded into the load signal");
        assertTrue(stats.spikesInWindow() > 0, "but they should still be reported to the player");
    }

    @Test
    void controlWindowRespondsFasterThanTheWideWindow() {
        FrameTimeRecorder recorder = warmedUp(30.0, 400);
        long wideBefore = recorder.snapshot().p95Micros();

        // The machine speeds up. The control signal should follow within its own window length;
        // the display window is allowed to lag, and that difference is the point of having both.
        for (int i = 0; i < FrameTimeRecorder.CONTROL_WINDOW_FRAMES; i++) {
            recorder.record(millis(5));
        }
        FrameStats stats = recorder.snapshot();

        assertTrue(wideBefore > 25_000, "fixture sanity");
        assertTrue(stats.controlP95Micros() < 8_000,
                "control signal still reads " + stats.controlP95Micros() + "us after a full window");
        assertTrue(stats.p95Micros() > stats.controlP95Micros(),
                "the wide window should still be catching up");
    }

    @Test
    void reportsNothingUsefulBeforeItHasFrames() {
        FrameTimeRecorder recorder = new FrameTimeRecorder(240);
        assertEquals(FrameStats.EMPTY, recorder.snapshot());
        assertFalse(recorder.snapshot().isMeaningful());
        assertEquals(Long.MAX_VALUE, recorder.framesSinceSpike());
    }

    @Test
    void copiesRecentFramesOldestFirst() {
        FrameTimeRecorder recorder = new FrameTimeRecorder(8);
        for (int i = 1; i <= 20; i++) {
            recorder.record(millis(i));
        }
        long[] out = new long[8];
        assertEquals(8, recorder.copyRecent(out));
        for (int i = 0; i < 8; i++) {
            assertEquals(millis(13 + i) / 1_000, out[i], "sample " + i);
        }
    }

    @Test
    void resetClearsEverything() {
        FrameTimeRecorder recorder = warmedUp(16.7, 300);
        recorder.record(millis(200));
        recorder.reset();
        assertEquals(FrameStats.EMPTY, recorder.snapshot());
        assertEquals(0, recorder.frameCount());
        assertEquals(Long.MAX_VALUE, recorder.framesSinceSpike());
    }
}
