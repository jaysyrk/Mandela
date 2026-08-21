package io.github.jaysyrk.graphene.core.stats;

/**
 * Collects frame times and turns them into a signal the governor can act on.
 *
 * <p>The important job here is separating two things that look identical in a raw frame-time trace
 * but demand opposite responses. Sustained load -- too many entities, too high a render distance --
 * shows up as the whole distribution shifting, and the right answer is to lower quality. A spike --
 * a chunk mesh rebuild, a texture upload, a GC pause -- is one or two frames that are many times the
 * median, and lowering quality does nothing about it because it will not happen again for seconds.
 * A controller that cannot tell them apart spends its life sawing quality up and down in response to
 * noise.
 *
 * <p>So spikes are counted and reported separately, and excluded from the percentile window. What
 * remains is the steady-state distribution, which is what {@link #snapshot()} describes.
 *
 * <p>Not thread safe; this lives on the render thread.
 */
public final class FrameTimeRecorder {

    /** A frame this many times the running median is treated as a spike, not as load. */
    private static final double SPIKE_RATIO = 3.0;
    /** ...but only if it is also absolutely slow. Guards against a 1ms median making noise "spiky". */
    private static final long SPIKE_FLOOR_MICROS = 25_000;
    /** Frames between window decays. At 60fps this ages the window over roughly two seconds. */
    private static final int DECAY_INTERVAL_FRAMES = 120;
    /**
     * Frames the governor's control signal is computed over. Short enough that a quality change
     * shows up in it within a fraction of a second, which is what stops the controller from acting
     * twice on the same stale evidence.
     */
    public static final int CONTROL_WINDOW_FRAMES = 64;
    /** Below this many usable samples the short window is too noisy to steer on. */
    private static final int MIN_CONTROL_SAMPLES = 8;

    /** How many frames a spike stays counted in the reported window. */
    private static final int SPIKE_MEMORY_FRAMES = 300;
    /**
     * A spike is by definition brief. Once this many slow frames arrive back to back, they are not
     * a hitch any more -- they are the new normal, and treating them as noise would leave the
     * governor frozen while the frame rate stayed on the floor. So the run length is capped and the
     * excess is fed back in as load, which lets the median climb and the classifier self-correct.
     */
    private static final int MAX_CONSECUTIVE_SPIKES = 3;

    private final FrameTimeHistogram histogram = new FrameTimeHistogram();
    private final long[] recent;
    private final boolean[] recentSpike;
    private final int[] spikeFrames;
    private final long[] scratch;

    private int recentIndex;
    private int recentFilled;
    private int spikeIndex;
    private long frameCounter;
    private int sinceDecay;
    private long runningMedianMicros = 16_667;
    private long lastSpikeFrame = Long.MIN_VALUE;
    private long lastSpikeMicros;
    private int consecutiveSpikes;

    /**
     * @param graphSamples how many raw frame times to retain for display, e.g. 240 for a HUD graph
     */
    public FrameTimeRecorder(int graphSamples) {
        if (graphSamples < 1) {
            throw new IllegalArgumentException("graphSamples must be positive");
        }
        this.recent = new long[graphSamples];
        this.recentSpike = new boolean[graphSamples];
        this.scratch = new long[Math.min(graphSamples, CONTROL_WINDOW_FRAMES)];
        this.spikeFrames = new int[64];
        java.util.Arrays.fill(this.spikeFrames, Integer.MIN_VALUE);
    }

    /**
     * Records one frame.
     *
     * @param nanos wall time the frame took
     * @return true if this frame was classified as a spike rather than as load
     */
    public boolean record(long nanos) {
        if (nanos < 0) {
            return false;
        }
        long micros = nanos / 1_000L;
        frameCounter++;

        boolean looksLikeASpike = isSpike(micros);
        boolean spike = looksLikeASpike && consecutiveSpikes < MAX_CONSECUTIVE_SPIKES;
        // Counted on the raw classification, not the capped one. Resetting the run whenever the cap
        // suppresses a flag would let a permanent slowdown flag three frames in every four forever.
        if (looksLikeASpike) {
            consecutiveSpikes++;
        } else {
            consecutiveSpikes = 0;
        }

        recent[recentIndex] = micros;
        recentSpike[recentIndex] = spike;
        recentIndex = (recentIndex + 1) % recent.length;
        if (recentFilled < recent.length) {
            recentFilled++;
        }

        if (spike) {
            lastSpikeFrame = frameCounter;
            lastSpikeMicros = micros;
            spikeFrames[spikeIndex] = (int) Math.min(Integer.MAX_VALUE, frameCounter);
            spikeIndex = (spikeIndex + 1) % spikeFrames.length;
        } else {
            histogram.record(nanos);
            // The median only tracks non-spike frames, so a burst of stutter cannot raise the bar
            // for what counts as a spike and hide the stutter that follows.
            runningMedianMicros = histogram.percentileMicros(0.5);
        }

        if (++sinceDecay >= DECAY_INTERVAL_FRAMES) {
            sinceDecay = 0;
            histogram.decay();
        }
        return spike;
    }

    private boolean isSpike(long micros) {
        if (histogram.sampleCount() < 30) {
            // Too early to have a trustworthy median; treat everything as load so the window fills.
            return false;
        }
        return micros >= SPIKE_FLOOR_MICROS && micros > runningMedianMicros * SPIKE_RATIO;
    }

    /** Builds a snapshot of the current window. Allocates one small record; call once per tick. */
    public FrameStats snapshot() {
        if (histogram.sampleCount() == 0) {
            return FrameStats.EMPTY;
        }
        return new FrameStats(
                frameCounter,
                histogram.sampleCount(),
                histogram.meanMicros(),
                histogram.percentileMicros(0.50),
                histogram.percentileMicros(0.95),
                histogram.percentileMicros(0.99),
                histogram.maxMicros(),
                controlP95Micros(),
                countRecentSpikes());
    }

    /**
     * p95 over the most recent frames only, ignoring spikes.
     *
     * <p>Sorting is fine here: it is at most {@link #CONTROL_WINDOW_FRAMES} longs into a scratch
     * array that is allocated once, and the governor asks for this a few times a second rather than
     * every frame.
     */
    private long controlP95Micros() {
        int window = Math.min(recentFilled, scratch.length);
        if (window < MIN_CONTROL_SAMPLES) {
            return 0L;
        }
        int start = (recentIndex - window + recent.length) % recent.length;
        int count = 0;
        for (int i = 0; i < window; i++) {
            int idx = (start + i) % recent.length;
            if (!recentSpike[idx]) {
                scratch[count++] = recent[idx];
            }
        }
        if (count < MIN_CONTROL_SAMPLES) {
            // Almost everything recent was a spike. Fall back to the wide window rather than
            // steering on two samples.
            return histogram.percentileMicros(0.95);
        }
        java.util.Arrays.sort(scratch, 0, count);
        int rank = (int) Math.ceil(0.95 * count) - 1;
        return scratch[Math.max(0, Math.min(rank, count - 1))];
    }

    private int countRecentSpikes() {
        long cutoff = frameCounter - SPIKE_MEMORY_FRAMES;
        int n = 0;
        for (int frame : spikeFrames) {
            if (frame != Integer.MIN_VALUE && frame >= cutoff) {
                n++;
            }
        }
        return n;
    }

    /** Frames since the last spike, or {@link Long#MAX_VALUE} if there has never been one. */
    public long framesSinceSpike() {
        return lastSpikeFrame == Long.MIN_VALUE ? Long.MAX_VALUE : frameCounter - lastSpikeFrame;
    }

    public long lastSpikeMicros() {
        return lastSpikeMicros;
    }

    public long frameCount() {
        return frameCounter;
    }

    /**
     * Copies the retained raw frame times into {@code dest}, oldest first, for drawing a graph.
     *
     * @return the number of samples written
     */
    public int copyRecent(long[] dest) {
        int n = Math.min(dest.length, recentFilled);
        int start = (recentIndex - n + recent.length) % recent.length;
        for (int i = 0; i < n; i++) {
            dest[i] = recent[(start + i) % recent.length];
        }
        return n;
    }

    public void reset() {
        histogram.reset();
        java.util.Arrays.fill(recent, 0L);
        java.util.Arrays.fill(recentSpike, false);
        java.util.Arrays.fill(spikeFrames, Integer.MIN_VALUE);
        recentIndex = 0;
        recentFilled = 0;
        spikeIndex = 0;
        frameCounter = 0;
        sinceDecay = 0;
        lastSpikeFrame = Long.MIN_VALUE;
        lastSpikeMicros = 0;
        consecutiveSpikes = 0;
        runningMedianMicros = 16_667;
    }
}
