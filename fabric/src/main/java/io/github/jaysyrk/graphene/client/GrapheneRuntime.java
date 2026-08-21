package io.github.jaysyrk.graphene.client;

import io.github.jaysyrk.graphene.Graphene;
import io.github.jaysyrk.graphene.core.config.GrapheneConfig;
import io.github.jaysyrk.graphene.core.governor.GovernorState;
import io.github.jaysyrk.graphene.core.governor.QualityGovernor;
import io.github.jaysyrk.graphene.core.idle.IdlePolicy;
import io.github.jaysyrk.graphene.core.idle.WindowState;
import io.github.jaysyrk.graphene.core.quality.QualitySettings;
import io.github.jaysyrk.graphene.core.stats.FrameStats;
import io.github.jaysyrk.graphene.core.stats.FrameTimeRecorder;
import io.github.jaysyrk.graphene.core.visibility.RenderBudget;
import io.github.jaysyrk.graphene.core.visibility.VisibilityCache;
import io.github.jaysyrk.graphene.tune.AutotuneSession;
import net.minecraft.client.Minecraft;

/**
 * Wires the engine to the game and owns everything that lives for one frame.
 *
 * <p>All state that mixins read is settled once, at the top of the frame, and then held constant
 * until the frame ends. Letting mixins ask the governor for a fresh answer whenever they happened to
 * run would mean one frame rendered with two different sets of settings -- entities culled at one
 * distance and block entities at another -- which shows up as tearing between systems rather than as
 * a clean quality change.
 *
 * <p>Everything here belongs to the render thread.
 */
public final class GrapheneRuntime {

    /** Frames retained for the HUD graph. About four seconds at 60fps. */
    private static final int GRAPH_SAMPLES = 240;

    private final FrameTimeRecorder recorder = new FrameTimeRecorder(GRAPH_SAMPLES);
    private final QualityGovernor governor = new QualityGovernor();
    private final VisibilityCache entityVisibility = new VisibilityCache(512);
    private final RenderBudget blockEntityBudget = new RenderBudget();
    private final SectionVisibility sections = new SectionVisibility();
    private final IdlePolicy idlePolicy = new IdlePolicy();
    private final AutotuneSession autotune = new AutotuneSession(this);

    private GrapheneConfig config = new GrapheneConfig();

    private long frameStartNanos;
    private boolean frameOpen;
    private QualitySettings settings = QualitySettings.UNTOUCHED;
    private FrameStats stats = FrameStats.EMPTY;
    private double entityDrawDistance = 64.0;
    private long lastInputMillis = System.currentTimeMillis();

    public GrapheneRuntime() {
        applyConfig(config);
    }

    // -- lifecycle ----------------------------------------------------------------------------

    /** Called at the top of the render pass, before anything is drawn. */
    public void beginFrame() {
        frameStartNanos = System.nanoTime();
        frameOpen = true;
        settings = resolveSettings();
        entityDrawDistance = computeEntityDrawDistance();
        entityVisibility.beginFrame(settings.cullingAggression());
        blockEntityBudget.beginFrame(blockEntityBudgetForThisFrame());
    }

    /** Called once the frame has been drawn, but before the frame limiter waits. */
    public void endFrame() {
        if (!frameOpen) {
            return;
        }
        frameOpen = false;
        long now = System.nanoTime();
        long durationNanos = now - frameStartNanos;
        recorder.record(durationNanos);
        stats = recorder.snapshot();
        blockEntityBudget.endFrame();

        if (autotune.isRunning()) {
            // While tuning, the governor must not also be moving the settings underneath the
            // measurement -- the trial would be measuring two things at once.
            autotune.onFrameMeasured(durationNanos);
            return;
        }
        if (config.isEnabled() && config.getMode() == GrapheneConfig.Mode.ADAPTIVE) {
            governor.update(stats, now, recorder.framesSinceSpike());
        }
    }

    /**
     * Whether the frame timing measured excludes the frame limiter's wait.
     *
     * <p>This matters more than it looks. If the measurement included the sleep that vsync or a
     * frame cap imposes, then a capped machine would always measure exactly at its cap, the governor
     * would read no headroom, and quality could fall but never come back. Timing the render pass
     * rather than the whole loop is what keeps the control signal honest under a cap.
     */
    public boolean measuresRenderWorkOnly() {
        return true;
    }

    private QualitySettings resolveSettings() {
        if (!config.isEnabled()) {
            return QualitySettings.UNTOUCHED;
        }
        if (autotune.isRunning()) {
            return autotune.settingsUnderTest();
        }
        return switch (config.getMode()) {
            case ADAPTIVE -> governor.settings();
            case FIXED -> config.getFixedProfile();
            case OBSERVE -> QualitySettings.UNTOUCHED;
        };
    }

    /**
     * Vanilla's entity draw distance, resolved once per frame.
     *
     * <p>Reading it per entity instead would be a boxed {@code Double} out of an {@code
     * OptionInstance} for every entity in the world, every frame -- garbage generated by the code
     * whose job is to reduce garbage. It cannot change mid-frame, so once is enough.
     */
    private double computeEntityDrawDistance() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) {
            return 64.0;
        }
        double chunks = client.options.getEffectiveRenderDistance();
        double scaling = client.options.entityDistanceScaling().get();
        return Math.max(16.0, chunks * 16.0 * scaling);
    }

    /** The distance beyond which entities are not drawn, before the quality scale is applied. */
    public double entityDrawDistance() {
        return entityDrawDistance;
    }

    private int blockEntityBudgetForThisFrame() {
        if (!config.isEnabled() || !config.isBlockEntityBudgetEnabled()) {
            return Integer.MAX_VALUE;
        }
        // The configured budget is what full quality allows; it tightens as quality falls, so the
        // cap and the distance scale pull in the same direction instead of fighting.
        double scale = 0.35 + 0.65 * settings.blockEntityDistanceScale();
        return Math.max(16, (int) (config.getBlockEntityBudget() * scale));
    }

    // -- state the mixins read ----------------------------------------------------------------

    /** Whether Graphene should be changing anything right now. */
    public boolean isActive() {
        if (!config.isEnabled()) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        return client != null && client.level != null;
    }

    /** The settings in force for this frame. Never null. */
    public QualitySettings settings() {
        return settings;
    }

    public VisibilityCache entityVisibility() {
        return entityVisibility;
    }

    public RenderBudget blockEntityBudget() {
        return blockEntityBudget;
    }

    public SectionVisibility sections() {
        return sections;
    }

    public boolean isEntityCullingEnabled() {
        return config.isEnabled() && config.isEntityCullingEnabled();
    }

    public boolean isBlockEntityBudgetEnabled() {
        return config.isEnabled() && config.isBlockEntityBudgetEnabled();
    }

    // -- idle throttling ----------------------------------------------------------------------

    /** Records that the player did something, which cancels idle throttling. */
    public void onInput() {
        lastInputMillis = System.currentTimeMillis();
    }

    /**
     * The frame cap Graphene wants, or {@link IdlePolicy#UNCAPPED}.
     *
     * <p>Vanilla already throttles a minimised window and a long-idle session. This adds the case
     * vanilla does not cover -- visible but not focused -- and makes the thresholds configurable,
     * then defers to whichever cap is lower so it can only ever ask for less work, never more.
     */
    public int idleFrameCap() {
        if (!config.isEnabled() || !config.getIdle().enabled()) {
            return IdlePolicy.UNCAPPED;
        }
        return idlePolicy.frameCapFor(windowState(), System.currentTimeMillis() - lastInputMillis);
    }

    private WindowState windowState() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return WindowState.ACTIVE;
        }
        if (client.getWindow() != null && client.getWindow().isIconified()) {
            return WindowState.HIDDEN;
        }
        if (!client.isWindowActive()) {
            return WindowState.UNFOCUSED;
        }
        if (client.screen != null) {
            return WindowState.MENU;
        }
        return WindowState.ACTIVE;
    }

    // -- accessors for the HUD, commands and tuner --------------------------------------------

    public FrameStats stats() {
        return stats;
    }

    public FrameTimeRecorder recorder() {
        return recorder;
    }

    public QualityGovernor governor() {
        return governor;
    }

    public GovernorState governorState() {
        return governor.state();
    }

    public AutotuneSession autotune() {
        return autotune;
    }

    public GrapheneConfig config() {
        return config;
    }

    /** Replaces the configuration and pushes the parts of it the engine caches. */
    public void applyConfig(GrapheneConfig config) {
        this.config = java.util.Objects.requireNonNull(config, "config");
        governor.setConfig(config.toGovernorConfig());
        governor.setEnabled(config.isEnabled() && config.getMode() == GrapheneConfig.Mode.ADAPTIVE);
        idlePolicy.setConfig(config.getIdle());
        blockEntityBudget.reset();
        entityVisibility.clear();
        Graphene.LOGGER.info("Configuration applied: mode={}, target={}fps, enabled={}",
                config.getMode(), config.getTargetFps(), config.isEnabled());
    }

    /** Drops every cached decision, e.g. when the player changes world. */
    public void onWorldChanged() {
        recorder.reset();
        governor.reset();
        entityVisibility.clear();
        blockEntityBudget.reset();
        sections.clear();
        autotune.cancel();
    }
}
