package io.github.jaysyrk.mandela.core.governor;

import io.github.jaysyrk.mandela.core.quality.DegradationLadder;
import io.github.jaysyrk.mandela.core.quality.QualitySettings;
import io.github.jaysyrk.mandela.core.quality.ResponseCurve;
import io.github.jaysyrk.mandela.core.stats.FrameStats;

/**
 * A closed loop that holds the frame rate near a target by trading away visual detail.
 *
 * <p>The measured signal is a 95th percentile frame time, not a mean. A mean that meets the target
 * while one frame in ten misses it still feels bad, and the mean is exactly the statistic that hides
 * it. Steering on p95 means nearly every frame meets the target, which is what a player perceives as
 * smoothness.
 *
 * <p>Four mechanisms keep it from the failure mode that makes adaptive quality unbearable, which is
 * not being slow to react but oscillating:
 *
 * <ul>
 *   <li><b>Settling.</b> Quality is changed in discrete steps, and after each one the controller
 *       waits for the frames rendered under the new settings to fill the control window before
 *       deciding again. Without this the loop reacts to its own changes: the frame time it reads
 *       still describes the settings it just replaced, so it cuts again, and again, and arrives at
 *       the floor when a single step would have done.
 *   <li><b>Proportional steps.</b> Each cut corrects a fraction of the measured overshoot rather
 *       than a fixed amount, so the step shrinks as the error does and the loop converges instead of
 *       ringing around the target.
 *   <li><b>Asymmetry.</b> Quality falls quickly and returns slowly, because the two mistakes are not
 *       equally costly.
 *   <li><b>A spike guard.</b> Frame-time spikes come from chunk builds and GC pauses, not from draw
 *       load, so cutting quality does not prevent the next one. The controller holds still through
 *       them rather than paying for noise with permanent visual loss.
 * </ul>
 *
 * <p>Time and frame counts are passed in rather than read, so the whole loop is deterministic under
 * test. Not thread safe; it belongs to the render thread.
 */
public final class QualityGovernor {

    private GovernorConfig config;
    private boolean enabled = true;

    private double quality = 1.0;
    private QualitySettings settings = QualitySettings.UNTOUCHED;
    private GovernorPhase phase = GovernorPhase.WARMUP;
    private double loadRatio;

    private long lastDecisionFrame = Long.MIN_VALUE;
    private long lastDropNanos = Long.MIN_VALUE;
    private long lastRiseNanos = Long.MIN_VALUE;

    public QualityGovernor() {
        this(GovernorConfig.DEFAULT);
    }

    public QualityGovernor(GovernorConfig config) {
        this.config = java.util.Objects.requireNonNull(config, "config");
    }

    /**
     * Advances the controller. Call once per frame; most calls do nothing but update the reported
     * load, because the controller is waiting for its last change to show up in the measurement.
     *
     * @param stats            the current frame-time window
     * @param nowNanos         a monotonic clock reading
     * @param framesSinceSpike frames elapsed since the last frame-time spike
     */
    public GovernorState update(FrameStats stats, long nowNanos, long framesSinceSpike) {
        if (!enabled) {
            phase = GovernorPhase.DISABLED;
            quality = 1.0;
            settings = QualitySettings.UNTOUCHED;
            loadRatio = 0.0;
            return state();
        }
        if (stats == null || !stats.isMeaningful()) {
            phase = GovernorPhase.WARMUP;
            return state();
        }

        loadRatio = (double) stats.controlP95Micros() / config.targetFrameMicros();
        double error = loadRatio - 1.0;

        if (framesSinceSpike < config.spikeGuardFrames()) {
            phase = GovernorPhase.SPIKE_GUARD;
            return state();
        }
        if (!settled(stats.frameIndex())) {
            // The measurement still describes the settings we replaced. Acting on it now would be
            // acting twice on one piece of evidence. The phase from the last decision stands, so
            // the HUD keeps saying "dropping" for as long as that is what is happening.
            if (phase == GovernorPhase.WARMUP || phase == GovernorPhase.SPIKE_GUARD) {
                phase = classifySteady();
            }
            return state();
        }

        if (error > config.deadBand()) {
            return dropStep(error, nowNanos, stats.frameIndex());
        }
        if (error < -(config.deadBand() + config.riseMargin())) {
            return riseStep(nowNanos, stats.frameIndex());
        }
        phase = classifySteady();
        return state();
    }

    private GovernorState dropStep(double error, long nowNanos, long frameIndex) {
        if (quality <= config.minQuality() + 1e-9) {
            phase = GovernorPhase.SATURATED;
            return state();
        }
        // Correct a fraction of the measured overshoot. The step shrinks with the error, so the
        // approach to the target is geometric rather than a march that overshoots and comes back.
        double step = Math.min(config.maxDropStep(), config.dropGain() * error);
        step = Math.max(step, 0.01);
        lastDropNanos = nowNanos;
        lastDecisionFrame = frameIndex;
        applyQuality(quality - step);
        phase = quality <= config.minQuality() + 1e-9
                ? GovernorPhase.SATURATED
                : GovernorPhase.DROPPING;
        return state();
    }

    private GovernorState riseStep(long nowNanos, long frameIndex) {
        if (quality >= 1.0) {
            phase = GovernorPhase.STEADY;
            return state();
        }
        if (!recoveryAllowed(nowNanos)) {
            phase = GovernorPhase.STEADY;
            return state();
        }
        lastRiseNanos = nowNanos;
        lastDecisionFrame = frameIndex;
        applyQuality(quality + config.riseStep());
        phase = GovernorPhase.RECOVERING;
        return state();
    }

    private boolean settled(long frameIndex) {
        return lastDecisionFrame == Long.MIN_VALUE
                || frameIndex - lastDecisionFrame >= config.settleFrames();
    }

    private boolean recoveryAllowed(long nowNanos) {
        if (lastDropNanos != Long.MIN_VALUE
                && millisSince(lastDropNanos, nowNanos) < config.recoveryDelayMillis()) {
            return false;
        }
        return lastRiseNanos == Long.MIN_VALUE
                || millisSince(lastRiseNanos, nowNanos) >= config.riseIntervalMillis();
    }

    private static long millisSince(long thenNanos, long nowNanos) {
        long delta = nowNanos - thenNanos;
        return delta < 0 ? Long.MAX_VALUE : delta / 1_000_000L;
    }

    private GovernorPhase classifySteady() {
        boolean atFloor = quality <= config.minQuality() + 1e-9;
        return atFloor && loadRatio > 1.0 + config.deadBand()
                ? GovernorPhase.SATURATED
                : GovernorPhase.STEADY;
    }

    private GovernorState applyQuality(double raw) {
        double clamped = ResponseCurve.clamp(raw, config.minQuality(), 1.0);
        if (clamped != quality) {
            quality = clamped;
            settings = DegradationLadder.resolve(quality);
        }
        return state();
    }

    /** The most recent decision. Cheap; safe to call every frame. */
    public GovernorState state() {
        return new GovernorState(
                quality, phase, settings, loadRatio, loadRatio <= 1.0 + config.deadBand());
    }

    public QualitySettings settings() {
        return enabled ? settings : QualitySettings.UNTOUCHED;
    }

    public double quality() {
        return quality;
    }

    public GovernorConfig config() {
        return config;
    }

    /** Replaces the tuning constants, re-clamping current quality to the new floor. */
    public void setConfig(GovernorConfig config) {
        this.config = java.util.Objects.requireNonNull(config, "config");
        applyQuality(quality);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Turning the governor off restores full quality immediately; there is no reason to ease out. */
    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            reset();
        }
    }

    /** Pins quality to an exact value, as the autotuner does while measuring a candidate. */
    public void forceQuality(double value) {
        applyQuality(value);
        phase = GovernorPhase.STEADY;
        lastDecisionFrame = Long.MIN_VALUE;
    }

    public void reset() {
        quality = 1.0;
        settings = QualitySettings.UNTOUCHED;
        phase = enabled ? GovernorPhase.WARMUP : GovernorPhase.DISABLED;
        loadRatio = 0.0;
        lastDecisionFrame = Long.MIN_VALUE;
        lastDropNanos = Long.MIN_VALUE;
        lastRiseNanos = Long.MIN_VALUE;
    }
}
