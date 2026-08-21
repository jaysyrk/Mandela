package io.github.jaysyrk.mandela.core.autotune;

import io.github.jaysyrk.mandela.core.quality.QualitySettings;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the best-looking settings this machine can actually sustain, by measuring instead of guessing.
 *
 * <p>Every performance guide, preset and "optimised config" answers the wrong question. They rank
 * settings by how expensive they are in general, when what matters is how expensive they are on one
 * particular GPU, at one particular resolution, with one particular set of other mods installed, in
 * the kind of place that player actually spends their time. Those differ enough that general advice
 * is often exactly backwards -- render distance is nearly free on a machine that is CPU-bound on
 * entities, and ruinous on the machine next to it.
 *
 * <p>So this measures. The search is a budgeted knapsack solved greedily against measured prices:
 *
 * <ol>
 *   <li><b>Baseline.</b> Measure the cheapest configuration. If even that misses the target, there
 *       is nothing to search and the player is told so.
 *   <li><b>Probe.</b> Measure what one step up each axis actually costs on this machine. This is the
 *       part that cannot be replaced by a preset, and it is why the search takes a minute rather
 *       than a moment.
 *   <li><b>Ascend.</b> Repeatedly buy whichever step offers the most perceived quality per
 *       microsecond of frame time, re-measuring after each purchase so the prices stay current. A
 *       step that no longer fits the budget closes its axis.
 * </ol>
 *
 * <p>Choosing by quality per microsecond rather than by quality alone is what makes the result
 * hardware-specific. Buying in order of how much a setting is missed sounds right and behaves badly:
 * on a machine that is bottlenecked on terrain it spends the entire budget on render distance and
 * has nothing left for entity distance, which on that machine was nearly free. Dividing by the
 * measured price is what stops the search from paying most for what happens to be most expensive.
 *
 * <p>Closing an axis after a single failure assumes each axis gets monotonically more expensive as
 * it climbs, which is true of all of them here. Since the ascent buys cheapest-per-quality first,
 * the budget only ever tightens, so a step that cannot be afforded now will not become affordable
 * later in the same search.
 *
 * <p>The class is a pure state machine: it never reads a clock, never touches a renderer, and never
 * blocks. The caller pulls {@link #currentTrial()}, applies it, measures it, and pushes the result
 * back with {@link #submit}. That is what lets the search be driven by a real render loop in the
 * game and by a synthetic performance model under test.
 */
public final class Autotuner {

    /** What the search is doing. */
    public enum Phase {
        /** Not started. */
        IDLE,
        /** Measuring the cheapest configuration, to check the target is reachable at all. */
        BASELINE,
        /** Measuring what one step up each axis costs on this machine. */
        PROBING,
        /** Buying quality back, one step at a time. */
        ASCENDING,
        /** Finished; {@link #result()} is available. */
        DONE,
        /** Stopped by the caller. */
        CANCELLED
    }

    private final AutotuneConfig config;

    private Phase phase = Phase.IDLE;
    private int[] best;
    private int[] candidate;
    private boolean[] closed;
    private TuningKnob pending;
    private Trial trial;
    private int trialsRun;
    private int retriesOnCurrent;
    private int probeAxis;
    /** Measured price of one step up each axis, in microseconds of frame time. */
    private double[] stepCostMicros;
    private long baselineP99Micros;
    private long bestP99Micros;
    private boolean targetReached;
    private final List<String> log = new ArrayList<>();
    private AutotuneResult result;

    public Autotuner() {
        this(AutotuneConfig.DEFAULT);
    }

    public Autotuner(AutotuneConfig config) {
        this.config = java.util.Objects.requireNonNull(config, "config");
    }

    /** Begins a search, discarding any previous one. */
    public void start() {
        best = TuningKnob.lowestIndices();
        candidate = best.clone();
        closed = new boolean[TuningSpace.AXES];
        stepCostMicros = new double[TuningSpace.AXES];
        pending = null;
        probeAxis = 0;
        trialsRun = 0;
        retriesOnCurrent = 0;
        baselineP99Micros = 0;
        bestP99Micros = 0;
        targetReached = false;
        result = null;
        log.clear();
        phase = Phase.BASELINE;
        trial = buildTrial(candidate, "baseline (cheapest settings)");
    }

    /**
     * The configuration awaiting measurement, or null when the search is not running.
     * The same instance is returned until {@link #submit} is called.
     */
    public Trial currentTrial() {
        return isRunning() ? trial : null;
    }

    /**
     * Reports what a trial measured and advances the search.
     *
     * <p>An invalid result is retried rather than believed. A sample taken while the player walked
     * into a different biome says nothing about the setting under test, and folding it in would make
     * the search converge on noise.
     */
    public void submit(TrialResult measurement) {
        if (!isRunning()) {
            throw new IllegalStateException("no trial is awaiting a result (phase=" + phase + ")");
        }
        java.util.Objects.requireNonNull(measurement, "measurement");

        if (!measurement.valid()) {
            if (retriesOnCurrent < config.maxRetries()) {
                retriesOnCurrent++;
                log.add(String.format("trial %d: discarded (unstable scene), retrying %d/%d",
                        trial.sequence(), retriesOnCurrent, config.maxRetries()));
                return;
            }
            log.add(String.format("trial %d: discarded and out of retries; finishing early",
                    trial.sequence()));
            finish();
            return;
        }

        retriesOnCurrent = 0;
        trialsRun++;
        boolean feasible = measurement.p99Micros() <= config.budgetMicros();

        switch (phase) {
            case BASELINE -> handleBaseline(measurement, feasible);
            case PROBING -> handleProbe(measurement);
            default -> handleAscent(measurement, feasible);
        }
    }

    private void handleBaseline(TrialResult measurement, boolean feasible) {
        targetReached = feasible;
        bestP99Micros = measurement.p99Micros();
        baselineP99Micros = measurement.p99Micros();
        log.add(String.format("trial %d: baseline %.1f fps at p99 (%s)",
                trial.sequence(), fps(measurement.p99Micros()),
                feasible ? "target met" : "TARGET UNREACHABLE on this machine"));
        if (!feasible) {
            // Nothing above this is any cheaper, so there is nothing to search. Report honestly
            // rather than spending a minute proving it.
            finish();
            return;
        }
        phase = Phase.PROBING;
        probeAxis = -1;
        nextProbe();
    }

    /** Queues the next price check, or moves on to spending once every axis has one. */
    private void nextProbe() {
        TuningKnob[] knobs = TuningKnob.values();
        for (int i = probeAxis + 1; i < knobs.length; i++) {
            if (knobs[i].maxIndex() > 0) {
                probeAxis = i;
                pending = knobs[i];
                candidate = best.clone();
                candidate[i]++;
                trial = buildTrial(candidate,
                        "price of " + knobs[i].name().toLowerCase(java.util.Locale.ROOT));
                return;
            }
            stepCostMicros[i] = Double.MAX_VALUE;
            closed[i] = true;
        }
        phase = Phase.ASCENDING;
        candidate = best.clone();
        nextAscentStep();
    }

    private void handleProbe(TrialResult measurement) {
        int axis = pending.ordinal();
        // A step that measures as free is priced at one microsecond rather than zero, both to avoid
        // dividing by it and because nothing is genuinely free.
        stepCostMicros[axis] = Math.max(1.0, measurement.p99Micros() - baselineP99Micros);
        log.add(String.format("trial %d: %s step costs %.2f ms",
                trial.sequence(), pending.name().toLowerCase(java.util.Locale.ROOT),
                stepCostMicros[axis] / 1000.0));
        if (trialsRun >= config.maxTrials()) {
            log.add("trial budget exhausted during pricing; keeping the cheapest configuration");
            finish();
            return;
        }
        nextProbe();
    }

    private void handleAscent(TrialResult measurement, boolean feasible) {
        String knobName = pending.name().toLowerCase(java.util.Locale.ROOT);
        if (feasible) {
            // Re-price from what this step actually cost, so the estimate tracks the curve as the
            // axis climbs rather than staying stuck at what the first step cost.
            stepCostMicros[pending.ordinal()] =
                    Math.max(1.0, measurement.p99Micros() - bestP99Micros);
            best = candidate.clone();
            bestP99Micros = measurement.p99Micros();
            log.add(String.format("trial %d: %s -> %s kept, %.1f fps at p99",
                    trial.sequence(), knobName,
                    trimValue(pending.valueAt(candidate[pending.ordinal()])),
                    fps(measurement.p99Micros())));
        } else {
            closed[pending.ordinal()] = true;
            candidate = best.clone();
            log.add(String.format("trial %d: %s -> %s too expensive (%.1f fps at p99), axis closed",
                    trial.sequence(), knobName,
                    trimValue(pending.valueAt(best[pending.ordinal()] + 1)),
                    fps(measurement.p99Micros())));
        }
        nextAscentStep();
    }

    /** Picks the next raise to attempt, or ends the search when there is nothing left worth trying. */
    private void nextAscentStep() {
        if (trialsRun >= config.maxTrials()) {
            log.add("trial budget exhausted; keeping the best configuration found");
            finish();
            return;
        }
        TuningKnob choice = null;
        double bestDensity = -1.0;
        for (TuningKnob knob : TuningKnob.values()) {
            int i = knob.ordinal();
            if (closed[i] || best[i] >= knob.maxIndex()) {
                continue;
            }
            double density = knob.gainPerStep() / Math.max(1.0, stepCostMicros[i]);
            if (density > bestDensity) {
                bestDensity = density;
                choice = knob;
            }
        }
        if (choice == null) {
            log.add("no affordable improvements remain");
            finish();
            return;
        }
        pending = choice;
        candidate = best.clone();
        candidate[choice.ordinal()]++;
        trial = buildTrial(candidate, "raise " + choice.name().toLowerCase(java.util.Locale.ROOT));
    }

    private void finish() {
        QualitySettings settings = TuningSpace.resolve(best);
        result = new AutotuneResult(
                settings, best.clone(), trialsRun, bestP99Micros, targetReached, List.copyOf(log));
        phase = Phase.DONE;
        trial = null;
    }

    /** Stops the search. Any partial result is discarded; the caller should restore its settings. */
    public void cancel() {
        if (phase == Phase.DONE || phase == Phase.IDLE) {
            return;
        }
        phase = Phase.CANCELLED;
        trial = null;
        result = null;
    }

    private Trial buildTrial(int[] indices, String label) {
        return new Trial(
                trialsRun + 1,
                indices.clone(),
                TuningSpace.resolve(indices),
                config.warmupFrames(),
                config.measureFrames(),
                label);
    }

    public Phase phase() {
        return phase;
    }

    public boolean isRunning() {
        return phase == Phase.BASELINE || phase == Phase.PROBING || phase == Phase.ASCENDING;
    }

    /** The finished result, or null while the search is still running. */
    public AutotuneResult result() {
        return result;
    }

    public int trialsRun() {
        return trialsRun;
    }

    /**
     * Rough progress in [0, 1]. The search has no fixed length -- it ends when it runs out of
     * affordable raises -- so this is the fraction of axes that have been settled, which is honest
     * about being an estimate.
     */
    public double progress() {
        if (phase == Phase.DONE) {
            return 1.0;
        }
        if (phase == Phase.IDLE || phase == Phase.CANCELLED || best == null) {
            return 0.0;
        }
        TuningKnob[] knobs = TuningKnob.values();
        if (phase == Phase.BASELINE) {
            return 0.0;
        }
        if (phase == Phase.PROBING) {
            return 0.05 + 0.35 * (Math.max(0, probeAxis) / (double) knobs.length);
        }
        int settled = 0;
        for (TuningKnob knob : knobs) {
            if (closed[knob.ordinal()] || best[knob.ordinal()] >= knob.maxIndex()) {
                settled++;
            }
        }
        return 0.40 + 0.60 * settled / knobs.length;
    }

    public AutotuneConfig config() {
        return config;
    }

    private static double fps(long micros) {
        return micros <= 0 ? 0.0 : 1_000_000.0 / micros;
    }

    private static String trimValue(double v) {
        return v == Math.rint(v)
                ? Integer.toString((int) v)
                : String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
