package io.github.jaysyrk.mandela.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.jaysyrk.mandela.client.MandelaRuntime;
import io.github.jaysyrk.mandela.core.autotune.AutotuneResult;
import io.github.jaysyrk.mandela.core.config.MandelaConfig;
import io.github.jaysyrk.mandela.core.governor.GovernorState;
import io.github.jaysyrk.mandela.core.stats.FrameStats;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * The {@code /mandela} client command.
 *
 * <p>Client-side only, so it works on any server without the server knowing or caring, and it never
 * needs permissions. Every subcommand that changes something persists it immediately, because a
 * setting that silently reverts on the next launch is worse than no setting at all.
 */
public final class MandelaCommand {

    private MandelaCommand() {
    }

    /**
     * @param onChanged called after anything is modified, so the caller can persist it
     */
    public static void register(
            CommandDispatcher<FabricClientCommandSource> dispatcher,
            MandelaRuntime runtime,
            Runnable onChanged) {

        LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommandManager.literal("mandela");

        root.executes(context -> status(context.getSource(), runtime));

        root.then(ClientCommandManager.literal("status")
                .executes(context -> status(context.getSource(), runtime)));

        root.then(ClientCommandManager.literal("on").executes(context -> {
            runtime.config().setEnabled(true);
            runtime.applyConfig(runtime.config());
            onChanged.run();
            return reply(context.getSource(), "Mandela enabled.");
        }));

        root.then(ClientCommandManager.literal("off").executes(context -> {
            runtime.config().setEnabled(false);
            runtime.applyConfig(runtime.config());
            onChanged.run();
            return reply(context.getSource(), "Mandela disabled. Nothing is being changed.");
        }));

        LiteralArgumentBuilder<FabricClientCommandSource> mode = ClientCommandManager.literal("mode");
        for (MandelaConfig.Mode value : MandelaConfig.Mode.values()) {
            mode.then(ClientCommandManager.literal(value.name().toLowerCase(java.util.Locale.ROOT))
                    .executes(context -> {
                        runtime.config().setMode(value);
                        runtime.applyConfig(runtime.config());
                        onChanged.run();
                        return reply(context.getSource(), "Mode set to " + value + ".");
                    }));
        }
        root.then(mode);

        LiteralArgumentBuilder<FabricClientCommandSource> hud = ClientCommandManager.literal("hud");
        for (MandelaConfig.HudMode value : MandelaConfig.HudMode.values()) {
            hud.then(ClientCommandManager.literal(value.name().toLowerCase(java.util.Locale.ROOT))
                    .executes(context -> {
                        runtime.config().setHudMode(value);
                        onChanged.run();
                        return reply(context.getSource(), "Overlay set to " + value + ".");
                    }));
        }
        root.then(hud);

        root.then(ClientCommandManager.literal("target")
                .then(ClientCommandManager.argument("fps", IntegerArgumentType.integer(10, 1000))
                        .executes(context -> {
                            int fps = IntegerArgumentType.getInteger(context, "fps");
                            runtime.config().setTargetFps(fps);
                            runtime.applyConfig(runtime.config());
                            onChanged.run();
                            return reply(context.getSource(), "Target set to " + fps + " fps.");
                        })));

        root.then(ClientCommandManager.literal("autotune")
                .executes(context -> startAutotune(
                        context.getSource(), runtime, runtime.config().getTargetFps(), onChanged))
                .then(ClientCommandManager.argument("fps", IntegerArgumentType.integer(10, 1000))
                        .executes(context -> startAutotune(context.getSource(), runtime,
                                IntegerArgumentType.getInteger(context, "fps"), onChanged))));

        root.then(ClientCommandManager.literal("cancel").executes(context -> {
            runtime.autotune().cancel();
            return reply(context.getSource(), "Stopped.");
        }));

        root.then(ClientCommandManager.literal("reset").executes(context -> {
            runtime.onWorldChanged();
            return reply(context.getSource(), "Measurements and cached decisions cleared.");
        }));

        dispatcher.register(root);
    }

    private static int startAutotune(
            FabricClientCommandSource source, MandelaRuntime runtime, int targetFps, Runnable onChanged) {
        if (runtime.autotune().isRunning()) {
            return reply(source, "Already tuning. Use '/mandela cancel' to stop.");
        }
        boolean started = runtime.autotune().start(targetFps, message -> {
            source.sendFeedback(message);
            onChanged.run();
        });
        if (!started) {
            return reply(source, "Cannot tune from here - load into a world first.");
        }
        return reply(source, String.format(
                "Tuning for %d fps. Stand still and keep looking the same way; this takes a minute, "
                        + "and moving invalidates the measurements it is comparing.", targetFps));
    }

    private static int status(FabricClientCommandSource source, MandelaRuntime runtime) {
        MandelaConfig config = runtime.config();
        FrameStats stats = runtime.stats();
        GovernorState governor = runtime.governorState();

        reply(source, String.format("Mandela: %s, mode %s, target %d fps",
                config.isEnabled() ? "on" : "off", config.getMode(), config.getTargetFps()));
        reply(source, String.format("  %.0f fps average, %.0f fps 1%% low, %d spikes recently",
                stats.meanFps(), stats.onePercentLowFps(), stats.spikesInWindow()));
        long targetMicros = runtime.governor().config().targetFrameMicros();
        double budgetUsed = targetMicros <= 0 ? 0.0 : (double) stats.p95Micros() / targetMicros * 100.0;
        reply(source, String.format("  quality %.0f%% (%s), using %.0f%% of the frame budget",
                runtime.settings().visualScore() * 100,
                config.getMode() == MandelaConfig.Mode.ADAPTIVE ? governor.phase().toString()
                        : config.getMode().toString(),
                budgetUsed));
        reply(source, String.format("  culled %d of %d entities last frame, %d block entities skipped",
                runtime.entityVisibility().culledLastFrame(),
                runtime.entityVisibility().consideredLastFrame(),
                runtime.blockEntityBudget().skippedLastFrame()));

        AutotuneResult tuned = runtime.autotune().result();
        if (tuned != null) {
            reply(source, String.format("  last tune: %.0f fps at %d%% visual quality, %d trials",
                    tuned.achievedFps(), Math.round(tuned.visualScore() * 100), tuned.trialsRun()));
        }
        if (governor.phase() == io.github.jaysyrk.mandela.core.governor.GovernorPhase.SATURATED) {
            reply(source, "  Quality is at its floor and the target is still not being met. "
                    + "Lower the target, or accept the frame rate you have.");
        }
        return 1;
    }

    private static int reply(FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal(message));
        return 1;
    }
}
