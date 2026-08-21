package io.github.jaysyrk.graphene;

import io.github.jaysyrk.graphene.client.GrapheneRuntime;
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
public final class Graphene {

    public static final String MOD_ID = "graphene";
    public static final Logger LOGGER = LoggerFactory.getLogger("Graphene");

    private static volatile GrapheneRuntime runtime;

    private Graphene() {
    }

    /** The live engine, or null before initialisation. Callers must handle null. */
    public static GrapheneRuntime runtime() {
        return runtime;
    }

    /**
     * The engine only if it is initialised, enabled and in a state where changing what is drawn is
     * safe. Every mixin gates on this, so a single flag turns the whole mod inert.
     */
    public static GrapheneRuntime active() {
        GrapheneRuntime current = runtime;
        return current != null && current.isActive() ? current : null;
    }

    /** Installs the engine. Called once during client initialisation. */
    public static void install(GrapheneRuntime instance) {
        runtime = instance;
    }
}
