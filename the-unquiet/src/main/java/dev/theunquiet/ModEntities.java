package dev.theunquiet;

import dev.theunquiet.entity.WitnessEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.SubscribeEvent;

@Mod.EventBusSubscriber(modid = TheUnquiet.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModEntities {
    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, TheUnquiet.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<WitnessEntity>> WITNESS =
            ENTITIES.register("witness", () -> EntityType.Builder.of(WitnessEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.95F)
                    .clientTrackingRange(16)
                    .build("witness"));

    private ModEntities() {
    }

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
    }

    @SubscribeEvent
    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(WITNESS.get(), WitnessEntity.createAttributes().build());
    }
}
