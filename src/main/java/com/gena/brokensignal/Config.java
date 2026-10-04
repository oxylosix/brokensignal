package com.gena.brokensignal;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Enable horror events")
            .define("enabled", true);
    public static final ModConfigSpec.DoubleValue INTENSITY = BUILDER
            .comment("How often events happen (1.0 = normal, 2.0 = twice as often)")
            .defineInRange("intensity", 1.0, 0.1, 10.0);
    public static final ModConfigSpec.BooleanValue WORLD_EDITS = BUILDER
            .comment("Allow events that change blocks (torches, pillars, signs, chests)")
            .define("worldEdits", true);
    public static final ModConfigSpec.IntValue GRACE_MINUTES = BUILDER
            .comment("Quiet minutes before the first event")
            .defineInRange("graceMinutes", 3, 0, 120);
    public static final ModConfigSpec.BooleanValue AFFECT_CREATIVE = BUILDER
            .comment("Run events for players in creative mode")
            .define("affectCreative", false);
    public static final ModConfigSpec.BooleanValue COMPUTER_EVENTS = BUILDER
            .comment("Meta events: window title, fake 'connection lost' screen, static, jumpscare, screenshots,",
                    "messages with your PC user name and local time. Nothing leaves your computer.")
            .define("computerEvents", true);
    public static final ModConfigSpec.BooleanValue OPEN_FILES = BUILDER
            .comment("Allow the 'note' event to open a text file (.minecraft/brokensignal/s1gnal.txt) in your text editor")
            .define("openFiles", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}
}
