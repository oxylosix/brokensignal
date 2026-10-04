package com.gena.brokensignal.client;

import com.gena.brokensignal.BrokenSignal;
import com.gena.brokensignal.WatcherEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.resources.ResourceLocation;

/** Glowing eyes, visible in full darkness. */
public class WatcherEyesLayer extends EyesLayer<WatcherEntity, HumanoidModel<WatcherEntity>> {
    private static final RenderType EYES = RenderType.eyes(
            ResourceLocation.fromNamespaceAndPath(BrokenSignal.MODID, "textures/entity/watcher_eyes.png"));

    public WatcherEyesLayer(RenderLayerParent<WatcherEntity, HumanoidModel<WatcherEntity>> parent) {
        super(parent);
    }

    @Override
    public RenderType renderType() {
        return EYES;
    }
}
