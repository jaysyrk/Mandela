package io.github.jaysyrk.graphene.core.config;

import io.github.jaysyrk.graphene.core.idle.IdleConfig;
import io.github.jaysyrk.graphene.core.quality.QualitySettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and writes the config as commented {@code key = value} text.
 *
 * <p>Writes go to a temporary file that is then moved into place, so a crash mid-write leaves the
 * previous config intact rather than a truncated one. Reads never throw on bad input: an
 * unparseable value falls back to its default and is noted in {@link #warnings()}, because a config
 * file with one bad line should not stop the game from starting.
 */
public final class ConfigFile {

    private static final String HEADER = """
            # Graphene configuration
            #
            # Adaptive performance settings for Minecraft. Edited in game with /graphene,
            # or here by hand -- the file is re-read when the game starts.
            """;

    private final List<String> warnings = new ArrayList<>();

    /** Non-fatal problems found by the last {@link #load}. */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** Loads a config, returning defaults if the file does not exist. */
    public GrapheneConfig load(Path path) throws IOException {
        warnings.clear();
        GrapheneConfig config = new GrapheneConfig();
        if (!Files.exists(path)) {
            return config;
        }
        Map<String, String> values = parse(Files.readAllLines(path, StandardCharsets.UTF_8));
        apply(config, values);
        return config;
    }

    /** Parses config text directly. Exposed for tests and for validating pasted settings. */
    public GrapheneConfig parseText(String text) {
        warnings.clear();
        GrapheneConfig config = new GrapheneConfig();
        apply(config, parse(List.of(text.split("\n", -1))));
        return config;
    }

    private Map<String, String> parse(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                warnings.add("ignored line without a key: " + line);
                continue;
            }
            values.put(line.substring(0, eq).trim().toLowerCase(Locale.ROOT),
                    line.substring(eq + 1).trim());
        }
        return values;
    }

    private void apply(GrapheneConfig config, Map<String, String> v) {
        config.setEnabled(bool(v, "enabled", config.isEnabled()));
        config.setMode(enumValue(v, "mode", GrapheneConfig.Mode.class, config.getMode()));
        config.setHudMode(enumValue(v, "hud", GrapheneConfig.HudMode.class, config.getHudMode()));
        config.setTargetFps(integer(v, "target_fps", config.getTargetFps()));
        config.setMinQuality(number(v, "min_quality", config.getMinQuality()));
        config.setEntityCullingEnabled(bool(v, "entity_culling", config.isEntityCullingEnabled()));
        config.setBlockEntityBudgetEnabled(
                bool(v, "block_entity_budget_enabled", config.isBlockEntityBudgetEnabled()));
        config.setBlockEntityBudget(integer(v, "block_entity_budget", config.getBlockEntityBudget()));

        IdleConfig d = config.getIdle();
        config.setIdle(new IdleConfig(
                bool(v, "idle_enabled", d.enabled()),
                integer(v, "idle_menu_fps", d.menuFps()),
                integer(v, "idle_unfocused_fps", d.unfocusedFps()),
                integer(v, "idle_hidden_fps", d.hiddenFps()),
                integer(v, "idle_afk_fps", d.idleFps()),
                integer(v, "idle_afk_after_seconds", (int) (d.idleAfterMillis() / 1000L)) * 1000L));

        QualitySettings p = config.getFixedProfile();
        config.setFixedProfile(new QualitySettings(
                number(v, "profile_entity_distance", p.entityDistanceScale()),
                number(v, "profile_block_entity_distance", p.blockEntityDistanceScale()),
                number(v, "profile_particle_density", p.particleDensity()),
                bool(v, "profile_entity_shadows", p.entityShadows()),
                number(v, "profile_weather_density", p.weatherDensity()),
                bool(v, "profile_fancy_clouds", p.fancyClouds()),
                integer(v, "profile_render_distance_trim", p.renderDistanceTrim()),
                number(v, "profile_culling_aggression", p.cullingAggression())));
    }

    /** Writes the config, creating parent directories as needed. */
    public void save(Path path, GrapheneConfig config) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String text = render(config);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Some filesystems, notably a few network mounts, cannot do this. A non-atomic move is
            // still better than writing over the original in place.
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Renders the config to text. Deterministic, so writing an unchanged config is a no-op diff. */
    public String render(GrapheneConfig c) {
        StringBuilder sb = new StringBuilder(HEADER);
        sb.append('\n');

        section(sb, "General");
        comment(sb, "Master switch. When false Graphene does nothing at all.");
        entry(sb, "enabled", c.isEnabled());
        comment(sb, "ADAPTIVE steers quality to hold target_fps. FIXED uses the profile below.");
        comment(sb, "OBSERVE measures and reports without changing anything.");
        entry(sb, "mode", c.getMode());
        comment(sb, "Diagnostic overlay: OFF, COMPACT or FULL.");
        entry(sb, "hud", c.getHudMode());

        section(sb, "Adaptive quality");
        comment(sb, "The frame rate the governor steers towards.");
        entry(sb, "target_fps", c.getTargetFps());
        comment(sb, "How far quality may fall, 0 to 1. Raise this if the game gets too bare.");
        entry(sb, "min_quality", c.getMinQuality());

        section(sb, "Culling");
        comment(sb, "Skip drawing entities that are proven to be hidden behind terrain.");
        entry(sb, "entity_culling", c.isEntityCullingEnabled());
        comment(sb, "Cap how many block entities (chests, signs, banners) draw per frame.");
        entry(sb, "block_entity_budget_enabled", c.isBlockEntityBudgetEnabled());
        entry(sb, "block_entity_budget", c.getBlockEntityBudget());

        section(sb, "Idle throttling");
        comment(sb, "Frame caps for when nobody is watching. 0 means no cap.");
        IdleConfig idle = c.getIdle();
        entry(sb, "idle_enabled", idle.enabled());
        entry(sb, "idle_menu_fps", idle.menuFps());
        entry(sb, "idle_unfocused_fps", idle.unfocusedFps());
        entry(sb, "idle_hidden_fps", idle.hiddenFps());
        entry(sb, "idle_afk_fps", idle.idleFps());
        entry(sb, "idle_afk_after_seconds", idle.idleAfterMillis() / 1000L);

        section(sb, "Fixed profile");
        comment(sb, "Used when mode = FIXED. Normally written by /graphene autotune.");
        QualitySettings p = c.getFixedProfile();
        entry(sb, "profile_entity_distance", p.entityDistanceScale());
        entry(sb, "profile_block_entity_distance", p.blockEntityDistanceScale());
        entry(sb, "profile_particle_density", p.particleDensity());
        entry(sb, "profile_entity_shadows", p.entityShadows());
        entry(sb, "profile_weather_density", p.weatherDensity());
        entry(sb, "profile_fancy_clouds", p.fancyClouds());
        entry(sb, "profile_render_distance_trim", p.renderDistanceTrim());
        entry(sb, "profile_culling_aggression", p.cullingAggression());

        return sb.toString();
    }

    private static void section(StringBuilder sb, String title) {
        sb.append('\n').append("# -- ").append(title).append(' ')
          .append("-".repeat(Math.max(0, 60 - title.length()))).append('\n');
    }

    private static void comment(StringBuilder sb, String text) {
        sb.append("# ").append(text).append('\n');
    }

    private static void entry(StringBuilder sb, String key, Object value) {
        String rendered = value instanceof Double d
                ? String.format(Locale.ROOT, "%.3f", d)
                : String.valueOf(value);
        sb.append(key).append(" = ").append(rendered).append('\n');
    }

    // -- typed reads, all of which fall back rather than throw -------------------------------

    private boolean bool(Map<String, String> v, String key, boolean fallback) {
        String s = v.get(key);
        if (s == null) {
            return fallback;
        }
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(s);
        }
        warnings.add(key + ": expected true or false, got '" + s + "'; using " + fallback);
        return fallback;
    }

    private int integer(Map<String, String> v, String key, int fallback) {
        String s = v.get(key);
        if (s == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            warnings.add(key + ": expected a whole number, got '" + s + "'; using " + fallback);
            return fallback;
        }
    }

    private double number(Map<String, String> v, String key, double fallback) {
        String s = v.get(key);
        if (s == null) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(s);
            if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                warnings.add(key + ": '" + s + "' is not a finite number; using " + fallback);
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException e) {
            warnings.add(key + ": expected a number, got '" + s + "'; using " + fallback);
            return fallback;
        }
    }

    private <E extends Enum<E>> E enumValue(Map<String, String> v, String key, Class<E> type, E fallback) {
        String s = v.get(key);
        if (s == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warnings.add(key + ": '" + s + "' is not one of "
                    + java.util.Arrays.toString(type.getEnumConstants()) + "; using " + fallback);
            return fallback;
        }
    }
}
