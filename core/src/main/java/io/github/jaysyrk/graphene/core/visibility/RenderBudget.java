package io.github.jaysyrk.graphene.core.visibility;

/**
 * Caps how many optional objects are drawn per frame, keeping the most important ones.
 *
 * <p>The obvious implementation -- collect every candidate, sort by importance, take the top N -- is
 * the wrong shape for a render loop: it needs the whole set up front, and it allocates and sorts
 * every frame. Candidates instead arrive one at a time and have to be answered immediately.
 *
 * <p>So this keeps a score threshold and adapts it across frames. If last frame admitted more than
 * the budget, the bar goes up; if it admitted comfortably fewer, the bar comes down. After a few
 * frames the threshold sits at roughly the score of the Nth best candidate, and admission
 * approximates "keep the top N" at O(1) per candidate with no allocation and no ordering
 * requirement. A hard cap backs it up so a sudden change in the scene cannot blow the budget while
 * the threshold catches up.
 *
 * <p>The approximation is worth being honest about: within a frame it is still first-come-first-served
 * among candidates above the bar. The bar is what does the prioritising, and it is a frame behind.
 * For a scene that changes smoothly -- which is what a camera moving through a world produces -- that
 * lag is invisible.
 */
public final class RenderBudget {

    /** How fast the threshold chases the budget. Higher converges sooner and overshoots more. */
    private static final double ADAPT_RATE = 0.35;
    private static final double MAX_THRESHOLD = 0.98;

    private double threshold;
    private int budget = Integer.MAX_VALUE;
    private int admitted;
    private int offered;
    private int overflow;
    private int lastAdmitted;
    private int lastOffered;

    /**
     * Starts a frame.
     *
     * @param budget how many objects may be admitted; use {@link Integer#MAX_VALUE} for no limit
     */
    public void beginFrame(int budget) {
        this.budget = Math.max(0, budget);
        lastAdmitted = admitted;
        lastOffered = offered;
        admitted = 0;
        offered = 0;
        overflow = 0;
    }

    /**
     * Offers one candidate.
     *
     * @param score importance in [0, 1]; larger wins. Screen-space size over distance is a good
     *              proxy, since it is exactly "how much of the frame does this object account for"
     * @return whether to draw it
     */
    public boolean admit(double score) {
        offered++;
        if (score < threshold) {
            return false;
        }
        if (admitted >= budget) {
            // Cleared the bar but there was no room. This is the signal the bar is set too low,
            // and it is the only one available -- the hard cap means admissions can never run over
            // the budget, so counting admissions alone would show no pressure at all.
            overflow++;
            return false;
        }
        admitted++;
        return true;
    }

    /**
     * Closes the frame and moves the threshold towards one that would have exactly filled the budget.
     *
     * <p>The controller works in rate space rather than on the scores themselves: it compares the
     * fraction of candidates that cleared the bar against the fraction the budget can afford, and
     * corrects the difference. That makes it independent of how scores happen to be distributed --
     * it converges on the right quantile whether importance is spread evenly or bunched up.
     */
    public void endFrame() {
        if (budget == Integer.MAX_VALUE || offered == 0) {
            threshold = 0.0;
            return;
        }
        if (offered <= budget) {
            // Everything fits. A bar with nothing to keep out is only a way to drop things for free.
            threshold = 0.0;
            return;
        }
        double passRate = (double) (admitted + overflow) / offered;
        double affordableRate = (double) budget / offered;
        double error = passRate - affordableRate;
        threshold = Math.max(0.0, Math.min(MAX_THRESHOLD, threshold + ADAPT_RATE * error));
    }

    /** The score a candidate currently has to beat. */
    public double threshold() {
        return threshold;
    }

    public int admittedLastFrame() {
        return lastAdmitted;
    }

    public int offeredLastFrame() {
        return lastOffered;
    }

    /** Objects skipped in the previous complete frame because of the budget. */
    public int skippedLastFrame() {
        return Math.max(0, lastOffered - lastAdmitted);
    }

    public void reset() {
        threshold = 0.0;
        admitted = 0;
        offered = 0;
        overflow = 0;
        lastAdmitted = 0;
        lastOffered = 0;
        budget = Integer.MAX_VALUE;
    }
}
