package io.github.jaysyrk.graphene.core.idle;

/**
 * Frame caps for each kind of inattention. A cap of {@link IdlePolicy#UNCAPPED} disables that case.
 *
 * @param enabled          master switch
 * @param menuFps          cap while a screen is open
 * @param unfocusedFps     cap while the window is visible but not focused
 * @param hiddenFps        cap while the window is minimised
 * @param idleFps          cap after the player has been focused but idle for a while
 * @param idleAfterMillis  how long without input counts as idle; 0 disables idle detection
 */
public record IdleConfig(
        boolean enabled,
        int menuFps,
        int unfocusedFps,
        int hiddenFps,
        int idleFps,
        long idleAfterMillis) {

    public static final IdleConfig DEFAULT =
            new IdleConfig(true, 60, 30, 5, 30, 60_000L);

    /** Everything off, for players who want the game to run flat out regardless. */
    public static final IdleConfig OFF =
            new IdleConfig(false, 0, 0, 0, 0, 0L);

    public IdleConfig {
        menuFps = clampFps(menuFps);
        unfocusedFps = clampFps(unfocusedFps);
        hiddenFps = clampFps(hiddenFps);
        idleFps = clampFps(idleFps);
        if (idleAfterMillis < 0) {
            idleAfterMillis = 0;
        }
    }

    private static int clampFps(int fps) {
        if (fps <= 0) {
            return IdlePolicy.UNCAPPED;
        }
        // One frame per second is the floor: below that the window stops feeling alive at all.
        return Math.max(1, Math.min(fps, 1000));
    }
}
