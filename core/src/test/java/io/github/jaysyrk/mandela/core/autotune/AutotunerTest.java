package io.github.jaysyrk.mandela.core.autotune;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jaysyrk.mandela.core.SyntheticMachine;
import io.github.jaysyrk.mandela.core.quality.DegradationLadder;
import io.github.jaysyrk.mandela.core.quality.QualitySettings;
import io.github.jaysyrk.mandela.core.stats.FrameStats;
import io.github.jaysyrk.mandela.core.stats.FrameTimeRecorder;
import org.junit.jupiter.api.Test;

class AutotunerTest {

    /** Measures one configuration the way the game would: warm up, then collect frames. */
    private static TrialResult measure(SyntheticMachine machine, Trial trial) {
        for (int i = 0; i < trial.warmupFrames(); i++) {
            machine.frameNanos(trial.settings());
        }
        FrameTimeRecorder recorder = new FrameTimeRecorder(Math.max(64, trial.measureFrames()));
        for (int i = 0; i < trial.measureFrames(); i++) {
            recorder.record(machine.frameNanos(trial.settings()));
        }
        FrameStats stats = recorder.snapshot();
        return TrialResult.of(stats.p99Micros(), stats.meanMicros());
    }

    private static AutotuneResult tune(SyntheticMachine machine, AutotuneConfig config) {
        Autotuner tuner = new Autotuner(config);
        tuner.start();
        Trial trial;
        int guard = 0;
        while ((trial = tuner.currentTrial()) != null) {
            tuner.submit(measure(machine, trial));
            if (++guard > 500) {
                throw new AssertionError("search did not terminate");
            }
        }
        return tuner.result();
    }

    /** The best the fixed ladder can do on this machine, for comparison. */
    private static double bestLadderVisualScore(SyntheticMachine machine, AutotuneConfig config) {
        double best = 0.0;
        for (double q = 0.0; q <= 1.0001; q += 0.01) {
            QualitySettings settings = DegradationLadder.resolve(q);
            long p99 = measure(machine, new Trial(0, new int[TuningSpace.AXES], settings,
                    config.warmupFrames(), config.measureFrames(), "ladder")).p99Micros();
            if (p99 <= config.budgetMicros()) {
                best = Math.max(best, settings.visualScore());
            }
        }
        return best;
    }

    @Test
    void leavesAFastMachineAtFullQuality() {
        AutotuneResult result = tune(SyntheticMachine.fast(1L), AutotuneConfig.DEFAULT);
        assertTrue(result.targetReached());
        assertEquals(QualitySettings.UNTOUCHED, result.settings(),
                "a machine with headroom should be tuned to vanilla, not to something clever");
    }

    @Test
    void findsSettingsThatActuallyMeetTheTarget() {
        SyntheticMachine machine = SyntheticMachine.modest(2L);
        AutotuneConfig config = AutotuneConfig.DEFAULT;
        AutotuneResult result = tune(machine, config);

        assertTrue(result.targetReached(), "the target should be reachable on this machine");
        assertTrue(result.achievedP99Micros() <= config.budgetMicros(),
                "winning configuration measured " + result.achievedP99Micros()
                        + "us against a budget of " + config.budgetMicros() + "us");
        assertTrue(result.visualScore() > 0.3,
                "it should not have given up more quality than it needed: " + result.visualScore());
    }

    @Test
    void saysSoWhenTheTargetIsOutOfReach() {
        AutotuneResult result = tune(SyntheticMachine.hopeless(3L),
                AutotuneConfig.DEFAULT.withTargetFps(120));

        assertFalse(result.targetReached(),
                "the player should be told the target is impossible, not handed a bare world");
        assertEquals(1, result.trialsRun(),
                "there is nothing cheaper than the cheapest setting, so there is nothing to search");
        assertTrue(result.log().stream().anyMatch(line -> line.contains("UNREACHABLE")));
    }

    /**
     * The premise of tuning by measurement. Two machines with opposite bottlenecks must end up with
     * different settings -- if they converge on the same answer, the search is just a preset with
     * extra steps.
     */
    @Test
    void reachesDifferentAnswersOnDifferentHardware() {
        // Terrain-bound: drawing chunks is what costs, entities are nearly free.
        SyntheticMachine terrainBound =
                new SyntheticMachine(5_000, 1_000, 22_000, 800, 400, 300, 200, 4L);
        // Entity-bound: the opposite, as a crowded server on a weak CPU behaves.
        SyntheticMachine entityBound =
                new SyntheticMachine(5_000, 22_000, 1_000, 800, 400, 300, 200, 5L);

        AutotuneResult terrain = tune(terrainBound, AutotuneConfig.DEFAULT);
        AutotuneResult entity = tune(entityBound, AutotuneConfig.DEFAULT);

        assertTrue(terrain.settings().renderDistanceTrim() > entity.settings().renderDistanceTrim(),
                "the terrain-bound machine should be the one giving up render distance: "
                        + terrain.settings().renderDistanceTrim() + " vs "
                        + entity.settings().renderDistanceTrim());
        assertTrue(entity.settings().entityDistanceScale() < terrain.settings().entityDistanceScale(),
                "the entity-bound machine should be the one giving up entity distance: "
                        + entity.settings().entityDistanceScale() + " vs "
                        + terrain.settings().entityDistanceScale());
    }

    /**
     * The claim that justifies the whole feature: a searched configuration keeps more of what the
     * player can see than the best the fixed ladder manages at the same frame rate, because the
     * ladder has to guess an order that suits every machine and this does not.
     */
    @Test
    void keepsMoreQualityThanTheFixedLadderAtTheSameFrameRate() {
        SyntheticMachine machine = new SyntheticMachine(5_000, 20_000, 1_200, 700, 400, 300, 150, 6L);
        AutotuneConfig config = AutotuneConfig.DEFAULT;

        AutotuneResult tuned = tune(machine, config);
        double ladderBest = bestLadderVisualScore(machine, config);

        assertTrue(tuned.targetReached(), "fixture sanity: the target should be reachable");
        assertTrue(tuned.visualScore() > ladderBest,
                "searched configuration scored " + tuned.visualScore()
                        + ", the best fixed ladder step scored " + ladderBest);
    }

    @Test
    void retriesASampleTakenWhileTheSceneWasChanging() {
        Autotuner tuner = new Autotuner(AutotuneConfig.DEFAULT);
        tuner.start();
        Trial first = tuner.currentTrial();
        assertNotNull(first);

        tuner.submit(TrialResult.invalid());
        assertEquals(first, tuner.currentTrial(),
                "an unusable sample must not advance the search or be believed");
        assertEquals(0, tuner.trialsRun());

        tuner.submit(TrialResult.of(8_000, 7_000));
        assertEquals(1, tuner.trialsRun());
    }

    @Test
    void givesUpAfterTooManyUnusableSamples() {
        AutotuneConfig config = new AutotuneConfig(60, 0.08, 5, 20, 60, 1);
        Autotuner tuner = new Autotuner(config);
        tuner.start();

        tuner.submit(TrialResult.invalid());
        tuner.submit(TrialResult.invalid());

        assertEquals(Autotuner.Phase.DONE, tuner.phase());
        assertNotNull(tuner.result());
        assertNull(tuner.currentTrial());
    }

    @Test
    void respectsTheTrialBudget() {
        AutotuneConfig config = new AutotuneConfig(60, 0.08, 2, 20, 5, 0);
        AutotuneResult result = tune(SyntheticMachine.fast(7L), config);
        assertTrue(result.trialsRun() <= 5, "ran " + result.trialsRun() + " trials against a cap of 5");
        assertTrue(result.log().stream().anyMatch(line -> line.contains("budget exhausted")));
    }

    @Test
    void cancellingStopsTheSearch() {
        Autotuner tuner = new Autotuner();
        tuner.start();
        assertTrue(tuner.isRunning());
        tuner.cancel();

        assertEquals(Autotuner.Phase.CANCELLED, tuner.phase());
        assertNull(tuner.currentTrial());
        assertNull(tuner.result());
        assertFalse(tuner.isRunning());
    }

    @Test
    void rejectsAResultNobodyAskedFor() {
        Autotuner tuner = new Autotuner();
        assertThrows(IllegalStateException.class, () -> tuner.submit(TrialResult.of(1, 1)));
    }

    @Test
    void progressOnlyMovesForward() {
        Autotuner tuner = new Autotuner(AutotuneConfig.DEFAULT);
        SyntheticMachine machine = SyntheticMachine.modest(8L);
        tuner.start();

        double previous = 0.0;
        Trial trial;
        while ((trial = tuner.currentTrial()) != null) {
            tuner.submit(measure(machine, trial));
            double progress = tuner.progress();
            assertTrue(progress >= previous - 1e-9, "progress went backwards: " + previous + " -> " + progress);
            assertTrue(progress <= 1.0);
            previous = progress;
        }
        assertEquals(1.0, tuner.progress());
    }

    @Test
    void everyTrialIsALegalConfiguration() {
        Autotuner tuner = new Autotuner(AutotuneConfig.DEFAULT);
        SyntheticMachine machine = SyntheticMachine.modest(9L);
        tuner.start();

        Trial trial;
        while ((trial = tuner.currentTrial()) != null) {
            QualitySettings s = trial.settings();
            assertTrue(s.entityDistanceScale() > 0 && s.entityDistanceScale() <= 1.0);
            assertTrue(s.particleDensity() >= 0 && s.particleDensity() <= 1.0);
            assertTrue(s.renderDistanceTrim() >= 0);
            assertEquals(TuningSpace.AXES, trial.indices().length);
            assertTrue(trial.sequence() > 0);
            tuner.submit(measure(machine, trial));
        }
    }
}
