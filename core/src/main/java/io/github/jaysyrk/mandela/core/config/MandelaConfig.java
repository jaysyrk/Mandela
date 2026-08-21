package io.github.jaysyrk.mandela.core.config;

import io.github.jaysyrk.mandela.core.governor.GovernorConfig;
import io.github.jaysyrk.mandela.core.idle.IdleConfig;
import io.github.jaysyrk.mandela.core.quality.QualitySettings;

/**
 * Everything the player can change, in one mutable object.
 *
 * <p>Deliberately a plain bean with no serialisation framework behind it. The core module has no
 * dependencies, and a config format that a player can open in a text editor and understand is worth
 * more here than one that round-trips a nested object graph. {@link ConfigFile} does the reading and
 * writing.
 */
public final class MandelaConfig {

    /** How the resolved settings are chosen. */
    public enum Mode {
        /** The governor steers quality continuously against the frame-rate target. */
        ADAPTIVE,
        /** A fixed profile, normally the one autotune found. */
        FIXED,
        /** Mandela measures and reports but changes nothing. */
        OBSERVE
    }

    /** How much of the diagnostic overlay to draw. */
    public enum HudMode {
        OFF,
        /** One line: frame rate, 1% low, and what the governor is doing. */
        COMPACT,
        /** Adds the frame-time graph, culling counters and memory. */
        FULL
    }

    private boolean enabled = true;
    private Mode mode = Mode.ADAPTIVE;
    private HudMode hudMode = HudMode.OFF;

    private int targetFps = 60;
    private double minQuality = 0.25;

    private boolean entityCullingEnabled = true;
    private boolean blockEntityBudgetEnabled = true;
    private int blockEntityBudget = 256;

    private IdleConfig idle = IdleConfig.DEFAULT;

    /** The profile used in {@link Mode#FIXED}; normally written by autotune. */
    private QualitySettings fixedProfile = QualitySettings.UNTOUCHED;

    public GovernorConfig toGovernorConfig() {
        return GovernorConfig.DEFAULT.withTargetFps(targetFps).withMinQuality(minQuality);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = java.util.Objects.requireNonNull(mode, "mode");
    }

    public HudMode getHudMode() {
        return hudMode;
    }

    public void setHudMode(HudMode hudMode) {
        this.hudMode = java.util.Objects.requireNonNull(hudMode, "hudMode");
    }

    public int getTargetFps() {
        return targetFps;
    }

    public void setTargetFps(int targetFps) {
        this.targetFps = Math.max(10, Math.min(1000, targetFps));
    }

    public double getMinQuality() {
        return minQuality;
    }

    public void setMinQuality(double minQuality) {
        this.minQuality = Math.max(0.0, Math.min(1.0, minQuality));
    }

    public boolean isEntityCullingEnabled() {
        return entityCullingEnabled;
    }

    public void setEntityCullingEnabled(boolean entityCullingEnabled) {
        this.entityCullingEnabled = entityCullingEnabled;
    }

    public boolean isBlockEntityBudgetEnabled() {
        return blockEntityBudgetEnabled;
    }

    public void setBlockEntityBudgetEnabled(boolean blockEntityBudgetEnabled) {
        this.blockEntityBudgetEnabled = blockEntityBudgetEnabled;
    }

    public int getBlockEntityBudget() {
        return blockEntityBudget;
    }

    public void setBlockEntityBudget(int blockEntityBudget) {
        this.blockEntityBudget = Math.max(16, Math.min(8192, blockEntityBudget));
    }

    public IdleConfig getIdle() {
        return idle;
    }

    public void setIdle(IdleConfig idle) {
        this.idle = java.util.Objects.requireNonNull(idle, "idle");
    }

    public QualitySettings getFixedProfile() {
        return fixedProfile;
    }

    public void setFixedProfile(QualitySettings fixedProfile) {
        this.fixedProfile = java.util.Objects.requireNonNull(fixedProfile, "fixedProfile");
    }
}
