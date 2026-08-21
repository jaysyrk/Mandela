package io.github.jaysyrk.graphene.core.autotune;

import io.github.jaysyrk.graphene.core.quality.QualitySettings;
import java.util.List;

/**
 * The outcome of a search.
 *
 * @param settings        the best configuration found
 * @param indices         its index vector
 * @param trialsRun       how many measurements it took
 * @param achievedP99Micros the frame time measured at the winning configuration
 * @param targetReached   false when even the cheapest configuration missed the target, which means
 *                        the target is beyond this machine and the player should be told so rather
 *                        than left wondering why the game looks bare
 * @param log             one line per trial, in order
 */
public record AutotuneResult(
        QualitySettings settings,
        int[] indices,
        int trialsRun,
        long achievedP99Micros,
        boolean targetReached,
        List<String> log) {

    public double achievedFps() {
        return achievedP99Micros <= 0 ? 0.0 : 1_000_000.0 / achievedP99Micros;
    }

    public double visualScore() {
        return settings.visualScore();
    }
}
