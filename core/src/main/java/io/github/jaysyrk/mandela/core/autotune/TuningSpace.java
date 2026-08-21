package io.github.jaysyrk.mandela.core.autotune;

import io.github.jaysyrk.mandela.core.quality.QualitySettings;

/** Translates between the search's index vectors and the settings the renderer understands. */
public final class TuningSpace {

    private TuningSpace() {
    }

    public static final int AXES = TuningKnob.values().length;

    /** Builds concrete settings from one index per {@link TuningKnob}, in enum order. */
    public static QualitySettings resolve(int[] indices) {
        if (indices.length != AXES) {
            throw new IllegalArgumentException(
                    "expected " + AXES + " indices, got " + indices.length);
        }
        TuningKnob[] knobs = TuningKnob.values();
        return new QualitySettings(
                knobs[TuningKnob.ENTITY_DISTANCE.ordinal()]
                        .valueAt(indices[TuningKnob.ENTITY_DISTANCE.ordinal()]),
                knobs[TuningKnob.BLOCK_ENTITY_DISTANCE.ordinal()]
                        .valueAt(indices[TuningKnob.BLOCK_ENTITY_DISTANCE.ordinal()]),
                knobs[TuningKnob.PARTICLE_DENSITY.ordinal()]
                        .valueAt(indices[TuningKnob.PARTICLE_DENSITY.ordinal()]),
                knobs[TuningKnob.ENTITY_SHADOWS.ordinal()]
                        .valueAt(indices[TuningKnob.ENTITY_SHADOWS.ordinal()]) > 0.5,
                knobs[TuningKnob.WEATHER_DENSITY.ordinal()]
                        .valueAt(indices[TuningKnob.WEATHER_DENSITY.ordinal()]),
                knobs[TuningKnob.FANCY_CLOUDS.ordinal()]
                        .valueAt(indices[TuningKnob.FANCY_CLOUDS.ordinal()]) > 0.5,
                (int) Math.round(knobs[TuningKnob.RENDER_DISTANCE_TRIM.ordinal()]
                        .valueAt(indices[TuningKnob.RENDER_DISTANCE_TRIM.ordinal()])),
                knobs[TuningKnob.CULLING_AGGRESSION.ordinal()]
                        .valueAt(indices[TuningKnob.CULLING_AGGRESSION.ordinal()]));
    }

    /** Describes an index vector in a form fit for a chat message or a log line. */
    public static String describe(int[] indices) {
        StringBuilder sb = new StringBuilder();
        TuningKnob[] knobs = TuningKnob.values();
        for (int i = 0; i < knobs.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(knobs[i].name().toLowerCase(java.util.Locale.ROOT))
              .append('=')
              .append(trim(knobs[i].valueAt(indices[i])));
        }
        return sb.toString();
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? Integer.toString((int) v) : String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
