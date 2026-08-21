package io.github.jaysyrk.mandela.client;

import io.github.jaysyrk.mandela.Mandela;
import io.github.jaysyrk.mandela.command.MandelaCommand;
import io.github.jaysyrk.mandela.core.config.ConfigFile;
import io.github.jaysyrk.mandela.core.config.MandelaConfig;
import io.github.jaysyrk.mandela.hud.DiagnosticsHud;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;

/** Sets the mod up and connects it to the game's lifecycle. */
public final class MandelaClient implements ClientModInitializer {

    private static final String CONFIG_FILE_NAME = "mandela.properties";

    private final ConfigFile configIo = new ConfigFile();
    private MandelaRuntime runtime;

    @Override
    public void onInitializeClient() {
        runtime = new MandelaRuntime();
        runtime.applyConfig(loadConfig());
        Mandela.install(runtime);

        HudRenderCallback.EVENT.register(new DiagnosticsHud(runtime));
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registry) -> MandelaCommand.register(dispatcher, runtime, this::saveConfig));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> saveConfig());

        logStartupAdvice();
    }

    private Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
    }

    private MandelaConfig loadConfig() {
        try {
            MandelaConfig config = configIo.load(configPath());
            for (String warning : configIo.warnings()) {
                Mandela.LOGGER.warn("Config: {}", warning);
            }
            return config;
        } catch (IOException e) {
            // A config that cannot be read is not a reason to stop the game starting.
            Mandela.LOGGER.warn("Could not read the config, continuing with defaults", e);
            return new MandelaConfig();
        }
    }

    /** Writes the current configuration. Safe to call often; failures are logged, never thrown. */
    public void saveConfig() {
        if (runtime == null) {
            return;
        }
        try {
            configIo.save(configPath(), runtime.config());
        } catch (IOException e) {
            Mandela.LOGGER.warn("Could not write the config", e);
        }
    }

    /**
     * Reports the JVM's memory settings once at startup.
     *
     * <p>This is advice, not action. Heap size and garbage collector are launcher settings that a
     * mod cannot change at runtime, and the honest thing is to say what is set and what would be
     * better rather than to pretend otherwise. A heap that is far too large is the usual cause of
     * the multi-second freezes people blame on their hardware, and almost nobody knows to look.
     */
    private void logStartupAdvice() {
        long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        Mandela.LOGGER.info("Max heap: {} MB", maxHeapMb);
        if (maxHeapMb > 8192) {
            Mandela.LOGGER.info(
                    "That is a very large heap for Minecraft. Beyond about 8 GB, collections get "
                            + "longer without getting rarer, which shows up as occasional multi-second "
                            + "freezes. 4-6 GB is usually smoother.");
        } else if (maxHeapMb < 2048) {
            Mandela.LOGGER.info(
                    "That is a small heap. Below about 2 GB the collector runs constantly, which "
                            + "looks like a steady low frame rate rather than distinct stutters.");
        }
    }
}
