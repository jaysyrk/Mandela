package io.github.jaysyrk.mandela.core.governor;

import io.github.jaysyrk.mandela.core.quality.QualitySettings;

/**
 * The governor's output for one update: the quality scalar, what it resolved to, and enough context
 * to explain the decision on the HUD.
 *
 * @param quality    the control scalar in [0, 1]
 * @param phase      what the controller is doing
 * @param settings   the resolved settings the renderer should honour
 * @param loadRatio  measured frame time over the budget; 1.0 is exactly on target
 * @param targetMet  whether the current frame time meets the target
 */
public record GovernorState(
        double quality,
        GovernorPhase phase,
        QualitySettings settings,
        double loadRatio,
        boolean targetMet) {

    public static final GovernorState IDLE =
            new GovernorState(1.0, GovernorPhase.WARMUP, QualitySettings.UNTOUCHED, 0.0, true);

    /** Fraction of the frame budget still unspent; negative when over budget. */
    public double headroom() {
        return loadRatio <= 0 ? 0.0 : 1.0 - loadRatio;
    }

    /** True when Mandela is not changing anything the player would see. */
    public boolean isTransparent() {
        return quality >= 0.999;
    }
}
