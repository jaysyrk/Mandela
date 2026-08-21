package io.github.jaysyrk.mandela.core.governor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jaysyrk.mandela.core.SyntheticMachine;
import io.github.jaysyrk.mandela.core.quality.QualitySettings;
import io.github.jaysyrk.mandela.core.stats.FrameStats;
import io.github.jaysyrk.mandela.core.stats.FrameTimeRecorder;
import org.junit.jupiter.api.Test;

class QualityGovernorTest {

    /** Drives a governor against a synthetic machine and reports what happened. */
    private static final class Simulation {
        final QualityGovernor governor;
        final FrameTimeRecorder recorder = new FrameTimeRecorder(240);
        final SyntheticMachine machine;
        long clockNanos;
        double minQualitySeen = 1.0;
        double maxQualityAfterSettling;

        Simulation(GovernorConfig config, SyntheticMachine machine) {
            this.governor = new QualityGovernor(config);
            this.machine = machine;
        }

        /** Runs frames, returning the state at the end. */
        GovernorState run(int frames) {
            GovernorState state = governor.state();
            for (int i = 0; i < frames; i++) {
                QualitySettings settings = governor.settings();
                long nanos = machine.frameNanos(settings);
                recorder.record(nanos);
                clockNanos += nanos;
                state = governor.update(recorder.snapshot(), clockNanos, recorder.framesSinceSpike());
                minQualitySeen = Math.min(minQualitySeen, state.quality());
            }
            return state;
        }

        /** Runs frames while tracking how much quality moves, to detect hunting. */
        double runAndMeasureSwing(int frames) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (int i = 0; i < frames; i++) {
                QualitySettings settings = governor.settings();
                long nanos = machine.frameNanos(settings);
                recorder.record(nanos);
                clockNanos += nanos;
                GovernorState state = governor.update(
                        recorder.snapshot(), clockNanos, recorder.framesSinceSpike());
                min = Math.min(min, state.quality());
                max = Math.max(max, state.quality());
            }
            maxQualityAfterSettling = max;
            return max - min;
        }

        double measuredP95Micros() {
            return recorder.snapshot().p95Micros();
        }
    }

    @Test
    void leavesAFastMachineAlone() {
        Simulation sim = new Simulation(GovernorConfig.DEFAULT, SyntheticMachine.fast(1L));
        GovernorState state = sim.run(3_000);

        assertEquals(1.0, state.quality(), 1e-9,
                "a machine with headroom to spare must not lose any quality");
        assertEquals(QualitySettings.UNTOUCHED, state.settings());
        assertTrue(state.isTransparent());
    }

    @Test
    void bringsAStruggglingMachineBackToTarget() {
        SyntheticMachine machine = SyntheticMachine.modest(2L);
        Simulation sim = new Simulation(GovernorConfig.DEFAULT, machine);

        // At full quality this machine misses 60fps badly.
        assertTrue(machine.frameMicros(QualitySettings.UNTOUCHED) > 16_667,
                "fixture sanity: the modest machine should not manage 60fps untouched");

        GovernorState state = sim.run(8_000);

        assertTrue(state.quality() < 1.0, "quality should have been reduced");
        assertTrue(state.targetMet(),
                "expected the target to be met, load ratio was " + state.loadRatio());
        assertTrue(sim.measuredP95Micros() <= 16_667 * 1.10,
                "p95 settled at " + sim.measuredP95Micros() + "us, target is 16667us");
    }

    @Test
    void settlesInsteadOfHunting() {
        Simulation sim = new Simulation(GovernorConfig.DEFAULT, SyntheticMachine.modest(3L));
        sim.run(10_000);

        // Once settled, quality should barely move. Oscillation is the failure mode that makes
        // adaptive quality unusable, and it is invisible in an average-frame-rate measurement.
        double swing = sim.runAndMeasureSwing(6_000);
        assertTrue(swing < 0.10,
                "quality swung by " + swing + " after settling, which would be visible as pulsing");
    }

    @Test
    void recoversWhenTheSceneGetsEasier() {
        SyntheticMachine machine = SyntheticMachine.modest(4L);
        Simulation sim = new Simulation(GovernorConfig.DEFAULT, machine);

        machine.setSceneLoad(2.0);
        GovernorState underLoad = sim.run(8_000);
        assertTrue(underLoad.quality() < 0.9, "heavy scene should force a real cut");

        machine.setSceneLoad(0.25);
        GovernorState afterRelief = sim.run(30_000);
        assertTrue(afterRelief.quality() > underLoad.quality() + 0.2,
                "quality should climb back once the load goes away: "
                        + underLoad.quality() + " -> " + afterRelief.quality());
    }

    /**
     * Feeds a fixed frame time so the rates are measured directly, rather than through a machine
     * whose quality would clamp at the floor part way through and truncate the comparison.
     */
    private static int framesToMoveQuality(double startQuality, double frameMillis, double delta) {
        GovernorConfig config = GovernorConfig.DEFAULT.withMinQuality(0.0);
        QualityGovernor governor = new QualityGovernor(config);
        FrameTimeRecorder recorder = new FrameTimeRecorder(240);
        governor.forceQuality(startQuality);

        long nanos = (long) (frameMillis * 1_000_000);
        long clock = 0;
        for (int frame = 0; frame < 200_000; frame++) {
            recorder.record(nanos);
            clock += nanos;
            governor.update(recorder.snapshot(), clock, recorder.framesSinceSpike());
            if (Math.abs(governor.quality() - startQuality) >= delta) {
                return frame;
            }
        }
        return Integer.MAX_VALUE;
    }

    @Test
    void dropsFasterThanItRecovers() {
        // Symmetric provocations: 40% over budget, and 40% under it.
        int framesToDrop = framesToMoveQuality(0.60, 16.667 * 1.4, 0.15);
        int framesToRecover = framesToMoveQuality(0.60, 16.667 * 0.6, 0.15);

        assertTrue(framesToDrop < framesToRecover,
                "reacting to a stall must outpace giving quality back: dropped in "
                        + framesToDrop + " frames, recovered in " + framesToRecover);
        assertTrue(framesToRecover > framesToDrop * 3L,
                "recovery should be markedly slower, not marginally: " + framesToDrop
                        + " vs " + framesToRecover + " frames");
    }

    /**
     * Regression guard for the failure this controller is built around. A controller that re-decides
     * before its own last change has reached the measurement will cut, read a frame time that still
     * describes the old settings, cut again, and land on the floor -- having destroyed the picture to
     * fix an overshoot that one step would have covered.
     */
    @Test
    void doesNotOvershootWhenLoadJumps() {
        SyntheticMachine machine = SyntheticMachine.fast(11L);
        Simulation sim = new Simulation(GovernorConfig.DEFAULT, machine);
        sim.run(2_000);
        assertEquals(1.0, sim.governor.quality(), 1e-9, "fixture sanity: should start untouched");

        // Roughly a 50% overshoot: enough to need a real cut, nowhere near enough to justify the
        // floor. Held long enough for the loop to settle wherever it is going to settle.
        machine.setSceneLoad(3.0);
        sim.run(12_000);

        GovernorState state = sim.governor.state();
        assertTrue(state.targetMet(),
                "should have found a quality that meets the target, load ratio "
                        + state.loadRatio());
        assertTrue(state.quality() > GovernorConfig.DEFAULT.minQuality() + 0.05,
                "settled at " + state.quality() + ", which is the floor -- the loop overshot");
    }

    @Test
    void ignoresIsolatedSpikes() {
        // A machine that is comfortably fast, interrupted by occasional long frames of the kind a
        // chunk rebuild or a GC pause produces. Lowering quality would not prevent a single one.
        SyntheticMachine machine = SyntheticMachine.fast(6L);
        Simulation sim = new Simulation(GovernorConfig.DEFAULT, machine);
        sim.run(500);

        for (int burst = 0; burst < 40; burst++) {
            for (int i = 0; i < 60; i++) {
                long nanos = machine.frameNanos(sim.governor.settings());
                sim.recorder.record(nanos);
                sim.clockNanos += nanos;
                sim.governor.update(sim.recorder.snapshot(), sim.clockNanos,
                        sim.recorder.framesSinceSpike());
            }
            long spike = 90_000_000L; // a 90ms hitch
            sim.recorder.record(spike);
            sim.clockNanos += spike;
            sim.governor.update(sim.recorder.snapshot(), sim.clockNanos,
                    sim.recorder.framesSinceSpike());
        }

        assertEquals(1.0, sim.governor.quality(), 1e-9,
                "stutter that quality cannot fix must not cost the player any quality");
    }

    @Test
    void reportsSaturationWhenTheTargetIsOutOfReach() {
        Simulation sim = new Simulation(
                GovernorConfig.DEFAULT.withTargetFps(240), SyntheticMachine.hopeless(7L));
        GovernorState state = sim.run(6_000);

        assertEquals(GovernorConfig.DEFAULT.withTargetFps(240).minQuality(), state.quality(), 1e-9,
                "quality should bottom out at the configured floor");
        assertEquals(GovernorPhase.SATURATED, state.phase(),
                "the player deserves to be told the target is unreachable, not left guessing");
    }

    @Test
    void neverFallsBelowTheConfiguredFloor() {
        GovernorConfig config = GovernorConfig.DEFAULT.withTargetFps(500).withMinQuality(0.5);
        Simulation sim = new Simulation(config, SyntheticMachine.hopeless(8L));
        sim.run(6_000);
        assertTrue(sim.minQualitySeen >= 0.5 - 1e-9,
                "quality reached " + sim.minQualitySeen + ", below the 0.5 floor");
    }

    @Test
    void disablingRestoresFullQualityImmediately() {
        Simulation sim = new Simulation(GovernorConfig.DEFAULT, SyntheticMachine.modest(9L));
        sim.run(5_000);
        assertNotEquals(1.0, sim.governor.quality(), "fixture sanity: quality should have dropped");

        sim.governor.setEnabled(false);
        assertEquals(QualitySettings.UNTOUCHED, sim.governor.settings());
        assertEquals(GovernorPhase.DISABLED, sim.governor.update(
                sim.recorder.snapshot(), sim.clockNanos, Long.MAX_VALUE).phase());
    }

    @Test
    void doesNothingUntilItHasEnoughFrames() {
        QualityGovernor governor = new QualityGovernor();
        GovernorState state = governor.update(FrameStats.EMPTY, 0L, Long.MAX_VALUE);
        assertEquals(GovernorPhase.WARMUP, state.phase());
        assertEquals(1.0, state.quality());
    }

    private static GovernorConfig config() {
        return GovernorConfig.DEFAULT;
    }

    @Test
    void survivesAStalledClock() {
        // A paused game or a suspended laptop can hand the governor an enormous delta. It must not
        // integrate that into a single catastrophic quality cut.
        Simulation sim = new Simulation(GovernorConfig.DEFAULT, SyntheticMachine.modest(10L));
        sim.run(600);
        double before = sim.governor.quality();

        FrameStats slow = new FrameStats(
                sim.recorder.frameCount(), 500, 40_000, 40_000, 45_000, 50_000, 60_000, 45_000, 0);
        GovernorState state = sim.governor.update(slow, sim.clockNanos + 3_600_000_000_000L, Long.MAX_VALUE);

        assertTrue(before - state.quality() <= config().maxDropStep() + 1e-9,
                "a one hour clock jump cut quality by " + (before - state.quality())
                        + ", more than a single step");
    }
}
