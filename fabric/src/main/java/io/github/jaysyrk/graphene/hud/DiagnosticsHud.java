package io.github.jaysyrk.graphene.hud;

import io.github.jaysyrk.graphene.client.GrapheneRuntime;
import io.github.jaysyrk.graphene.core.config.GrapheneConfig;
import io.github.jaysyrk.graphene.core.governor.GovernorState;
import io.github.jaysyrk.graphene.core.stats.FrameStats;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws what Graphene is doing and why.
 *
 * <p>An adaptive system that changes the picture without explaining itself is indistinguishable from
 * a bug. If entity distance drops as someone walks into a village, they should be able to see that
 * it was a deliberate response to the frame budget and not their game breaking -- so the overlay
 * always names the phase the governor is in, not just the numbers.
 *
 * <p>The frame-time graph is the part worth reading. A frame rate counter is an average and hides
 * exactly the thing that makes a game feel bad; a trace of the last few seconds shows the spikes as
 * spikes, with the target drawn across it as a line to clear.
 */
public final class DiagnosticsHud implements HudRenderCallback {

    private static final int MARGIN = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int GRAPH_WIDTH = 240;
    private static final int GRAPH_HEIGHT = 40;

    private static final int COLOUR_TEXT = 0xFFE8E8E8;
    private static final int COLOUR_GOOD = 0xFF66DD66;
    private static final int COLOUR_WARN = 0xFFE8C86A;
    private static final int COLOUR_BAD = 0xFFE87A6A;
    private static final int COLOUR_PANEL = 0x90000000;
    private static final int COLOUR_TARGET_LINE = 0xFF5A9BD5;

    private final GrapheneRuntime runtime;
    private final long[] samples = new long[GRAPH_WIDTH];

    public DiagnosticsHud(GrapheneRuntime runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public void onHudRender(GuiGraphics graphics, DeltaTracker deltaTracker) {
        GrapheneConfig.HudMode mode = runtime.config().getHudMode();
        if (mode == GrapheneConfig.HudMode.OFF) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options.hideGui || client.getDebugOverlay().showDebugScreen()) {
            // The debug screen already covers this ground; two overlays on top of each other help
            // nobody.
            return;
        }

        FrameStats stats = runtime.stats();
        GovernorState governor = runtime.governorState();
        int y = MARGIN;

        y = drawSummary(graphics, stats, governor, y);
        if (mode == GrapheneConfig.HudMode.FULL) {
            y = drawDetail(graphics, stats, governor, y);
            drawGraph(graphics, y);
        }
    }

    private int drawSummary(GuiGraphics graphics, FrameStats stats, GovernorState governor, int y) {
        String headline = String.format(
                "Graphene  %.0f fps  |  1%% low %.0f  |  %s",
                stats.meanFps(), stats.onePercentLowFps(), statusWord(governor));
        drawLine(graphics, headline, y, colourForPhase(governor));
        return y + LINE_HEIGHT;
    }

    /**
     * How much of the frame budget the measured frame time is using.
     *
     * <p>Computed from the statistics rather than read off the governor, because the governor only
     * runs in adaptive mode and would report zero in the other two -- turning the one number an
     * observing player is there to read into a flat lie.
     */
    private double budgetUsedPercent(FrameStats stats) {
        long target = runtime.governor().config().targetFrameMicros();
        return target <= 0 ? 0.0 : (double) stats.p95Micros() / target * 100.0;
    }

    /**
     * What to call the current state.
     *
     * <p>The governor only runs in adaptive mode, so reporting its phase in the other two would show
     * a stale or disabled controller and read as a fault. In those modes the mode itself is the
     * honest answer.
     */
    private String statusWord(GovernorState governor) {
        GrapheneConfig config = runtime.config();
        if (!config.isEnabled()) {
            return "off";
        }
        return switch (config.getMode()) {
            case ADAPTIVE -> governor.phase().toString();
            case FIXED -> "FIXED profile";
            case OBSERVE -> "OBSERVING (changing nothing)";
        };
    }

    private int drawDetail(GuiGraphics graphics, FrameStats stats, GovernorState governor, int y) {
        drawLine(graphics, String.format(
                "quality %.0f%%  |  budget used %.0f%%  |  frame %.1f ms (p95 %.1f, p99 %.1f)",
                runtime.settings().visualScore() * 100,
                budgetUsedPercent(stats),
                FrameStats.microsToMillis(stats.meanMicros()),
                FrameStats.microsToMillis(stats.p95Micros()),
                FrameStats.microsToMillis(stats.p99Micros())), y, COLOUR_TEXT);
        y += LINE_HEIGHT;

        drawLine(graphics, String.format(
                "entities %d/%d culled (%.0f%%)  |  block entities %d skipped  |  sections %d",
                runtime.entityVisibility().culledLastFrame(),
                runtime.entityVisibility().consideredLastFrame(),
                runtime.entityVisibility().cullRatio() * 100,
                runtime.blockEntityBudget().skippedLastFrame(),
                runtime.sections().visibleSectionCount()), y, COLOUR_TEXT);
        y += LINE_HEIGHT;

        if (stats.spikesInWindow() > 0) {
            drawLine(graphics, String.format(
                    "%d frame spikes recently (worst %.0f ms) - these are hitches, not load",
                    stats.spikesInWindow(),
                    FrameStats.microsToMillis(runtime.recorder().lastSpikeMicros())),
                    y, COLOUR_WARN);
            y += LINE_HEIGHT;
        }
        if (runtime.autotune().isRunning()) {
            drawLine(graphics, String.format("autotuning %.0f%% - stay still",
                    runtime.autotune().progress() * 100), y, COLOUR_WARN);
            y += LINE_HEIGHT;
        }
        return y + 2;
    }

    /**
     * Plots recent frame times against the target.
     *
     * <p>The vertical scale is fixed at three times the target rather than fitted to the data.
     * Autoscaling would make a perfectly smooth trace and a disastrous one look identical, which is
     * the opposite of what a graph is for.
     */
    private void drawGraph(GuiGraphics graphics, int top) {
        int count = runtime.recorder().copyRecent(samples);
        if (count < 2) {
            return;
        }
        long targetMicros = runtime.governor().config().targetFrameMicros();
        long scaleMicros = targetMicros * 3;

        int left = MARGIN;
        graphics.fill(left, top, left + GRAPH_WIDTH, top + GRAPH_HEIGHT, COLOUR_PANEL);

        int targetY = top + GRAPH_HEIGHT - (int) ((double) targetMicros / scaleMicros * GRAPH_HEIGHT);
        graphics.fill(left, targetY, left + GRAPH_WIDTH, targetY + 1, COLOUR_TARGET_LINE);

        int firstColumn = GRAPH_WIDTH - count;
        for (int i = 0; i < count; i++) {
            long micros = samples[i];
            int height = (int) Math.min(GRAPH_HEIGHT, (double) micros / scaleMicros * GRAPH_HEIGHT);
            int x = left + firstColumn + i;
            int colour = micros <= targetMicros ? COLOUR_GOOD
                    : micros <= targetMicros * 2 ? COLOUR_WARN : COLOUR_BAD;
            graphics.fill(x, top + GRAPH_HEIGHT - height, x + 1, top + GRAPH_HEIGHT, colour);
        }
    }

    private void drawLine(GuiGraphics graphics, String text, int y, int colour) {
        Minecraft client = Minecraft.getInstance();
        int width = client.font.width(text);
        graphics.fill(MARGIN - 2, y - 1, MARGIN + width + 2, y + LINE_HEIGHT - 2, COLOUR_PANEL);
        graphics.drawString(client.font, text, MARGIN, y, colour, false);
    }

    private int colourForPhase(GovernorState governor) {
        if (runtime.config().getMode() != GrapheneConfig.Mode.ADAPTIVE) {
            return COLOUR_TEXT;
        }
        return switch (governor.phase()) {
            case SATURATED -> COLOUR_BAD;
            case DROPPING, SPIKE_GUARD -> COLOUR_WARN;
            default -> governor.targetMet() ? COLOUR_GOOD : COLOUR_WARN;
        };
    }
}
