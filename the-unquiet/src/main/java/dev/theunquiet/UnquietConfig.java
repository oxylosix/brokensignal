package dev.theunquiet;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class UnquietConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue HORROR_ENABLED;
    public static final ModConfigSpec.DoubleValue INTENSITY;
    public static final ModConfigSpec.BooleanValue RARE_EVENTS;
    public static final ModConfigSpec.BooleanValue ENTITY_EVENTS;
    public static final ModConfigSpec.BooleanValue MULTIPLAYER_EVENTS;
    public static final ModConfigSpec.BooleanValue AUDIO_EFFECTS;
    public static final ModConfigSpec.BooleanValue EXTREME_EVENTS;
    public static final ModConfigSpec.BooleanValue SAFE_MODE;
    public static final ModConfigSpec.BooleanValue DEVELOPER_MODE;
    public static final ModConfigSpec SPEC;

    static {
        BUILDER.push("experience");
        HORROR_ENABLED = BUILDER.comment("Master switch for in-game horror events.")
                .define("enabled", true);
        INTENSITY = BUILDER.comment("Event selection intensity. Low values preserve longer quiet periods.")
                .defineInRange("intensity", 0.35, 0.0, 1.0);
        RARE_EVENTS = BUILDER.define("rare_events", true);
        ENTITY_EVENTS = BUILDER.define("entity_events", true);
        MULTIPLAYER_EVENTS = BUILDER.define("multiplayer_events", true);
        AUDIO_EFFECTS = BUILDER.define("audio_effects", true);
        EXTREME_EVENTS = BUILDER.define("extreme_events", false);
        SAFE_MODE = BUILDER.define("safe_mode", true);
        DEVELOPER_MODE = BUILDER.define("developer_mode", false);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private UnquietConfig() {
    }

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC, "theunquiet-common.toml");
    }

}
