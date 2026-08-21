package io.github.jaysyrk.graphene.core.visibility;

import io.github.jaysyrk.graphene.core.util.IntLongMap;

/**
 * Decides which objects are worth drawing, and how often that decision needs re-checking.
 *
 * <p>Occlusion culling is a trade: proving that an entity is hidden behind terrain costs real work,
 * and doing it for every entity every frame can cost more than just drawing them. Two ideas make the
 * trade pay off.
 *
 * <p><b>Staggering.</b> Visibility changes over tens of milliseconds, not between frames, so there is
 * no reason to re-test everything at frame rate. Each object is re-tested every {@code stagger}
 * frames, offset by its id so the work spreads evenly across frames instead of arriving as a
 * periodic hitch. Between tests the cached answer stands.
 *
 * <p><b>Grace frames.</b> Occlusion tests are conservative and slightly noisy near edges; an object
 * grazing the corner of a wall can flip visibility repeatedly. Culling the instant a test says
 * "hidden" turns that noise into flicker, which is far more distracting than the frames it saves. So
 * becoming visible takes effect immediately, and becoming hidden has to persist for
 * {@code graceFrames} first. The asymmetry is the point: a wrongly drawn object costs a little time,
 * a wrongly culled one is a visible bug.
 *
 * <p>Both knobs are driven by {@code cullingAggression} from the governor, so culling effort rises
 * only as the frame budget tightens.
 *
 * <p>Callers follow a fixed protocol each frame: {@link #beginFrame} once, then {@link #poll} for
 * every candidate, and {@link #submit} for exactly those that polled {@link Visibility#TEST}. The
 * counters and the staggering both assume that shape -- polling without submitting leaves the entry
 * looking overdue forever, which quietly turns staggering off.
 *
 * <p>Not thread safe; this belongs to the render thread.
 */
public final class VisibilityCache implements IntLongMap.EntryPredicate {

    /** Re-test interval at zero aggression. One test every 8 frames is background noise. */
    public static final int MAX_STAGGER = 8;
    /** Grace period at zero aggression, in frames: long enough that culling effectively never bites. */
    public static final int MAX_GRACE_FRAMES = 24;
    /** Entries untouched for this long are assumed gone and dropped. */
    private static final int EVICT_AFTER_FRAMES = 600;
    /** How often the eviction sweep runs. */
    private static final int EVICT_INTERVAL_FRAMES = 300;

    private final IntLongMap state;

    private int frame;
    private int graceFrames = MAX_GRACE_FRAMES;
    private int stagger = MAX_STAGGER;

    private int considered;
    private int culled;
    private int tested;
    private int lastConsidered;
    private int lastCulled;
    private int lastTested;

    public VisibilityCache() {
        this(256);
    }

    public VisibilityCache(int expectedObjects) {
        this.state = new IntLongMap(expectedObjects, 0.6f);
    }

    /**
     * Starts a frame and sets how hard to cull.
     *
     * @param cullingAggression 0 leaves visibility effectively untouched, 1 culls as soon as the
     *                          evidence allows
     */
    public void beginFrame(double cullingAggression) {
        double a = cullingAggression < 0 ? 0 : (cullingAggression > 1 ? 1 : cullingAggression);
        frame++;
        lastConsidered = considered;
        lastCulled = culled;
        lastTested = tested;
        considered = 0;
        culled = 0;
        tested = 0;

        // Both tighten together: more aggression means fresher tests and less benefit of the doubt.
        graceFrames = (int) Math.round(MAX_GRACE_FRAMES * (1.0 - a));
        stagger = Math.max(1, (int) Math.round(MAX_STAGGER * (1.0 - a) + 1.0 * a));

        if (frame % EVICT_INTERVAL_FRAMES == 0) {
            state.removeIf(this);
        }
    }

    /**
     * Asks what to do with an object without doing any work.
     *
     * @param id a stable per-object id, typically the entity network id
     * @return {@link Visibility#TEST} if the caller should run its occlusion test and pass the result
     *         to {@link #submit}, otherwise the cached decision
     */
    public Visibility poll(int id) {
        considered++;
        long packed = state.get(id, ABSENT);
        if (packed == ABSENT) {
            return Visibility.TEST;
        }
        int lastTestFrame = unpackTestFrame(packed);
        // Subtraction rather than comparison, so a wrapped frame counter still yields a sane age.
        if (frame - lastTestFrame >= staggerFor(id)) {
            return Visibility.TEST;
        }
        boolean render = withinGrace(unpackVisibleFrame(packed));
        if (!render) {
            culled++;
        }
        return render ? Visibility.RENDER : Visibility.CULL;
    }

    /**
     * Records the outcome of an occlusion test.
     *
     * @return whether the object should be drawn this frame, which may still be true for an object
     *         the test found hidden if it was visible within the grace period
     */
    public boolean submit(int id, boolean visible) {
        tested++;
        long packed = state.get(id, ABSENT);
        int lastVisibleFrame;
        if (visible) {
            lastVisibleFrame = frame;
        } else if (packed == ABSENT) {
            // Never seen and not visible: nothing to grant grace against.
            lastVisibleFrame = frame - graceFrames - 1;
        } else {
            lastVisibleFrame = unpackVisibleFrame(packed);
        }
        state.put(id, pack(lastVisibleFrame, frame));

        boolean render = visible || withinGrace(lastVisibleFrame);
        if (!render) {
            culled++;
        }
        return render;
    }

    /** Forgets an object, e.g. when an entity is removed from the world. */
    public void forget(int id) {
        state.remove(id);
    }

    private boolean withinGrace(int lastVisibleFrame) {
        return frame - lastVisibleFrame <= graceFrames;
    }

    /**
     * Spreads re-tests across frames. Offsetting by the id means a thousand entities do not all fall
     * due on the same frame, which would show up as a periodic stutter at exactly the stagger rate.
     */
    private int staggerFor(int id) {
        if (stagger <= 1) {
            return 1;
        }
        int offset = (id & 0x7fffffff) % stagger;
        int phase = ((frame - offset) % stagger + stagger) % stagger;
        return phase == 0 ? 1 : stagger;
    }

    @Override
    public boolean test(int key, long value) {
        return frame - unpackTestFrame(value) < EVICT_AFTER_FRAMES;
    }

    // -- stats, for the HUD ------------------------------------------------------------------

    /** Objects examined in the previous complete frame. */
    public int consideredLastFrame() {
        return lastConsidered;
    }

    /** Objects skipped in the previous complete frame. */
    public int culledLastFrame() {
        return lastCulled;
    }

    /** Occlusion tests actually run in the previous complete frame. */
    public int testedLastFrame() {
        return lastTested;
    }

    /** Fraction of examined objects that were skipped last frame, in [0, 1]. */
    public double cullRatio() {
        return lastConsidered == 0 ? 0.0 : (double) lastCulled / lastConsidered;
    }

    public int trackedObjects() {
        return state.size();
    }

    public int currentGraceFrames() {
        return graceFrames;
    }

    public int currentStagger() {
        return stagger;
    }

    public void clear() {
        state.clear();
        considered = culled = tested = 0;
        lastConsidered = lastCulled = lastTested = 0;
    }

    // -- packing -----------------------------------------------------------------------------

    private static final long ABSENT = Long.MIN_VALUE;

    private static long pack(int lastVisibleFrame, int lastTestFrame) {
        return ((long) lastVisibleFrame << 32) | (lastTestFrame & 0xffffffffL);
    }

    private static int unpackVisibleFrame(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackTestFrame(long packed) {
        return (int) packed;
    }
}
