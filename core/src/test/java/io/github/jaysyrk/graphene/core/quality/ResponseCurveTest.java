package io.github.jaysyrk.graphene.core.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResponseCurveTest {

    @Test
    void windowIsFullyOnAboveAndFullyOffBelow() {
        assertEquals(1.0, ResponseCurve.window(0.95, 0.9, 0.7));
        assertEquals(1.0, ResponseCurve.window(0.90, 0.9, 0.7));
        assertEquals(0.0, ResponseCurve.window(0.70, 0.9, 0.7));
        assertEquals(0.0, ResponseCurve.window(0.10, 0.9, 0.7));
        assertEquals(0.5, ResponseCurve.window(0.80, 0.9, 0.7), 1e-9);
    }

    @Test
    void windowRejectsAnInvertedRange() {
        assertThrows(IllegalArgumentException.class, () -> ResponseCurve.window(0.5, 0.2, 0.8));
        assertThrows(IllegalArgumentException.class, () -> ResponseCurve.window(0.5, 0.5, 0.5));
    }

    @Test
    void smoothWindowHasNoCornersAtTheEnds() {
        // The derivative at each end should be flat, which is what removes the visible kink as
        // quality drifts across a window boundary.
        double justInside = ResponseCurve.smoothWindow(0.899, 0.9, 0.7);
        double atEdge = ResponseCurve.smoothWindow(0.9, 0.9, 0.7);
        assertTrue(atEdge - justInside < 0.001, "smoothstep should flatten at the top edge");

        double justAbove = ResponseCurve.smoothWindow(0.701, 0.9, 0.7);
        assertTrue(justAbove < 0.001, "smoothstep should flatten at the bottom edge");
    }

    @Test
    void smoothWindowStaysWithinBounds() {
        for (double q = -0.5; q <= 1.5; q += 0.01) {
            double t = ResponseCurve.smoothWindow(q, 0.8, 0.2);
            assertTrue(t >= 0.0 && t <= 1.0, "out of bounds at q=" + q + ": " + t);
        }
    }

    @Test
    void lerpInterpolatesAndClamps() {
        assertEquals(10.0, ResponseCurve.lerp(0.0, 10.0, 20.0));
        assertEquals(20.0, ResponseCurve.lerp(1.0, 10.0, 20.0));
        assertEquals(15.0, ResponseCurve.lerp(0.5, 10.0, 20.0));
        assertEquals(20.0, ResponseCurve.lerp(3.0, 10.0, 20.0), "t must be clamped");
    }

    @Test
    void clampHandlesNaN() {
        assertEquals(0.0, ResponseCurve.clamp01(Double.NaN));
        assertEquals(0.0, ResponseCurve.clamp01(-1.0));
        assertEquals(1.0, ResponseCurve.clamp01(2.0));
    }

    @Test
    void lerpIntRounds() {
        assertEquals(6, ResponseCurve.lerpInt(1.0, 0, 6));
        assertEquals(3, ResponseCurve.lerpInt(0.5, 0, 6));
        assertEquals(0, ResponseCurve.lerpInt(0.0, 0, 6));
    }
}
