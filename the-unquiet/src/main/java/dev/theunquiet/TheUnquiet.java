package dev.theunquiet;

import dev.theunquiet.core.HorrorCore;
import dev.theunquiet.game.EventDirector;
import dev.theunquiet.game.EventCatalog;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import dev.theunquiet.game.DevCommands;

@Mod(TheUnquiet.MOD_ID)
public final class TheUnquiet {
    public static final String MOD_ID = "theunquiet";

    public TheUnquiet(IEventBus modBus, ModContainer container) {
        UnquietConfig.register(container);
        MetaConsentConfig.register(container);
        ModEntities.register(modBus);
        EventCatalog.register();
        HorrorCore.addServerTickListener(EventDirector::tick);
        NeoForge.EVENT_BUS.addListener(DevCommands::register);
    }
}
