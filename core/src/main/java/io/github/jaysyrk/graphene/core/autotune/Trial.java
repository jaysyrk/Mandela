package io.github.jaysyrk.graphene.core.autotune;

import io.github.jaysyrk.graphene.core.quality.QualitySettings;

/**
 * One configuration the autotuner wants measured.
 *
 * @param sequence      1-based trial number, for progress reporting
 * @param indices       the index vector under test; do not mutate
 * @param settings      the settings the caller should apply before measuring
 * @param warmupFrames  frames to discard after applying, so the change has taken effect
 * @param measureFrames frames to collect statistics over
 * @param label         a short human-readable reason this trial exists
 */
public record Trial(
        int sequence,
        int[] indices,
        QualitySettings settings,
        int warmupFrames,
        int measureFrames,
        String label) {

    public int totalFrames() {
        return warmupFrames + measureFrames;
    }
}
