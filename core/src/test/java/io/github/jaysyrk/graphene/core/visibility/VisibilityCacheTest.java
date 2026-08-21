package io.github.jaysyrk.graphene.core.visibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VisibilityCacheTest {

    private static final double FULL = 1.0;
    private static final double NONE = 0.0;

    @Test
    void asksForATestOnFirstSight() {
        VisibilityCache cache = new VisibilityCache();
        cache.beginFrame(FULL);
        assertEquals(Visibility.TEST, cache.poll(42), "an unknown object has no cached answer");
    }

    @Test
    void drawsWhatTheTestFinds() {
        VisibilityCache cache = new VisibilityCache();
        cache.beginFrame(FULL);
        assertTrue(cache.submit(42, true));
    }

    @Test
    void cullsImmediatelyAtFullAggression() {
        VisibilityCache cache = new VisibilityCache();
        cache.beginFrame(FULL);
        assertFalse(cache.submit(42, false), "at full aggression there is no benefit of the doubt");
    }

    /**
     * Appearing must be instant and disappearing must not be, because the two mistakes are not
     * equally visible. Drawing something hidden costs a few microseconds; hiding something visible is
     * a bug the player watches happen.
     */
    @Test
    void grantsGraceBeforeCullingSomethingRecentlySeen() {
        VisibilityCache cache = new VisibilityCache(16);
        cache.beginFrame(NONE);
        assertTrue(cache.submit(7, true));

        int grace = cache.currentGraceFrames();
        for (int i = 1; i <= grace; i++) {
            cache.beginFrame(NONE);
            assertTrue(cache.submit(7, false),
                    "frame " + i + " of " + grace + " should still draw on grace");
        }
        cache.beginFrame(NONE);
        assertFalse(cache.submit(7, false), "grace should have run out by now");
    }

    @Test
    void becomingVisibleTakesEffectAtOnce() {
        VisibilityCache cache = new VisibilityCache();
        cache.beginFrame(FULL);
        assertFalse(cache.submit(7, false));
        cache.beginFrame(FULL);
        assertTrue(cache.submit(7, true), "there must be no delay on reappearing");
    }

    @Test
    void aggressionShortensGraceAndTestInterval() {
        VisibilityCache cache = new VisibilityCache();
        cache.beginFrame(NONE);
        int lenientGrace = cache.currentGraceFrames();
        int lenientStagger = cache.currentStagger();

        cache.beginFrame(FULL);
        assertTrue(cache.currentGraceFrames() < lenientGrace,
                "more aggression should mean less benefit of the doubt");
        assertTrue(cache.currentStagger() <= lenientStagger,
                "more aggression should mean fresher tests");
        assertEquals(1, cache.currentStagger(), "at full aggression every frame is tested");
    }

    @Test
    void reusesCachedAnswersBetweenTests() {
        VisibilityCache cache = new VisibilityCache();
        cache.beginFrame(NONE);
        cache.submit(1234, true);

        int reused = 0;
        for (int i = 0; i < 40; i++) {
            cache.beginFrame(NONE);
            if (cache.poll(1234) == Visibility.TEST) {
                cache.submit(1234, true);
            } else {
                reused++;
            }
        }
        assertTrue(reused > 20,
                "only " + reused + " of 40 frames avoided a test; staggering is not saving work");
    }

    /**
     * If every object fell due on the same frame, the saved work would come back as a periodic
     * hitch -- which is worse than the steady cost it replaced.
     */
    @Test
    void spreadsTestsAcrossFrames() {
        VisibilityCache cache = new VisibilityCache(2048);
        int objects = 1_000;

        cache.beginFrame(NONE);
        for (int id = 0; id < objects; id++) {
            cache.submit(id, true);
        }

        int stagger = cache.currentStagger();
        int busiestFrame = 0;
        for (int frame = 0; frame < stagger * 3; frame++) {
            cache.beginFrame(NONE);
            int tests = 0;
            for (int id = 0; id < objects; id++) {
                if (cache.poll(id) == Visibility.TEST) {
                    cache.submit(id, true);
                    tests++;
                }
            }
            busiestFrame = Math.max(busiestFrame, tests);
        }

        double evenShare = (double) objects / stagger;
        assertTrue(busiestFrame < evenShare * 2.0,
                "busiest frame ran " + busiestFrame + " tests against an even share of " + evenShare);
    }

    @Test
    void countsWhatItCulled() {
        VisibilityCache cache = new VisibilityCache();
        cache.beginFrame(FULL);
        for (int id = 0; id < 100; id++) {
            if (cache.poll(id) == Visibility.TEST) {
                cache.submit(id, id % 4 == 0);
            }
        }
        cache.beginFrame(FULL);

        assertEquals(100, cache.consideredLastFrame());
        assertEquals(75, cache.culledLastFrame());
        assertEquals(0.75, cache.cullRatio(), 1e-9);
    }

    @Test
    void forgetsObjectsThatStopBeingOffered() {
        VisibilityCache cache = new VisibilityCache();
        cache.beginFrame(FULL);
        for (int id = 0; id < 500; id++) {
            cache.submit(id, true);
        }
        assertEquals(500, cache.trackedObjects());

        // Nothing is offered again, so after long enough the entries should be swept.
        for (int frame = 0; frame < 1_200; frame++) {
            cache.beginFrame(FULL);
        }
        assertEquals(0, cache.trackedObjects(),
                "entities that left the world must not accumulate for the length of a session");
    }

    @Test
    void forgetRemovesAnObjectAtOnce() {
        VisibilityCache cache = new VisibilityCache();
        cache.beginFrame(FULL);
        cache.submit(9, true);
        assertEquals(1, cache.trackedObjects());
        cache.forget(9);
        assertEquals(0, cache.trackedObjects());
        cache.beginFrame(FULL);
        assertEquals(Visibility.TEST, cache.poll(9));
    }

    @Test
    void handlesNegativeAndLargeIds() {
        VisibilityCache cache = new VisibilityCache();
        cache.beginFrame(NONE);
        for (int id : new int[]{-1, -99_999, Integer.MAX_VALUE, 0}) {
            assertTrue(cache.submit(id, true), "id " + id);
        }
        cache.beginFrame(NONE);
        for (int id : new int[]{-1, -99_999, Integer.MAX_VALUE, 0}) {
            assertNotEquals(Visibility.CULL, cache.poll(id), "id " + id + " lost its cached answer");
        }
    }

    @Test
    void clearResetsEverything() {
        VisibilityCache cache = new VisibilityCache();
        cache.beginFrame(FULL);
        cache.submit(1, true);
        cache.clear();
        assertEquals(0, cache.trackedObjects());
        assertEquals(0, cache.culledLastFrame());
    }
}
