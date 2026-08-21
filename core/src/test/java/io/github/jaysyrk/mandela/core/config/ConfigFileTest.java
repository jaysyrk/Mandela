package io.github.jaysyrk.mandela.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jaysyrk.mandela.core.idle.IdleConfig;
import io.github.jaysyrk.mandela.core.quality.QualitySettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFileTest {

    @Test
    void returnsDefaultsWhenThereIsNoFile(@TempDir Path dir) throws IOException {
        MandelaConfig config = new ConfigFile().load(dir.resolve("absent.properties"));
        assertTrue(config.isEnabled());
        assertEquals(MandelaConfig.Mode.ADAPTIVE, config.getMode());
        assertEquals(60, config.getTargetFps());
    }

    @Test
    void roundTripsEverySetting(@TempDir Path dir) throws IOException {
        MandelaConfig original = new MandelaConfig();
        original.setEnabled(false);
        original.setMode(MandelaConfig.Mode.FIXED);
        original.setHudMode(MandelaConfig.HudMode.FULL);
        original.setTargetFps(144);
        original.setMinQuality(0.4);
        original.setEntityCullingEnabled(false);
        original.setBlockEntityBudgetEnabled(false);
        original.setBlockEntityBudget(512);
        original.setIdle(new IdleConfig(false, 45, 20, 3, 15, 30_000L));
        original.setFixedProfile(new QualitySettings(0.6, 0.7, 0.55, false, 0.25, false, 3, 0.8));

        Path path = dir.resolve("mandela.properties");
        ConfigFile io = new ConfigFile();
        io.save(path, original);
        MandelaConfig loaded = io.load(path);

        assertTrue(io.warnings().isEmpty(), "a file we just wrote should reload cleanly: " + io.warnings());
        assertEquals(original.isEnabled(), loaded.isEnabled());
        assertEquals(original.getMode(), loaded.getMode());
        assertEquals(original.getHudMode(), loaded.getHudMode());
        assertEquals(original.getTargetFps(), loaded.getTargetFps());
        assertEquals(original.getMinQuality(), loaded.getMinQuality(), 1e-3);
        assertEquals(original.isEntityCullingEnabled(), loaded.isEntityCullingEnabled());
        assertEquals(original.isBlockEntityBudgetEnabled(), loaded.isBlockEntityBudgetEnabled());
        assertEquals(original.getBlockEntityBudget(), loaded.getBlockEntityBudget());
        assertEquals(original.getIdle(), loaded.getIdle());

        QualitySettings profile = loaded.getFixedProfile();
        assertEquals(0.6, profile.entityDistanceScale(), 1e-3);
        assertEquals(0.55, profile.particleDensity(), 1e-3);
        assertFalse(profile.entityShadows());
        assertEquals(3, profile.renderDistanceTrim());
        assertEquals(0.8, profile.cullingAggression(), 1e-3);
    }

    @Test
    void writingTwiceProducesIdenticalText(@TempDir Path dir) throws IOException {
        ConfigFile io = new ConfigFile();
        MandelaConfig config = new MandelaConfig();
        Path path = dir.resolve("mandela.properties");

        io.save(path, config);
        String first = Files.readString(path, StandardCharsets.UTF_8);
        io.save(path, io.load(path));
        String second = Files.readString(path, StandardCharsets.UTF_8);

        assertEquals(first, second, "a load-save cycle must not churn the file");
    }

    /** A player who mistypes one line should get the game, not a crash. */
    @Test
    void survivesGarbageAndSaysWhatItIgnored() {
        ConfigFile io = new ConfigFile();
        MandelaConfig config = io.parseText("""
                enabled = yes please
                target_fps = sixty
                min_quality = NaN
                mode = TURBO
                hud = FULL
                this line has no equals sign
                block_entity_budget = 300
                """);

        assertTrue(config.isEnabled(), "a bad boolean should fall back, not flip");
        assertEquals(60, config.getTargetFps());
        assertEquals(0.25, config.getMinQuality(), 1e-9);
        assertEquals(MandelaConfig.Mode.ADAPTIVE, config.getMode());
        assertEquals(MandelaConfig.HudMode.FULL, config.getHudMode(), "valid lines must still apply");
        assertEquals(300, config.getBlockEntityBudget());
        assertEquals(5, io.warnings().size(), "every ignored line should be reported: " + io.warnings());
    }

    @Test
    void clampsValuesOutOfRange() {
        ConfigFile io = new ConfigFile();
        MandelaConfig config = io.parseText("""
                target_fps = 100000
                min_quality = 5.0
                block_entity_budget = 1
                idle_hidden_fps = -20
                """);
        assertEquals(1000, config.getTargetFps());
        assertEquals(1.0, config.getMinQuality());
        assertEquals(16, config.getBlockEntityBudget());
        assertEquals(0, config.getIdle().hiddenFps(), "a negative cap means no cap");
    }

    @Test
    void ignoresCommentsBlankLinesAndCase() {
        MandelaConfig config = new ConfigFile().parseText("""
                # a comment

                   TARGET_FPS = 30

                MoDe = observe
                """);
        assertEquals(30, config.getTargetFps());
        assertEquals(MandelaConfig.Mode.OBSERVE, config.getMode());
    }

    @Test
    void createsMissingDirectories(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("nested/deeper/mandela.properties");
        new ConfigFile().save(path, new MandelaConfig());
        assertTrue(Files.exists(path));
    }

    /** A crash mid-write must not be able to leave the player without a config. */
    @Test
    void leavesNoTemporaryFileBehind(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("mandela.properties");
        ConfigFile io = new ConfigFile();
        io.save(path, new MandelaConfig());
        try (var entries = Files.list(dir)) {
            assertEquals(1, entries.count(), "the temporary file should have been moved, not left");
        }
    }

    @Test
    void renderedFileExplainsItself() {
        String text = new ConfigFile().render(new MandelaConfig());
        assertTrue(text.contains("# Mandela configuration"));
        assertTrue(text.contains("target_fps"));
        assertTrue(text.lines().filter(l -> l.startsWith("# ")).count() > 8,
                "a hand-edited file needs comments explaining the options");
    }

    @Test
    void governorConfigFollowsTheUserSettings() {
        MandelaConfig config = new MandelaConfig();
        config.setTargetFps(144);
        config.setMinQuality(0.5);
        assertEquals(144, config.toGovernorConfig().targetFps());
        assertEquals(0.5, config.toGovernorConfig().minQuality());
    }
}
