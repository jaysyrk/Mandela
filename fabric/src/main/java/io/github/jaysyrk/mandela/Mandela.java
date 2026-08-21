package io.github.jaysyrk.mandela;

import io.github.jaysyrk.mandela.client.MandelaRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static entry point for the mixins.
 *
 * <p>Mixin code runs in the middle of the render loop and cannot be handed dependencies, so it needs
 * somewhere to look the engine up. Everything here is null-safe and cheap: a mixin that fires before
 * the mod has initialised, or after a crash has torn the runtime down, gets vanilla behaviour rather
 * than an exception inside the renderer.
 */
public final class Mandela {

    public static final String MOD_ID = "mandela";
    public static final Logger LOGGER = LoggerFactory.getLogger("Mandela");

    private static volatile MandelaRuntime runtime;

    private Mandela() {
    }

    /** The live engine, or null before initialisation. Callers must handle null. */
    public static MandelaRuntime runtime() {
        return runtime;
    }

    /**
     * The engine only if it is initialised, enabled and in a state where changing what is drawn is
     * safe. Every mixin gates on this, so a single flag turns the whole mod inert.
     */
    public static MandelaRuntime active() {
        MandelaRuntime current = runtime;
        return current != null && current.isActive() ? current : null;
    }

    /** Installs the engine. Called once during client initialisation. */
    public static void install(MandelaRuntime instance) {
        runtime = instance;
    }
}
