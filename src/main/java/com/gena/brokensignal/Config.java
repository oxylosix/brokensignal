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
            .comment("Allow events that change blocks (torches, pillars, signs)")
            .define("worldEdits", true);
    public static final ModConfigSpec.IntValue GRACE_MINUTES = BUILDER
            .comment("Quiet minutes before the first event")
            .defineInRange("graceMinutes", 3, 0, 120);
    public static final ModConfigSpec.BooleanValue AFFECT_CREATIVE = BUILDER
            .comment("Run events for players in creative mode")
            .define("affectCreative", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}
}
