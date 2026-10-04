package com.gena.brokensignal.client;

import com.gena.brokensignal.BrokenSignal;
import com.gena.brokensignal.WatcherEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

public class WatcherRenderer extends HumanoidMobRenderer<WatcherEntity, HumanoidModel<WatcherEntity>> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BrokenSignal.MODID, "textures/entity/watcher.png");

    public WatcherRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), 0.0F);
        addLayer(new WatcherEyesLayer(this));
    }

    @Override
    public ResourceLocation getTextureLocation(WatcherEntity entity) {
        return TEXTURE;
    }

    @Override
    protected void scale(WatcherEntity entity, PoseStack poseStack, float partialTick) {
        poseStack.scale(1.0F, 1.08F, 1.0F);
    }
}
