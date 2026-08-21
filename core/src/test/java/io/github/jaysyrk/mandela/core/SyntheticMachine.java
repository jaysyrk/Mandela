package io.github.jaysyrk.mandela.core;

import io.github.jaysyrk.mandela.core.quality.QualitySettings;
import java.util.Random;

/**
 * A stand-in for a graphics card, so the control loop and the search can be tested end to end
 * without a game.
 *
 * <p>Frame cost is modelled as a fixed base plus a term per setting. Distance terms are squared
 * because what a distance actually controls is an area, which is the property that makes render
 * distance behave so differently from the linear knobs. Weights are constructor arguments so a test
 * can build a CPU-bound machine and a GPU-bound one and check that the tuner reaches different,
 * correct answers on each -- which is the entire premise of tuning by measurement.
 */
public final class SyntheticMachine {

    private final double baseMicros;
    private final double entityMicros;
    private final double terrainMicros;
    private final double particleMicros;
    private final double blockEntityMicros;
    private final double extrasMicros;
    private final double jitterMicros;
    private final Random random;

    private double sceneLoad = 1.0;

    public SyntheticMachine(double baseMicros, double entityMicros, double terrainMicros,
                            double particleMicros, double blockEntityMicros, double extrasMicros,
                            double jitterMicros, long seed) {
        this.baseMicros = baseMicros;
        this.entityMicros = entityMicros;
        this.terrainMicros = terrainMicros;
        this.particleMicros = particleMicros;
        this.blockEntityMicros = blockEntityMicros;
        this.extrasMicros = extrasMicros;
        this.jitterMicros = jitterMicros;
        this.random = new Random(seed);
    }

    /** A machine that comfortably exceeds 60fps at full quality. */
    public static SyntheticMachine fast(long seed) {
        return new SyntheticMachine(3_000, 2_000, 2_500, 500, 400, 300, 200, seed);
    }

    /** A machine that needs real concessions to hold 60fps. */
    public static SyntheticMachine modest(long seed) {
        return new SyntheticMachine(6_000, 9_000, 7_000, 2_500, 1_500, 1_200, 400, seed);
    }

    /** A machine that cannot hit 60fps even with everything turned off. */
    public static SyntheticMachine hopeless(long seed) {
        return new SyntheticMachine(30_000, 9_000, 7_000, 2_500, 1_500, 1_200, 500, seed);
    }

    /** Scales every variable cost, standing in for walking into somewhere busy. */
    public void setSceneLoad(double sceneLoad) {
        this.sceneLoad = sceneLoad;
    }

    /** Frame time in microseconds for the given settings. */
    public long frameMicros(QualitySettings s) {
        double terrainFraction = Math.max(0.0, 1.0 - s.renderDistanceTrim() / 12.0);
        double variable = 0.0;
        variable += entityMicros * square(s.entityDistanceScale()) * (1.0 - 0.35 * s.cullingAggression());
        variable += terrainMicros * square(terrainFraction);
        variable += particleMicros * s.particleDensity();
        variable += blockEntityMicros * square(s.blockEntityDistanceScale());
        variable += extrasMicros * (0.4 * s.weatherDensity()
                + (s.fancyClouds() ? 0.3 : 0.0)
                + (s.entityShadows() ? 0.3 : 0.0));

        double total = baseMicros + variable * sceneLoad;
        total += (random.nextDouble() - 0.5) * 2.0 * jitterMicros;
        return (long) Math.max(500, total);
    }

    /** Frame time in nanoseconds, which is what the recorder consumes. */
    public long frameNanos(QualitySettings s) {
        return frameMicros(s) * 1_000L;
    }

    private static double square(double v) {
        return v * v;
    }
}
