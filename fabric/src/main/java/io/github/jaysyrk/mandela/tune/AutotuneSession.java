package io.github.jaysyrk.mandela.tune;

import io.github.jaysyrk.mandela.Mandela;
import io.github.jaysyrk.mandela.client.MandelaRuntime;
import io.github.jaysyrk.mandela.core.autotune.AutotuneConfig;
import io.github.jaysyrk.mandela.core.autotune.AutotuneResult;
import io.github.jaysyrk.mandela.core.autotune.Autotuner;
import io.github.jaysyrk.mandela.core.autotune.Trial;
import io.github.jaysyrk.mandela.core.autotune.TrialResult;
import io.github.jaysyrk.mandela.core.quality.QualitySettings;
import io.github.jaysyrk.mandela.core.stats.FrameStats;
import io.github.jaysyrk.mandela.core.stats.FrameTimeRecorder;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Runs the autotuner against the live game.
 *
 * <p>The search itself is pure and lives in the core module; this is the part that has to deal with
 * the fact that the thing being measured is a game the player is sitting in front of. Its whole job
 * is guarding the comparison. Two configurations can only be ranked against each other if they were
 * measured looking at the same thing, so if the player walks off, turns around, opens a menu or
 * leaves the world mid-trial, the sample is thrown away rather than believed. A tuner that treats
 * "I turned to face a wall" as "that setting was cheaper" will confidently arrive at nonsense.
 */
public final class AutotuneSession {

    /** Moving further than this during a trial invalidates it. */
    private static final double MAX_DRIFT_BLOCKS = 3.0;
    /** Turning further than this during a trial invalidates it, in degrees. */
    private static final float MAX_TURN_DEGREES = 25.0f;

    private final MandelaRuntime runtime;
    private final FrameTimeRecorder trialRecorder = new FrameTimeRecorder(600);

    private Autotuner tuner;
    private Trial trial;
    private int frameInTrial;
    private boolean invalidated;

    private double anchorX;
    private double anchorY;
    private double anchorZ;
    private float anchorYaw;
    private float anchorPitch;

    private QualitySettings settingsBeforeRun = QualitySettings.UNTOUCHED;
    private Consumer<Component> reporter = message -> { };

    public AutotuneSession(MandelaRuntime runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
    }

    /**
     * Starts a search.
     *
     * @param targetFps the frame rate the winning configuration has to hold
     * @param reporter  where progress messages go, normally the player's chat
     * @return false if a search is already running or there is no world to measure in
     */
    public boolean start(int targetFps, Consumer<Component> reporter) {
        if (isRunning()) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null) {
            return false;
        }
        this.reporter = reporter == null ? message -> { } : reporter;
        this.settingsBeforeRun = runtime.settings();
        this.tuner = new Autotuner(AutotuneConfig.DEFAULT.withTargetFps(targetFps));
        this.tuner.start();
        beginTrial();
        Mandela.LOGGER.info("Autotune started, target {} fps", targetFps);
        return true;
    }

    public boolean isRunning() {
        return tuner != null && tuner.isRunning();
    }

    /** The configuration currently being measured; the runtime renders with this while tuning. */
    public QualitySettings settingsUnderTest() {
        return trial == null ? settingsBeforeRun : trial.settings();
    }

    /**
     * Feeds one frame into the current trial.
     *
     * <p>Warm-up frames are discarded rather than measured. A settings change does not take effect
     * on the frame it is applied -- caches are cold, meshes may need rebuilding -- and folding those
     * frames in would make every configuration look worse than it is, in proportion to how big a
     * change it was.
     */
    public void onFrameMeasured(long frameNanos) {
        if (!isRunning() || trial == null) {
            return;
        }
        if (!sceneIsStillComparable()) {
            invalidated = true;
        }
        frameInTrial++;

        if (frameInTrial <= trial.warmupFrames()) {
            return;
        }
        trialRecorder.record(frameNanos);

        if (frameInTrial >= trial.totalFrames()) {
            completeTrial();
        }
    }

    private void completeTrial() {
        TrialResult result;
        if (invalidated) {
            result = TrialResult.invalid();
        } else {
            FrameStats stats = trialRecorder.snapshot();
            result = stats.sampleCount() < 10
                    ? TrialResult.invalid()
                    : TrialResult.of(stats.p99Micros(), stats.meanMicros());
        }
        tuner.submit(result);

        if (tuner.isRunning()) {
            reportProgress();
            beginTrial();
            return;
        }
        finish();
    }

    private void beginTrial() {
        trial = tuner.currentTrial();
        frameInTrial = 0;
        invalidated = false;
        trialRecorder.reset();
        anchorOnPlayer();
    }

    private void anchorOnPlayer() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            invalidated = true;
            return;
        }
        anchorX = player.getX();
        anchorY = player.getY();
        anchorZ = player.getZ();
        anchorYaw = player.getYRot();
        anchorPitch = player.getXRot();
    }

    private boolean sceneIsStillComparable() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client == null ? null : client.player;
        if (player == null || client.level == null) {
            return false;
        }
        if (client.screen != null) {
            return false;
        }
        double dx = player.getX() - anchorX;
        double dy = player.getY() - anchorY;
        double dz = player.getZ() - anchorZ;
        if (dx * dx + dy * dy + dz * dz > MAX_DRIFT_BLOCKS * MAX_DRIFT_BLOCKS) {
            return false;
        }
        return angleDelta(player.getYRot(), anchorYaw) <= MAX_TURN_DEGREES
                && angleDelta(player.getXRot(), anchorPitch) <= MAX_TURN_DEGREES;
    }

    private static float angleDelta(float a, float b) {
        float delta = Math.abs(a - b) % 360.0f;
        return delta > 180.0f ? 360.0f - delta : delta;
    }

    private void reportProgress() {
        Trial next = tuner.currentTrial();
        if (next == null) {
            return;
        }
        reporter.accept(Component.literal(String.format(
                "Mandela: tuning %d%% - %s", Math.round(tuner.progress() * 100), next.label())));
    }

    private void finish() {
        AutotuneResult result = tuner.result();
        trial = null;
        if (result == null) {
            reporter.accept(Component.literal("Mandela: tuning stopped."));
            return;
        }
        for (String line : result.log()) {
            Mandela.LOGGER.info("[autotune] {}", line);
        }
        if (!result.targetReached()) {
            reporter.accept(Component.literal(String.format(
                    "Mandela: %d fps is out of reach on this machine even at the lowest settings "
                            + "(measured %.0f fps). Try a lower target.",
                    tuner.config().targetFps(), result.achievedFps())));
            return;
        }
        reporter.accept(Component.literal(String.format(
                "Mandela: tuning done in %d trials. Holding %.0f fps at %d%% visual quality. "
                        + "Run '/mandela mode fixed' to keep it.",
                result.trialsRun(), result.achievedFps(), Math.round(result.visualScore() * 100))));
        runtime.config().setFixedProfile(result.settings());
    }

    /** Stops a running search and restores what was rendering before it started. */
    public void cancel() {
        if (tuner != null && tuner.isRunning()) {
            tuner.cancel();
            reporter.accept(Component.literal("Mandela: tuning cancelled."));
        }
        tuner = null;
        trial = null;
        frameInTrial = 0;
    }

    /** Rough progress in [0, 1], for the HUD. */
    public double progress() {
        return tuner == null ? 0.0 : tuner.progress();
    }

    /** The finished result, or null if no search has completed. */
    public AutotuneResult result() {
        return tuner == null ? null : tuner.result();
    }
}
