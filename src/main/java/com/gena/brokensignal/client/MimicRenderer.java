package com.gena.brokensignal.client;

import com.gena.brokensignal.mimic.MimicEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws a mimic exactly like a player: the real skin of whoever it copies (wide or slim arms),
 * armour, held item, player scale, vanilla name tag. No extra layers, no eyes, nothing to notice.
 */
public class MimicRenderer extends HumanoidMobRenderer<MimicEntity, PlayerModel<MimicEntity>> {
    private final PlayerModel<MimicEntity> wide;
    private final PlayerModel<MimicEntity> slim;

    public MimicRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.wide = this.model;
        this.slim = new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        this.addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidArmorModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                ctx.getModelManager()));
    }

    private static PlayerSkin skin(MimicEntity e) {
        UUID id = e.skinOwner();
        Minecraft mc = Minecraft.getInstance();
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(id);
        return info != null ? info.getSkin() : DefaultPlayerSkin.get(id);
    }

    @Override
    public void render(MimicEntity e, float yaw, float partial, PoseStack pose, MultiBufferSource buf, int light) {
        this.model = skin(e).model() == PlayerSkin.Model.SLIM ? slim : wide;
        this.model.crouching = e.isCrouching();
        super.render(e, yaw, partial, pose, buf, light);
    }

    @Override
    public ResourceLocation getTextureLocation(MimicEntity e) {
        return skin(e).texture();
    }

    @Override
    protected void scale(MimicEntity e, PoseStack pose, float partial) {
        pose.scale(0.9375F, 0.9375F, 0.9375F);
    }
}
