package io.github.jaysyrk.graphene.core.stats;

/**
 * An immutable snapshot of the frame-time window. Times are microseconds so that integer maths stays
 * exact; the {@code *Fps} accessors are for display, not for control decisions.
 *
 * @param frameIndex    how many frames the recorder has seen in total, used to time decisions
 * @param sampleCount   frames represented by this snapshot
 * @param meanMicros    arithmetic mean frame time
 * @param p50Micros     median frame time
 * @param p95Micros     the frame time 95% of frames come in under; the governor's load signal
 * @param p99Micros     the frame time 99% of frames come in under; the stutter signal
 * @param controlP95Micros p95 over only the last {@link FrameTimeRecorder#CONTROL_WINDOW_FRAMES}
 *                      frames. The wide window above is the right thing to show a player and the
 *                      wrong thing to steer on: it still contains frames rendered at the previous
 *                      settings, so a controller reading it reacts to a change it already made and
 *                      winds itself into the floor. This is the signal the governor uses
 * @param maxMicros     the single worst frame in the window
 * @param spikesInWindow frames flagged as spikes rather than sustained load
 */
public record FrameStats(
        long frameIndex,
        long sampleCount,
        long meanMicros,
        long p50Micros,
        long p95Micros,
        long p99Micros,
        long maxMicros,
        long controlP95Micros,
        int spikesInWindow) {

    public static final FrameStats EMPTY = new FrameStats(0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** True once the window holds enough frames for percentiles to mean anything. */
    public boolean isMeaningful() {
        return sampleCount >= 30 && controlP95Micros > 0;
    }

    public double meanFps() {
        return toFps(meanMicros);
    }

    /**
     * The commonly quoted "1% low": the frame rate implied by the 99th percentile frame time. It is
     * the number that tracks how choppy the game feels, as opposed to the headline average.
     */
    public double onePercentLowFps() {
        return toFps(p99Micros);
    }

    public double p95Fps() {
        return toFps(p95Micros);
    }

    private static double toFps(long micros) {
        return micros <= 0 ? 0.0 : 1_000_000.0 / micros;
    }

    public static double microsToMillis(long micros) {
        return micros / 1000.0;
    }
}
