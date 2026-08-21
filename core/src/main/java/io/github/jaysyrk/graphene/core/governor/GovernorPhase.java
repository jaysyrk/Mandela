package io.github.jaysyrk.graphene.core.governor;

/** What the governor is doing right now. Surfaced on the HUD so the behaviour is never a mystery. */
public enum GovernorPhase {
    /** Not enough frames measured yet to act on. */
    WARMUP,
    /** Frame time is inside the dead band; nothing to do. */
    STEADY,
    /** Over budget: quality is being reduced. */
    DROPPING,
    /** Under budget with room to spare: quality is being restored. */
    RECOVERING,
    /** A frame-time spike was seen recently; holding still rather than reacting to noise. */
    SPIKE_GUARD,
    /** Quality is at its floor and the target is still not being met. */
    SATURATED,
    /** Turned off by the player. */
    DISABLED
}
