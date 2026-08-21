package io.github.jaysyrk.graphene.core.autotune;

/**
 * How thorough the search should be.
 *
 * @param targetFps       frame rate a configuration must sustain to count as feasible
 * @param safetyMargin    fraction of the budget held back, so the winning configuration is not sitting
 *                        exactly on the edge of the target where any extra load tips it over
 * @param warmupFrames    frames discarded after applying a configuration
 * @param measureFrames   frames measured per trial
 * @param maxTrials       hard cap, so a pathological search still terminates
 * @param maxRetries      how many times an invalidated trial is repeated before giving up
 */
public record AutotuneConfig(
        int targetFps,
        double safetyMargin,
        int warmupFrames,
        int measureFrames,
        int maxTrials,
        int maxRetries) {

    public static final AutotuneConfig DEFAULT = new AutotuneConfig(60, 0.08, 30, 120, 60, 2);

    public AutotuneConfig {
        if (targetFps < 10) {
            targetFps = 10;
        }
        if (safetyMargin < 0.0) {
            safetyMargin = 0.0;
        }
        if (safetyMargin > 0.5) {
            safetyMargin = 0.5;
        }
        if (warmupFrames < 1) {
            warmupFrames = 1;
        }
        if (measureFrames < 10) {
            measureFrames = 10;
        }
        if (maxTrials < 1) {
            maxTrials = 1;
        }
        if (maxRetries < 0) {
            maxRetries = 0;
        }
    }

    /** The frame time a trial has to come in under to be judged feasible, in microseconds. */
    public long budgetMicros() {
        return (long) ((1_000_000.0 / targetFps) * (1.0 - safetyMargin));
    }

    public AutotuneConfig withTargetFps(int fps) {
        return new AutotuneConfig(fps, safetyMargin, warmupFrames, measureFrames, maxTrials, maxRetries);
    }
}
