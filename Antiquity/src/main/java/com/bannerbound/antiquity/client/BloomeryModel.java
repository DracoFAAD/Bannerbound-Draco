package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Baked model for the Bloomery multiblock (1×1×2), drawn by {@link BloomeryRenderer}.
 * <p>
 * Geometry is the Blockbench "Modded Entity" export of {@code Bloomery.bbmodel}, cleaned up for
 * Minecraft 1.21.1. Extends {@link HierarchicalModel} so {@code KeyframeAnimations.animate} can
 * drive the "Door" bone's open/close animations by name.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class BloomeryModel extends HierarchicalModel<Entity> {
    public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "bloomery"), "main");

    private final ModelPart root;
    private final ModelPart master;
    private final ModelPart inside;

    public BloomeryModel(ModelPart root) {
        this.root = root;
        this.master = root.getChild("Master");
        this.inside = this.master.getChild("Inside");
    }

    /** Renders the bloomery — everything except the "Inside" bone (drawn separately so it can
     *  glow orange while the furnace is lit). */
    public void renderBody(com.mojang.blaze3d.vertex.PoseStack pose,
                           com.mojang.blaze3d.vertex.VertexConsumer vc, int light, int overlay) {
        inside.visible = false;
        renderToBuffer(pose, vc, light, overlay, 0xFFFFFFFF);
        inside.visible = true;
    }

    /** Renders just the "Inside" bone, with the given light + colour tint. */
    public void renderInside(com.mojang.blaze3d.vertex.PoseStack pose,
                             com.mojang.blaze3d.vertex.VertexConsumer vc, int light, int overlay, int color) {
        pose.pushPose();
        master.translateAndRotate(pose);
        inside.render(pose, vc, light, overlay, color);
        pose.popPose();
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        PartDefinition master = root.addOrReplaceChild("Master", CubeListBuilder.create(),
            PartPose.offsetAndRotation(-7.0F, 24.0F, -7.0F, 0.0F, -1.5708F, 0.0F));

        PartDefinition base = master.addOrReplaceChild("Base", CubeListBuilder.create()
                .texOffs(0, 29).addBox(2.0F, -16.0F, -10.0F, 11.0F, 1.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(0, 0).addBox(1.0F, -3.0F, -13.0F, 12.0F, 3.0F, 12.0F, new CubeDeformation(0.0F)),
            PartPose.offset(0.0F, 0.0F, 0.0F));

        base.addOrReplaceChild("North Wall", CubeListBuilder.create()
                .texOffs(24, 46).addBox(-11.0F, -5.0F, -2.0F, 12.0F, 5.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(48, 6).addBox(-11.0F, -8.0F, -1.0F, 12.0F, 3.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(44, 15).addBox(-11.0F, -13.0F, 0.0F, 12.0F, 5.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(58, 33).addBox(-11.0F, -15.0F, 1.0F, 12.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)),
            PartPose.offset(12.0F, 0.0F, -12.0F));

        base.addOrReplaceChild("South Wall", CubeListBuilder.create()
                .texOffs(48, 0).addBox(-11.0F, -5.0F, -2.0F, 12.0F, 5.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(48, 10).addBox(-11.0F, -8.0F, -3.0F, 12.0F, 3.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(44, 22).addBox(-11.0F, -13.0F, -5.0F, 12.0F, 5.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(58, 36).addBox(-11.0F, -15.0F, -5.0F, 12.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)),
            PartPose.offset(12.0F, 0.0F, 1.0F));

        base.addOrReplaceChild("Front Cover", CubeListBuilder.create()
                .texOffs(44, 52).addBox(0.0F, -3.0F, -1.0F, 1.0F, 3.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(24, 41).addBox(0.0F, 0.0F, -10.0F, 1.0F, 2.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(24, 36).addBox(0.0F, 0.0F, -1.0F, 1.0F, 2.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(44, 57).addBox(0.0F, -3.0F, -9.0F, 1.0F, 3.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(44, 62).addBox(0.0F, -10.0F, -7.0F, 1.0F, 3.0F, 6.0F, new CubeDeformation(0.0F)),
            PartPose.offset(1.0F, -5.0F, -3.0F));

        base.addOrReplaceChild("East Back", CubeListBuilder.create()
                .texOffs(14, 63).addBox(0.0F, -3.0F, -1.0F, 1.0F, 3.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(14, 53).addBox(0.0F, 0.0F, -1.0F, 1.0F, 2.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(64, 53).addBox(0.0F, -3.0F, -9.0F, 1.0F, 3.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(14, 58).addBox(0.0F, 0.0F, -10.0F, 1.0F, 2.0F, 3.0F, new CubeDeformation(0.0F))
                .texOffs(50, 46).addBox(0.0F, -8.0F, -7.0F, 1.0F, 10.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(58, 62).addBox(0.0F, -10.0F, -7.0F, 1.0F, 2.0F, 6.0F, new CubeDeformation(0.0F)),
            PartPose.offset(12.0F, -5.0F, -3.0F));

        master.addOrReplaceChild("Door", CubeListBuilder.create()
                .texOffs(64, 58).addBox(-1.0F, -1.0F, 3.0F, 1.0F, 1.0F, 2.0F, new CubeDeformation(0.0F))
                .texOffs(0, 53).addBox(0.0F, -5.0F, 0.0F, 1.0F, 9.0F, 6.0F, new CubeDeformation(0.0F)),
            PartPose.offset(0.2F, -7.0F, -10.0F));

        master.addOrReplaceChild("Inside", CubeListBuilder.create()
                .texOffs(0, 15).addBox(-5.0F, -5.0F, -6.0F, 10.0F, 2.0F, 12.0F, new CubeDeformation(0.0F)),
            PartPose.offset(7.0F, 0.0F, -7.0F));

        master.addOrReplaceChild("Chimney", CubeListBuilder.create()
                .texOffs(58, 31).addBox(-12.0F, -1.0F, 2.0F, 13.0F, 1.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(34, 29).addBox(-11.0F, 0.0F, 1.0F, 11.0F, 16.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(0, 36).addBox(-11.0F, 0.0F, -4.0F, 11.0F, 16.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(24, 52).addBox(-1.0F, 0.0F, -3.0F, 1.0F, 16.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(34, 52).addBox(-11.0F, 0.0F, -3.0F, 1.0F, 16.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(58, 39).addBox(-12.0F, -1.0F, -4.0F, 1.0F, 1.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(64, 46).addBox(0.0F, -1.0F, -4.0F, 1.0F, 1.0F, 6.0F, new CubeDeformation(0.0F))
                .texOffs(58, 29).addBox(-12.0F, -1.0F, -5.0F, 13.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)),
            PartPose.offset(13.0F, -32.0F, -6.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    @Override
    public ModelPart root() {
        return root;
    }

    @Override
    public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                          float netHeadYaw, float headPitch) {
        // Animation is driven by the block entity renderer, not entity state.
    }
}
