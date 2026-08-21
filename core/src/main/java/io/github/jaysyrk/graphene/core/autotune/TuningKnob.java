package io.github.jaysyrk.graphene.core.autotune;

/**
 * One axis the autotuner can search, as an ordered ladder from cheapest to prettiest.
 *
 * <p>Index 0 is always the setting that costs the least to draw, and the last index is always
 * untouched vanilla. That ordering is what lets the search start from a configuration it can assume
 * is fast and climb, rather than starting from vanilla and hoping to find its way down.
 *
 * <p>{@code visualWeight} is how much of the total perceived quality this axis accounts for; the
 * weights across all knobs sum to 1. They decide the order the search tries to buy quality back in,
 * which matters because the search stops as soon as the frame budget runs out -- so whatever is
 * weighted highest is what the player keeps.
 */
public enum TuningKnob {

    /** Distance multiplier for entities. The biggest single lever on a crowded server. */
    ENTITY_DISTANCE(new double[]{0.25, 0.40, 0.55, 0.70, 0.85, 1.00}, 0.30),

    /** Distance multiplier for block entities: chests, signs, banners, shulkers. */
    BLOCK_ENTITY_DISTANCE(new double[]{0.40, 0.60, 0.80, 1.00}, 0.10),

    /** Fraction of optional particles allowed to spawn. */
    PARTICLE_DENSITY(new double[]{0.10, 0.30, 0.55, 0.80, 1.00}, 0.15),

    /** Blob shadows under entities. Cheap to lose, and a real cost with many mobs. */
    ENTITY_SHADOWS(new double[]{0.0, 1.0}, 0.05),

    /** Rain and snow particle density. */
    WEATHER_DENSITY(new double[]{0.25, 0.50, 0.75, 1.00}, 0.05),

    /** Volumetric clouds. */
    FANCY_CLOUDS(new double[]{0.0, 1.0}, 0.05),

    /** Chunks shaved off the player's render distance. Descends in value, ascends in quality. */
    RENDER_DISTANCE_TRIM(new double[]{6, 4, 2, 1, 0}, 0.25),

    /** How hard to cull. Also descends in value and ascends in quality. */
    CULLING_AGGRESSION(new double[]{1.00, 0.70, 0.40, 0.00}, 0.05);

    private final double[] values;
    private final double visualWeight;

    TuningKnob(double[] values, double visualWeight) {
        this.values = values;
        this.visualWeight = visualWeight;
    }

    public int levels() {
        return values.length;
    }

    public double valueAt(int index) {
        return values[clampIndex(index)];
    }

    public int clampIndex(int index) {
        if (index < 0) {
            return 0;
        }
        return Math.min(index, values.length - 1);
    }

    public int maxIndex() {
        return values.length - 1;
    }

    public double visualWeight() {
        return visualWeight;
    }

    /** Visual quality bought by moving up one step on this axis. Uniform across the axis's steps. */
    public double gainPerStep() {
        return values.length <= 1 ? 0.0 : visualWeight / (values.length - 1);
    }

    /** The cheapest setting, where every search starts. */
    public static int[] lowestIndices() {
        return new int[values().length];
    }

    /** Vanilla: every axis at its top. */
    public static int[] highestIndices() {
        TuningKnob[] knobs = values();
        int[] idx = new int[knobs.length];
        for (int i = 0; i < knobs.length; i++) {
            idx[i] = knobs[i].maxIndex();
        }
        return idx;
    }
}
