package io.github.jaysyrk.mandela.core.quality;

/**
 * The concrete settings that a quality level resolves to. This is the contract between the engine
 * and the game: everything the governor decides arrives here, and the Minecraft-facing code reads
 * only this record. Keeping it a plain value means the whole control path can be tested without a
 * game attached.
 *
 * @param entityDistanceScale      multiplier on the distance at which entities stop being drawn
 * @param blockEntityDistanceScale the same, for block entities such as chests and signs
 * @param particleDensity          fraction of optional particles allowed to spawn, 0 to 1
 * @param entityShadows            whether the blob shadow under entities is drawn
 * @param weatherDensity           fraction of rain and snow particles drawn, 0 to 1
 * @param fancyClouds              whether clouds render in their volumetric form
 * @param renderDistanceTrim       chunks shaved off the player's chosen render distance
 * @param cullingAggression        0 leaves vanilla visibility alone, 1 culls as hard as is safe
 */
public record QualitySettings(
        double entityDistanceScale,
        double blockEntityDistanceScale,
        double particleDensity,
        boolean entityShadows,
        double weatherDensity,
        boolean fancyClouds,
        int renderDistanceTrim,
        double cullingAggression) {

    /** What the game looks like with Mandela doing nothing at all. */
    public static final QualitySettings UNTOUCHED =
            new QualitySettings(1.0, 1.0, 1.0, true, 1.0, true, 0, 0.0);

    public QualitySettings {
        entityDistanceScale = ResponseCurve.clamp(entityDistanceScale, 0.05, 1.0);
        blockEntityDistanceScale = ResponseCurve.clamp(blockEntityDistanceScale, 0.05, 1.0);
        particleDensity = ResponseCurve.clamp01(particleDensity);
        weatherDensity = ResponseCurve.clamp01(weatherDensity);
        cullingAggression = ResponseCurve.clamp01(cullingAggression);
        if (renderDistanceTrim < 0) {
            renderDistanceTrim = 0;
        }
    }

    /**
     * A rough measure of how much visual fidelity this configuration keeps, in [0, 1]. The autotuner
     * uses it to choose between configurations that both hit the frame-rate target, so the weights
     * encode how much each setting is actually missed: distance is noticed, weather is not.
     */
    public double visualScore() {
        double score = 0.0;
        score += 0.30 * entityDistanceScale;
        score += 0.10 * blockEntityDistanceScale;
        score += 0.15 * particleDensity;
        score += 0.05 * (entityShadows ? 1.0 : 0.0);
        score += 0.05 * weatherDensity;
        score += 0.05 * (fancyClouds ? 1.0 : 0.0);
        score += 0.25 * Math.max(0.0, 1.0 - renderDistanceTrim / 8.0);
        score += 0.05 * (1.0 - cullingAggression);
        return ResponseCurve.clamp01(score);
    }
}
