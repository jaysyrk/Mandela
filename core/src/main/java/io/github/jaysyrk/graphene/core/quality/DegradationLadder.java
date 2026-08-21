package io.github.jaysyrk.graphene.core.quality;

import static io.github.jaysyrk.graphene.core.quality.ResponseCurve.above;
import static io.github.jaysyrk.graphene.core.quality.ResponseCurve.lerp;
import static io.github.jaysyrk.graphene.core.quality.ResponseCurve.lerpInt;
import static io.github.jaysyrk.graphene.core.quality.ResponseCurve.smoothWindow;

/**
 * Turns the quality scalar into {@link QualitySettings} by walking a fixed ladder of concessions.
 *
 * <p>The ladder is ordered by what a player notices. Rain thinning from a downpour to a drizzle is
 * nearly invisible in motion, so it goes first. Render distance is the thing people set deliberately
 * and stare at, so it goes last and never falls further than {@link #MAX_RENDER_DISTANCE_TRIM}
 * chunks. In between sit particles, block entities, shadows and entity distance, roughly in
 * increasing order of how much they are missed and decreasing order of how much they cost.
 *
 * <p>Windows overlap on purpose. Two knobs easing through the same stretch of the axis share the
 * work, which gets more frame time back per unit of visible change than driving one knob to its
 * limit before touching the next.
 */
public final class DegradationLadder {

    /** Never remove more than this many chunks of render distance, whatever the frame rate. */
    public static final int MAX_RENDER_DISTANCE_TRIM = 6;

    private DegradationLadder() {
    }

    /**
     * Resolves a quality scalar in [0, 1] to concrete settings. Monotonic: every knob is at least as
     * degraded at a lower quality as it is at a higher one.
     */
    public static QualitySettings resolve(double quality) {
        double q = ResponseCurve.clamp01(quality);

        // Tier 1 -- atmosphere. Cheap to lose, expensive to draw in a storm.
        double weather = lerp(smoothWindow(q, 1.00, 0.82), 0.30, 1.0);
        boolean fancyClouds = above(q, 0.86);

        // Tier 2 -- particles. Two windows so density falls fast at first, then bottoms out slowly
        // rather than snapping to zero, which would make explosions read as broken.
        double particleUpper = lerp(smoothWindow(q, 0.92, 0.68), 0.45, 1.0);
        double particleLower = lerp(smoothWindow(q, 0.45, 0.12), 0.10, 1.0);
        double particles = Math.min(particleUpper, particleLower);

        // Tier 3 -- incidental geometry.
        boolean shadows = above(q, 0.60);
        double blockEntityDistance = lerp(smoothWindow(q, 0.88, 0.50), 0.45, 1.0);

        // Tier 4 -- entities. The single biggest win on a busy server or a mob farm.
        double entityUpper = lerp(smoothWindow(q, 0.80, 0.38), 0.45, 1.0);
        double entityLower = lerp(smoothWindow(q, 0.38, 0.00), 0.25, 1.0);
        double entityDistance = Math.min(entityUpper, entityLower);

        // Culling ramps in alongside the distance cuts: as the drawn set shrinks, spend more effort
        // proving individual things are invisible.
        double culling = lerp(smoothWindow(q, 0.85, 0.10), 1.0, 0.0);

        // Tier 5 -- terrain. Last resort, hard floor.
        int trim = lerpInt(smoothWindow(q, 0.35, 0.00), MAX_RENDER_DISTANCE_TRIM, 0);

        return new QualitySettings(
                entityDistance,
                blockEntityDistance,
                particles,
                shadows,
                weather,
                fancyClouds,
                trim,
                culling);
    }
}
