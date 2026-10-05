package dev.theunquiet.client;

import dev.theunquiet.TheUnquiet;
import dev.theunquiet.entity.WitnessEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

public final class WitnessRenderer extends HumanoidMobRenderer<WitnessEntity, PlayerModel<WitnessEntity>> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(TheUnquiet.MOD_ID, "textures/entity/witness.png");

    public WitnessRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.0F);
    }

    @Override
    public ResourceLocation getTextureLocation(WitnessEntity entity) {
        return TEXTURE;
    }
}
