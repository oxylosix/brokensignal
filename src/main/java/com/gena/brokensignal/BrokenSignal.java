package com.gena.brokensignal;

import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(BrokenSignal.MODID)
public final class BrokenSignal {
    public static final String MODID = "brokensignal";

    public BrokenSignal(IEventBus modBus, ModContainer container) {
        ModRegistry.register(modBus);
        modBus.addListener(BrokenSignal::onAttributes);
        modBus.addListener(BrokenSignal::onCreativeTabs);
        modBus.addListener(BrokenSignal::onPayloads);
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        NeoForge.EVENT_BUS.addListener(HorrorEvents::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(HorrorEvents::onLogout);
        NeoForge.EVENT_BUS.addListener(HorrorEvents::onClone);
        NeoForge.EVENT_BUS.addListener(HorrorEvents::onChat);
        NeoForge.EVENT_BUS.addListener(HorrorEvents::onDeath);
        NeoForge.EVENT_BUS.addListener(SignalCommand::register);
    }

    private static void onPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(MetaPayload.TYPE, MetaPayload.STREAM_CODEC, MetaPayload::handle);
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(ModRegistry.WATCHER.get(), WatcherEntity.createAttributes().build());
    }

    private static void onCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ModRegistry.WATCHER_EGG.get());
        }
    }
}
