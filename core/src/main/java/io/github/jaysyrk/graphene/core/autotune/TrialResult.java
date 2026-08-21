package io.github.jaysyrk.graphene.core.autotune;

/**
 * What a trial measured.
 *
 * @param p99Micros  99th percentile frame time over the measurement window
 * @param meanMicros mean frame time over the measurement window
 * @param valid      false if the sample is not comparable to the others, e.g. the player moved
 *                   somewhere different or the window lost focus mid-trial
 */
public record TrialResult(long p99Micros, long meanMicros, boolean valid) {

    public static TrialResult invalid() {
        return new TrialResult(0, 0, false);
    }

    public static TrialResult of(long p99Micros, long meanMicros) {
        return new TrialResult(p99Micros, meanMicros, true);
    }
}
