package com.gena.brokensignal;

import com.gena.brokensignal.pc.ComputerActionPayload;
import com.gena.brokensignal.pc.ComputerService;
import com.gena.brokensignal.pc.ComputerSyncPayload;
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
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        NeoForge.EVENT_BUS.register(V5Hooks.class);

        NeoForge.EVENT_BUS.addListener(HorrorEvents::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(HorrorEvents::onLogout);
        NeoForge.EVENT_BUS.addListener(HorrorEvents::onClone);
        NeoForge.EVENT_BUS.addListener(HorrorEvents::onChat);
        NeoForge.EVENT_BUS.addListener(HorrorEvents::onDeath);
        NeoForge.EVENT_BUS.addListener(HorrorEvents::onLogin);
        NeoForge.EVENT_BUS.addListener(HorrorEvents::onBreak);
        NeoForge.EVENT_BUS.addListener(HorrorEvents::onPlace);
        NeoForge.EVENT_BUS.addListener(SignalCommand::register);
    }

    private static void onPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(MetaPayload.TYPE, MetaPayload.STREAM_CODEC, MetaPayload::handle);
        registrar.playToClient(ComputerSyncPayload.TYPE, ComputerSyncPayload.STREAM_CODEC, ComputerSyncPayload::handle);
        registrar.playToServer(ComputerActionPayload.TYPE, ComputerActionPayload.STREAM_CODEC, ComputerService::handle);
        registrar.playToServer(com.gena.brokensignal.ext.CompanionPayload.TYPE,
                com.gena.brokensignal.ext.CompanionPayload.STREAM_CODEC, com.gena.brokensignal.ext.Outside::handle);
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(ModRegistry.WATCHER.get(), WatcherEntity.createAttributes().build());
        event.put(ModRegistry.MIMIC.get(), com.gena.brokensignal.mimic.MimicEntity.createAttributes().build());
    }

    private static void onCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ModRegistry.WATCHER_EGG.get());
        } else if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModRegistry.COMPUTER_ITEM.get());
        }
    }
}
