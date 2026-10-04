package com.gena.brokensignal;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModRegistry {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, BrokenSignal.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BrokenSignal.MODID);
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, BrokenSignal.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<WatcherEntity>> WATCHER =
            ENTITIES.register("watcher", () -> EntityType.Builder.of(WatcherEntity::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.95F)
                    .clientTrackingRange(16)
                    .fireImmune()
                    .build("watcher"));

    public static final DeferredItem<DeferredSpawnEggItem> WATCHER_EGG =
            ITEMS.register("watcher_spawn_egg",
                    () -> new DeferredSpawnEggItem(WATCHER, 0x050505, 0xEEEEEE, new Item.Properties()));

    public static final DeferredHolder<SoundEvent, SoundEvent> WHISPER = sound("whisper");
    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE = sound("drone");
    public static final DeferredHolder<SoundEvent, SoundEvent> STATIC_BURST = sound("static_burst");
    public static final DeferredHolder<SoundEvent, SoundEvent> KNOCK = sound("knock");

    private ModRegistry() {}

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUNDS.register(name,
                () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(BrokenSignal.MODID, name)));
    }

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        SOUNDS.register(modBus);
    }
}
