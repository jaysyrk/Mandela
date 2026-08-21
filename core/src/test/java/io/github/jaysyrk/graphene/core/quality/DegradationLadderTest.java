package io.github.jaysyrk.graphene.core.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DegradationLadderTest {

    @Test
    void fullQualityChangesNothing() {
        assertEquals(QualitySettings.UNTOUCHED, DegradationLadder.resolve(1.0));
    }

    @Test
    void clampsOutOfRangeInput() {
        assertEquals(DegradationLadder.resolve(1.0), DegradationLadder.resolve(5.0));
        assertEquals(DegradationLadder.resolve(0.0), DegradationLadder.resolve(-5.0));
        assertEquals(DegradationLadder.resolve(0.0), DegradationLadder.resolve(Double.NaN));
    }

    /**
     * The single most important property. If any knob can get better as quality gets worse, the
     * governor's search is over a surface with a local trap in it, and lowering quality can make the
     * frame rate worse -- which reads to a player as the mod being broken.
     */
    @Test
    void everyKnobDegradesMonotonically() {
        QualitySettings previous = DegradationLadder.resolve(0.0);
        for (double q = 0.0; q <= 1.0001; q += 0.001) {
            QualitySettings current = DegradationLadder.resolve(q);
            assertTrue(current.entityDistanceScale() >= previous.entityDistanceScale() - 1e-9,
                    "entity distance fell as quality rose at q=" + q);
            assertTrue(current.blockEntityDistanceScale() >= previous.blockEntityDistanceScale() - 1e-9,
                    "block entity distance fell as quality rose at q=" + q);
            assertTrue(current.particleDensity() >= previous.particleDensity() - 1e-9,
                    "particle density fell as quality rose at q=" + q);
            assertTrue(current.weatherDensity() >= previous.weatherDensity() - 1e-9,
                    "weather density fell as quality rose at q=" + q);
            assertTrue(current.renderDistanceTrim() <= previous.renderDistanceTrim(),
                    "render distance trim grew as quality rose at q=" + q);
            assertTrue(current.cullingAggression() <= previous.cullingAggression() + 1e-9,
                    "culling got more aggressive as quality rose at q=" + q);
            assertTrue(!previous.entityShadows() || current.entityShadows(),
                    "shadows turned off as quality rose at q=" + q);
            assertTrue(!previous.fancyClouds() || current.fancyClouds(),
                    "clouds simplified as quality rose at q=" + q);
            previous = current;
        }
    }

    @Test
    void visualScoreRisesWithQuality() {
        double previous = -1;
        for (double q = 0.0; q <= 1.0001; q += 0.005) {
            double score = DegradationLadder.resolve(q).visualScore();
            assertTrue(score >= previous - 1e-9, "visual score fell as quality rose at q=" + q);
            previous = score;
        }
        assertEquals(1.0, DegradationLadder.resolve(1.0).visualScore(), 1e-9);
    }

    /** Weather is meant to be the first thing sacrificed and render distance the last. */
    @Test
    void degradesInTheIntendedOrder() {
        QualitySettings justBelowFull = DegradationLadder.resolve(0.90);
        assertTrue(justBelowFull.weatherDensity() < 1.0, "weather should thin out first");
        assertEquals(0, justBelowFull.renderDistanceTrim(), "render distance must not move yet");
        assertEquals(1.0, justBelowFull.entityDistanceScale(), 1e-9,
                "entity distance should still be untouched this high up");

        QualitySettings low = DegradationLadder.resolve(0.15);
        assertTrue(low.entityDistanceScale() < 0.6, "entity distance should be well down by now");
        assertTrue(low.renderDistanceTrim() > 0, "render distance should finally give way");
    }

    @Test
    void neverTrimsRenderDistanceBeyondTheCap() {
        for (double q = 0.0; q <= 1.0; q += 0.001) {
            assertTrue(DegradationLadder.resolve(q).renderDistanceTrim()
                            <= DegradationLadder.MAX_RENDER_DISTANCE_TRIM,
                    "trim exceeded the cap at q=" + q);
        }
    }

    @Test
    void keepsTheWorldRecognisableAtTheFloor() {
        QualitySettings floor = DegradationLadder.resolve(0.0);
        assertTrue(floor.entityDistanceScale() >= 0.2,
                "entities must still be visible at some distance, not switched off");
        assertTrue(floor.particleDensity() > 0.0, "some particles must survive or effects look broken");
        assertFalse(floor.fancyClouds());
        assertEquals(DegradationLadder.MAX_RENDER_DISTANCE_TRIM, floor.renderDistanceTrim());
    }

    @Test
    void producesContinuousSettings() {
        // Smoothstep windows exist so nothing jumps. A discontinuity here shows up in game as a
        // visible pop as quality drifts past a boundary.
        QualitySettings previous = DegradationLadder.resolve(0.0);
        for (double q = 0.001; q <= 1.0; q += 0.001) {
            QualitySettings current = DegradationLadder.resolve(q);
            assertTrue(Math.abs(current.entityDistanceScale() - previous.entityDistanceScale()) < 0.02,
                    "entity distance jumped at q=" + q);
            assertTrue(Math.abs(current.particleDensity() - previous.particleDensity()) < 0.02,
                    "particle density jumped at q=" + q);
            previous = current;
        }
    }
}
