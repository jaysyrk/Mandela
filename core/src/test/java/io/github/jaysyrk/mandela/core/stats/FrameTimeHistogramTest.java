package io.github.jaysyrk.mandela.core.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FrameTimeHistogramTest {

    private static long micros(double millis) {
        return (long) (millis * 1_000);
    }

    @Test
    void reportsNothingWhenEmpty() {
        FrameTimeHistogram h = new FrameTimeHistogram();
        assertEquals(0, h.sampleCount());
        assertEquals(0, h.percentileMicros(0.99));
        assertEquals(0, h.meanMicros());
    }

    @Test
    void ignoresNegativeDurations() {
        FrameTimeHistogram h = new FrameTimeHistogram();
        h.record(-1);
        assertEquals(0, h.sampleCount(), "a backwards clock must not be folded into the zero bucket");
    }

    @Test
    void percentilesTrackAKnownDistributionWithinBucketResolution() {
        FrameTimeHistogram h = new FrameTimeHistogram();
        Random random = new Random(4242L);
        long[] samples = new long[20_000];
        for (int i = 0; i < samples.length; i++) {
            // A plausible frame-time shape: tight around 16.7ms with an occasional slow frame.
            double base = 16_700 + random.nextGaussian() * 1_500;
            if (random.nextInt(50) == 0) {
                base += random.nextDouble() * 20_000;
            }
            long value = (long) Math.max(1_000, base);
            samples[i] = value;
            h.record(value * 1_000);
        }
        Arrays.sort(samples);

        for (double q : new double[]{0.5, 0.95, 0.99}) {
            long exact = samples[(int) Math.ceil(q * samples.length) - 1];
            long reported = h.percentileMicros(q);
            long tolerance = Math.max(FrameTimeHistogram.FINE_BUCKET_US, exact / 12);
            assertTrue(Math.abs(reported - exact) <= tolerance,
                    "p" + q + ": reported " + reported + "us, exact " + exact + "us");
        }
    }

    @Test
    void meanMatchesTheArithmeticMean() {
        FrameTimeHistogram h = new FrameTimeHistogram();
        for (int i = 0; i < 1_000; i++) {
            h.record(micros(10) * 1_000);
        }
        assertEquals(micros(10), h.meanMicros());
    }

    @Test
    void percentileZeroAndOneAreTheEnds() {
        FrameTimeHistogram h = new FrameTimeHistogram();
        for (int i = 1; i <= 100; i++) {
            h.record(micros(i) * 1_000);
        }
        assertTrue(h.percentileMicros(0.0) < micros(3), "p0 should sit at the fast end");
        assertTrue(h.percentileMicros(1.0) >= micros(90), "p100 should sit at the slow end");
    }

    @Test
    void percentilesAreMonotonic() {
        FrameTimeHistogram h = new FrameTimeHistogram();
        Random random = new Random(7L);
        for (int i = 0; i < 5_000; i++) {
            h.record((long) (random.nextDouble() * 60_000_000));
        }
        long previous = 0;
        for (double q = 0.0; q <= 1.0; q += 0.01) {
            long value = h.percentileMicros(q);
            assertTrue(value >= previous, "percentiles went backwards at q=" + q);
            previous = value;
        }
    }

    @Test
    void tracksVeryLongFramesWithoutOverflowing() {
        FrameTimeHistogram h = new FrameTimeHistogram();
        h.record(30_000_000_000L); // a 30 second hitch, far beyond the tracked range
        assertTrue(h.percentileMicros(1.0) > 0, "an extreme frame must still land somewhere finite");
        assertTrue(h.percentileMicros(1.0) < Long.MAX_VALUE / 2);
        assertEquals(30_000_000L, h.maxMicros());
    }

    @Test
    void decayAgesTheWindowTowardsRecentFrames() {
        FrameTimeHistogram h = new FrameTimeHistogram();
        for (int i = 0; i < 1_000; i++) {
            h.record(micros(50) * 1_000);
        }
        long slowMedian = h.percentileMicros(0.5);

        // The machine speeds up. Without decay the old slow frames would dominate forever.
        for (int round = 0; round < 8; round++) {
            h.decay();
            for (int i = 0; i < 500; i++) {
                h.record(micros(8) * 1_000);
            }
        }
        long fastMedian = h.percentileMicros(0.5);
        assertTrue(slowMedian > micros(45), "sanity: the slow window should read slow");
        assertTrue(fastMedian < micros(10),
                "after decay the window should reflect the new speed, got " + fastMedian + "us");
    }

    @Test
    void decayEventuallyEmptiesAnIdleWindow() {
        FrameTimeHistogram h = new FrameTimeHistogram();
        for (int i = 0; i < 64; i++) {
            h.record(micros(16) * 1_000);
        }
        for (int i = 0; i < 20; i++) {
            h.decay();
        }
        assertEquals(0, h.sampleCount(), "halving must reach zero rather than stalling at one");
        assertEquals(0, h.maxMicros());
    }

    @Test
    void bucketBoundsCoverTheWholeRangeInOrder() {
        long previous = -1;
        for (long value : new long[]{0, 1, 249, 250, 39_999, 40_000, 100_000, 1_000_000, 3_999_999}) {
            int bucket = FrameTimeHistogram.bucketOf(value);
            assertTrue(bucket >= previous, "bucket index went backwards at " + value);
            assertTrue(bucket < FrameTimeHistogram.bucketCount(), "bucket out of range at " + value);
            previous = bucket;
        }
    }
}
