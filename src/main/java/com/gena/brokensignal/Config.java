package com.gena.brokensignal;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.push("general");
    }

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Enable horror events")
            .define("enabled", true);
    public static final ModConfigSpec.DoubleValue INTENSITY = BUILDER
            .comment("Event frequency multiplier (1.0 = normal, 2.0 = twice as often, 0.5 = half)")
            .defineInRange("intensity", 1.0, 0.1, 10.0);
    public static final ModConfigSpec.DoubleValue PHASE_SPEED = BUILDER
            .comment("How fast the hidden escalation progresses (1.0 = normal)")
            .defineInRange("phaseSpeed", 1.0, 0.1, 10.0);
    public static final ModConfigSpec.DoubleValue RARE_CHANCE = BUILDER
            .comment("Multiplier for uncommon, rare and secret events")
            .defineInRange("rareChance", 1.0, 0.0, 20.0);
    public static final ModConfigSpec.IntValue MIN_GAP_SECONDS = BUILDER
            .comment("Hard minimum of seconds between two director events")
            .defineInRange("minGapSeconds", 60, 5, 3600);
    public static final ModConfigSpec.IntValue GRACE_MINUTES = BUILDER
            .comment("Quiet minutes before anything can happen in a new world")
            .defineInRange("graceMinutes", 3, 0, 120);
    public static final ModConfigSpec.BooleanValue AFFECT_CREATIVE = BUILDER
            .comment("Run events for players in creative mode")
            .define("affectCreative", false);
    public static final ModConfigSpec.BooleanValue SECRETS = BUILDER
            .comment("Allow secret and ultra-rare events")
            .define("secrets", true);

    static {
        BUILDER.pop().push("world");
    }

    public static final ModConfigSpec.BooleanValue WORLD_EDITS = BUILDER
            .comment("Allow events that place/remove blocks (torches, signs, chests, small structures)")
            .define("worldEdits", true);
    public static final ModConfigSpec.IntValue MAX_WORLD_CHANGES = BUILDER
            .comment("Max block-changing events per in-game hour of play")
            .defineInRange("maxWorldChangesPerHour", 6, 0, 100);

    public static final ModConfigSpec.BooleanValue MIMIC_ENABLED = BUILDER
            .comment("Copies of players, of you and of animals/villagers")
            .define("mimics", true);
    public static final ModConfigSpec.DoubleValue MIMIC_BETRAYAL = BUILDER
            .comment("Multiplier for the chance that a perfect copy attacks once (0 = never)")
            .defineInRange("mimicBetrayal", 1.0, 0.0, 3.0);

    static {
        BUILDER.pop().push("presentation");
    }

    public static final ModConfigSpec.BooleanValue COMPUTER_EVENTS = BUILDER
            .comment("In-game computer block and client 'fake glitch' effects (window title, fake screens, UI glitches).",
                    "Everything is simulated inside Minecraft: no real files, programs or system data are touched.")
            .define("computerEvents", true);
    public static final ModConfigSpec.BooleanValue VISUAL_EFFECTS = BUILDER
            .comment("Short visual anomalies: flicker, fog, HUD glitches, wrong item names")
            .define("visualEffects", true);
    public static final ModConfigSpec.DoubleValue SOUND_VOLUME = BUILDER
            .comment("Volume multiplier for event sounds")
            .defineInRange("soundVolume", 1.0, 0.0, 2.0);

    static {
        BUILDER.pop().push("debug");
    }

    public static final ModConfigSpec.BooleanValue DEBUG = BUILDER
            .comment("Developer mode: verbose reasons in /brokensignal output and debug log lines")
            .define("debug", false);
    public static final ModConfigSpec.BooleanValue LOG_EVENTS = BUILDER
            .comment("Write every fired event (with context) to the log")
            .define("logEvents", false);
    public static final ModConfigSpec.BooleanValue PERFORMANCE_MODE = BUILDER
            .comment("Smaller world scans for slow servers")
            .define("performanceMode", false);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}

    public static float vol(float v) {
        return (float) (v * SOUND_VOLUME.get());
    }
}
