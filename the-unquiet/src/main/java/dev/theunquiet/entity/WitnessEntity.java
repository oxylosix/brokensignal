package dev.theunquiet.entity;

import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

public final class WitnessEntity extends PathfinderMob {
    private UUID observer;

    public WitnessEntity(EntityType<? extends WitnessEntity> type, Level level) {
        super(type, level);
        setNoAi(true);
        setSilent(true);
        setInvulnerable(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.01);
    }

    public void setObserver(UUID observer) {
        this.observer = observer;
    }

    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        return observer != null && observer.equals(player.getUUID()) && super.broadcastToPlayer(player);
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && tickCount > 100) {
            discard();
        }
    }
}
