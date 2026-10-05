package com.gena.brokensignal;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-player settings (config/brokensignal-client.toml). Only the player can turn these on. */
public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("Real-PC companion. Off by default. Turn on only if you installed and started",
                "BrokenSignalCompanion yourself. It talks only to 127.0.0.1 and uses only its own folder",
                "(<home>/BrokenSignalCompanion). Create a file named STOP in that folder to disable it instantly.")
                .push("companion");
    }

    public static final ModConfigSpec.BooleanValue COMPANION = BUILDER
            .comment("Allow the mod to send cues to the companion app and receive its reports")
            .define("companion", false);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {}
}
