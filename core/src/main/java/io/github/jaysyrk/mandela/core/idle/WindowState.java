package io.github.jaysyrk.mandela.core.idle;

/** How much attention the game currently deserves. */
public enum WindowState {
    /** Focused, in the world, being played. */
    ACTIVE,
    /** In the world but sitting in a menu, inventory or pause screen. */
    MENU,
    /** Visible but not focused; the player is reading a wiki on the other monitor. */
    UNFOCUSED,
    /** Minimised or fully hidden. */
    HIDDEN
}
