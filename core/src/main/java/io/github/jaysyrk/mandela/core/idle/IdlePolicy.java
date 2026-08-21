package io.github.jaysyrk.mandela.core.idle;

/**
 * Decides how fast to render when nobody is looking.
 *
 * <p>A machine rendering 300 frames a second into a minimised window is heating a room for nothing.
 * The cost is real -- a laptop battery, a fan, and on a shared machine the CPU that everything else
 * wanted -- and the benefit is exactly zero, because no one sees a single one of those frames.
 *
 * <p>The judgement calls worth stating: a menu is still capped but generously, because a player
 * scrolling an inventory notices sluggishness. Losing focus caps harder, but not to nothing, since
 * people watch their game while doing something else. Minimised drops to a trickle rather than
 * stopping, so the connection stays alive and the window redraws instantly when it comes back.
 * Idling while focused -- afk at a farm -- is treated gently, because any input at all cancels it
 * and the player will not perceive the ramp.
 */
public final class IdlePolicy {

    /** Returned when no cap applies. */
    public static final int UNCAPPED = 0;

    private IdleConfig config;

    public IdlePolicy() {
        this(IdleConfig.DEFAULT);
    }

    public IdlePolicy(IdleConfig config) {
        this.config = java.util.Objects.requireNonNull(config, "config");
    }

    /**
     * The frame rate cap to apply right now.
     *
     * @param state             what the window is doing
     * @param millisSinceInput  time since the last keyboard, mouse or controller input
     * @return a cap in frames per second, or {@link #UNCAPPED}
     */
    public int frameCapFor(WindowState state, long millisSinceInput) {
        if (!config.enabled()) {
            return UNCAPPED;
        }
        return switch (state) {
            case HIDDEN -> config.hiddenFps();
            case UNFOCUSED -> config.unfocusedFps();
            case MENU -> config.menuFps();
            case ACTIVE -> activeCap(millisSinceInput);
        };
    }

    private int activeCap(long millisSinceInput) {
        if (config.idleAfterMillis() <= 0) {
            return UNCAPPED;
        }
        return millisSinceInput >= config.idleAfterMillis() ? config.idleFps() : UNCAPPED;
    }

    /** True when the cap in force is one the player might want explained on the HUD. */
    public boolean isThrottling(WindowState state, long millisSinceInput) {
        return frameCapFor(state, millisSinceInput) != UNCAPPED;
    }

    public IdleConfig config() {
        return config;
    }

    public void setConfig(IdleConfig config) {
        this.config = java.util.Objects.requireNonNull(config, "config");
    }
}
