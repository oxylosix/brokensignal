package dev.theunquiet;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class MetaConsentConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue META_HORROR;
    public static final ModConfigSpec.BooleanValue PERSONALIZED_META;
    public static final ModConfigSpec.BooleanValue EXTERNAL_WINDOWS;
    public static final ModConfigSpec.BooleanValue CREATED_FILES;
    public static final ModConfigSpec.BooleanValue AUDIO_EFFECTS;
    public static final ModConfigSpec.BooleanValue EXTREME_EVENTS;
    public static final ModConfigSpec.BooleanValue SAFE_MODE;
    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment(
                "The Unquiet does not currently perform external computer effects. These are reserved opt-ins for the later meta layer.")
                .push("meta_horror");
        META_HORROR = BUILDER.define("enabled", false);
        PERSONALIZED_META = BUILDER.define("personalized_meta_horror", false);
        EXTERNAL_WINDOWS = BUILDER.define("external_windows", false);
        CREATED_FILES = BUILDER.define("created_files", false);
        AUDIO_EFFECTS = BUILDER.define("audio_effects", false);
        EXTREME_EVENTS = BUILDER.define("extreme_events", false);
        SAFE_MODE = BUILDER.define("safe_mode", true);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private MetaConsentConfig() {
    }

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC, "theunquiet-meta-client.toml");
    }
}
