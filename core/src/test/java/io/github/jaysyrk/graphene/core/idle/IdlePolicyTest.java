package io.github.jaysyrk.graphene.core.idle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IdlePolicyTest {

    private final IdlePolicy policy = new IdlePolicy();

    @Test
    void doesNotCapActivePlay() {
        assertEquals(IdlePolicy.UNCAPPED, policy.frameCapFor(WindowState.ACTIVE, 0));
        assertFalse(policy.isThrottling(WindowState.ACTIVE, 0));
    }

    @Test
    void capsHarderTheLessTheWindowIsWatched() {
        int menu = policy.frameCapFor(WindowState.MENU, 0);
        int unfocused = policy.frameCapFor(WindowState.UNFOCUSED, 0);
        int hidden = policy.frameCapFor(WindowState.HIDDEN, 0);

        assertTrue(menu > unfocused, "a menu is still being looked at: " + menu + " vs " + unfocused);
        assertTrue(unfocused > hidden, "nothing minimised is being looked at at all");
        assertTrue(hidden > 0, "a minimised window should tick over, not stop");
    }

    @Test
    void throttlesAfterTheIdleTimeout() {
        IdleConfig config = IdleConfig.DEFAULT;
        assertEquals(IdlePolicy.UNCAPPED,
                policy.frameCapFor(WindowState.ACTIVE, config.idleAfterMillis() - 1));
        assertEquals(config.idleFps(),
                policy.frameCapFor(WindowState.ACTIVE, config.idleAfterMillis()));
    }

    @Test
    void anyInputCancelsIdleThrottling() {
        assertEquals(IdleConfig.DEFAULT.idleFps(), policy.frameCapFor(WindowState.ACTIVE, 600_000));
        assertEquals(IdlePolicy.UNCAPPED, policy.frameCapFor(WindowState.ACTIVE, 0));
    }

    @Test
    void disablingRemovesEveryCap() {
        IdlePolicy off = new IdlePolicy(IdleConfig.OFF);
        for (WindowState state : WindowState.values()) {
            assertEquals(IdlePolicy.UNCAPPED, off.frameCapFor(state, 10_000_000),
                    "state " + state + " should be uncapped");
        }
    }

    @Test
    void zeroTimeoutDisablesIdleDetectionOnly() {
        IdlePolicy custom = new IdlePolicy(new IdleConfig(true, 60, 30, 5, 30, 0L));
        assertEquals(IdlePolicy.UNCAPPED, custom.frameCapFor(WindowState.ACTIVE, 10_000_000));
        assertEquals(5, custom.frameCapFor(WindowState.HIDDEN, 0), "other caps still apply");
    }

    @Test
    void clampsNonsensicalCaps() {
        IdleConfig config = new IdleConfig(true, -5, 0, 99_999, 30, -1_000L);
        assertEquals(IdlePolicy.UNCAPPED, config.menuFps());
        assertEquals(1000, config.hiddenFps());
        assertEquals(0L, config.idleAfterMillis());
    }

    @Test
    void configCanBeSwappedAtRuntime() {
        assertEquals(5, policy.frameCapFor(WindowState.HIDDEN, 0));
        policy.setConfig(new IdleConfig(true, 60, 30, 1, 30, 60_000L));
        assertEquals(1, policy.frameCapFor(WindowState.HIDDEN, 0));
    }
}
