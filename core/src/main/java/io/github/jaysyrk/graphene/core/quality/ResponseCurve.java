package io.github.jaysyrk.graphene.core.quality;

/**
 * Maps the single quality scalar onto one setting's range.
 *
 * <p>Every knob Graphene controls is driven from one number in [0, 1]. What makes that work rather
 * than turning everything down at once is that each knob only responds inside its own slice of the
 * axis -- its window. Weather thins out in the top slice, particles in the next, entity distance
 * below that, render distance only at the bottom. The order of those windows is the whole design
 * opinion of the mod, and expressing it as data keeps it arguable.
 *
 * <p>Windows are written high-to-low because that is the direction quality actually travels under
 * load: {@code window(q, 0.9, 0.7)} is fully on at q&nbsp;&ge;&nbsp;0.9 and fully off at
 * q&nbsp;&le;&nbsp;0.7.
 */
public final class ResponseCurve {

    private ResponseCurve() {
    }

    /**
     * Position within a degradation window, where 1 means untouched and 0 means fully degraded.
     *
     * @param quality the global quality scalar
     * @param high    quality at or above which this knob is left alone
     * @param low     quality at or below which this knob is fully degraded
     */
    public static double window(double quality, double high, double low) {
        if (high <= low) {
            throw new IllegalArgumentException("window high (" + high + ") must exceed low (" + low + ")");
        }
        if (quality >= high) {
            return 1.0;
        }
        if (quality <= low) {
            return 0.0;
        }
        return (quality - low) / (high - low);
    }

    /**
     * Like {@link #window} but eased, so a knob does not start moving the instant quality dips into
     * its window. Smoothstep removes the corner at each end of the window, which is what stops a
     * governor hovering on a window boundary from producing visible pulsing.
     */
    public static double smoothWindow(double quality, double high, double low) {
        double t = window(quality, high, low);
        return t * t * (3.0 - 2.0 * t);
    }

    public static double lerp(double t, double atZero, double atOne) {
        return atZero + (atOne - atZero) * clamp01(t);
    }

    /** Interpolates and rounds to an integer setting, e.g. a chunk count. */
    public static int lerpInt(double t, int atZero, int atOne) {
        return (int) Math.round(lerp(t, atZero, atOne));
    }

    /** Turns a knob off once quality falls below {@code threshold}. */
    public static boolean above(double quality, double threshold) {
        return quality >= threshold;
    }

    public static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    public static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }
}
