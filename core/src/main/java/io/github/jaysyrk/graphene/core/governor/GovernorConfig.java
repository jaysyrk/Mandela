package io.github.jaysyrk.graphene.core.governor;

import io.github.jaysyrk.graphene.core.quality.ResponseCurve;
import io.github.jaysyrk.graphene.core.stats.FrameTimeRecorder;

/**
 * Tuning constants for {@link QualityGovernor}.
 *
 * <p>Two properties of the defaults matter more than the numbers themselves.
 *
 * <p><b>They are asymmetric.</b> A cut is up to {@link #maxDropStep} at a time and may repeat every
 * settle period; a restoration is a flat {@link #riseStep} and additionally waits out
 * {@link #recoveryDelayMillis}. A cut that arrives late has already cost the player a stutter, while
 * a restoration that arrives late costs nothing at all, so there is no reason to hurry it.
 *
 * <p><b>They are paced by the sensor, not by the clock.</b> Changing quality does not change the
 * measured frame time until the frames rendered under the new settings have filled the control
 * window. Deciding again before then means deciding twice on the same evidence, which is how a
 * controller ends up at the floor after a load spike it only needed to trim for.
 *
 * @param targetFps           frame rate the governor steers towards
 * @param minQuality          floor on the quality scalar, so the game never degrades past recognition
 * @param deadBand            relative frame-time error tolerated before reacting, e.g. 0.06 for 6%
 * @param riseMargin          extra headroom required before quality is restored
 * @param dropGain            fraction of the measured overshoot corrected per step
 * @param maxDropStep         largest single cut, so one bad measurement cannot strip the picture
 * @param riseStep            size of a restoration step
 * @param settleFrames        frames to wait after any change before deciding again
 * @param recoveryDelayMillis quiet period after a cut before any restoration may begin
 * @param riseIntervalMillis  gap between restoration steps
 * @param spikeGuardFrames    frames after a spike during which the controller holds still
 */
public record GovernorConfig(
        int targetFps,
        double minQuality,
        double deadBand,
        double riseMargin,
        double dropGain,
        double maxDropStep,
        double riseStep,
        int settleFrames,
        long recoveryDelayMillis,
        long riseIntervalMillis,
        int spikeGuardFrames) {

    public static final GovernorConfig DEFAULT = new GovernorConfig(
            60, 0.25, 0.06, 0.12,
            0.35, 0.15, 0.03,
            FrameTimeRecorder.CONTROL_WINDOW_FRAMES,
            2_000L, 500L, 20);

    public GovernorConfig {
        targetFps = (int) ResponseCurve.clamp(targetFps, 10, 1000);
        minQuality = ResponseCurve.clamp(minQuality, 0.0, 1.0);
        deadBand = ResponseCurve.clamp(deadBand, 0.0, 0.5);
        riseMargin = ResponseCurve.clamp(riseMargin, 0.0, 0.5);
        dropGain = ResponseCurve.clamp(dropGain, 0.01, 1.0);
        maxDropStep = ResponseCurve.clamp(maxDropStep, 0.01, 1.0);
        riseStep = ResponseCurve.clamp(riseStep, 0.001, 1.0);
        settleFrames = Math.max(1, settleFrames);
        recoveryDelayMillis = Math.max(0L, recoveryDelayMillis);
        riseIntervalMillis = Math.max(0L, riseIntervalMillis);
        spikeGuardFrames = Math.max(0, spikeGuardFrames);
    }

    /** The frame-time budget implied by the target, in microseconds. */
    public long targetFrameMicros() {
        return 1_000_000L / targetFps;
    }

    public GovernorConfig withTargetFps(int fps) {
        return new GovernorConfig(fps, minQuality, deadBand, riseMargin, dropGain, maxDropStep,
                riseStep, settleFrames, recoveryDelayMillis, riseIntervalMillis, spikeGuardFrames);
    }

    public GovernorConfig withMinQuality(double floor) {
        return new GovernorConfig(targetFps, floor, deadBand, riseMargin, dropGain, maxDropStep,
                riseStep, settleFrames, recoveryDelayMillis, riseIntervalMillis, spikeGuardFrames);
    }
}
