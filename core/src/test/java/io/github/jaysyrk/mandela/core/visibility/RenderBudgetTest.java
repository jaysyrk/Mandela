package io.github.jaysyrk.mandela.core.visibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class RenderBudgetTest {

    /** Runs frames of randomly scored candidates and reports the last frame's admissions. */
    private static int settle(RenderBudget budget, int cap, int candidates, int frames, long seed) {
        Random random = new Random(seed);
        int admitted = 0;
        for (int frame = 0; frame < frames; frame++) {
            budget.beginFrame(cap);
            admitted = 0;
            for (int i = 0; i < candidates; i++) {
                if (budget.admit(random.nextDouble())) {
                    admitted++;
                }
            }
            budget.endFrame();
        }
        return admitted;
    }

    @Test
    void admitsEverythingWhenThereIsNoCap() {
        RenderBudget budget = new RenderBudget();
        budget.beginFrame(Integer.MAX_VALUE);
        for (int i = 0; i < 1_000; i++) {
            assertTrue(budget.admit(0.001), "an uncapped frame should turn nothing away");
        }
        budget.endFrame();
    }

    @Test
    void neverExceedsTheCapEvenBeforeItHasAdapted() {
        RenderBudget budget = new RenderBudget();
        budget.beginFrame(50);
        int admitted = 0;
        for (int i = 0; i < 1_000; i++) {
            if (budget.admit(1.0)) {
                admitted++;
            }
        }
        assertEquals(50, admitted, "the hard cap has to hold on the very first frame");
    }

    @Test
    void settlesNearTheBudgetWhenOversubscribed() {
        RenderBudget budget = new RenderBudget();
        int admitted = settle(budget, 200, 1_000, 200, 1L);
        assertTrue(admitted >= 170 && admitted <= 200,
                "settled at " + admitted + " admissions against a budget of 200");
    }

    @Test
    void doesNotThrottleWhenThereIsRoom() {
        RenderBudget budget = new RenderBudget();
        settle(budget, 500, 1_000, 150, 2L);
        int admitted = settle(budget, 500, 100, 60, 3L);
        assertEquals(100, admitted,
                "with only 100 candidates for 500 slots nothing should be rejected");
        assertEquals(0.0, budget.threshold(), 1e-9, "the bar should have come all the way down");
    }

    /**
     * The point of the threshold is that what survives the cap is the important half, not whichever
     * half happened to be offered first.
     */
    @Test
    void keepsTheHigherScoringCandidates() {
        RenderBudget budget = new RenderBudget();
        Random random = new Random(4L);
        settle(budget, 100, 1_000, 200, 5L);

        double admittedTotal = 0;
        double rejectedTotal = 0;
        int admittedCount = 0;
        int rejectedCount = 0;

        for (int frame = 0; frame < 50; frame++) {
            budget.beginFrame(100);
            for (int i = 0; i < 1_000; i++) {
                double score = random.nextDouble();
                if (budget.admit(score)) {
                    admittedTotal += score;
                    admittedCount++;
                } else {
                    rejectedTotal += score;
                    rejectedCount++;
                }
            }
            budget.endFrame();
        }

        double admittedMean = admittedTotal / admittedCount;
        double rejectedMean = rejectedTotal / rejectedCount;
        assertTrue(admittedMean > rejectedMean + 0.3,
                "admitted mean score " + admittedMean + " vs rejected " + rejectedMean
                        + "; the budget is not prioritising, just truncating");
    }

    @Test
    void adaptsWhenTheSceneGetsBusier() {
        RenderBudget budget = new RenderBudget();
        settle(budget, 100, 150, 150, 6L);
        double easyThreshold = budget.threshold();

        settle(budget, 100, 3_000, 250, 7L);
        double busyThreshold = budget.threshold();

        assertTrue(busyThreshold > easyThreshold,
                "the bar should rise when twenty times as many candidates compete: "
                        + easyThreshold + " -> " + busyThreshold);
    }

    @Test
    void recoversWhenTheSceneEmptiesOut() {
        RenderBudget budget = new RenderBudget();
        settle(budget, 100, 3_000, 250, 8L);
        assertTrue(budget.threshold() > 0.3, "fixture sanity: the bar should be high");

        int admitted = settle(budget, 100, 90, 200, 9L);
        assertEquals(90, admitted,
                "after the crowd leaves, every remaining candidate should get through");
    }

    @Test
    void reportsWhatItSkipped() {
        RenderBudget budget = new RenderBudget();
        budget.beginFrame(10);
        for (int i = 0; i < 100; i++) {
            budget.admit(1.0);
        }
        budget.endFrame();
        budget.beginFrame(10);

        assertEquals(100, budget.offeredLastFrame());
        assertEquals(10, budget.admittedLastFrame());
        assertEquals(90, budget.skippedLastFrame());
    }

    @Test
    void aZeroBudgetAdmitsNothing() {
        RenderBudget budget = new RenderBudget();
        budget.beginFrame(0);
        assertFalse(budget.admit(1.0));
        budget.endFrame();
    }

    @Test
    void resetClearsTheLearnedThreshold() {
        RenderBudget budget = new RenderBudget();
        settle(budget, 50, 2_000, 200, 10L);
        assertTrue(budget.threshold() > 0);
        budget.reset();
        assertEquals(0.0, budget.threshold());
    }
}
