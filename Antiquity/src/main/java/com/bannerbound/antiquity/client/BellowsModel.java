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
 * Baked model for the Bellows Block, drawn by {@link BellowsRenderer}. Geometry is the Blockbench
 * export ({@code bellows.bbmodel}) cleaned up for 1.21.1; extends {@link HierarchicalModel} so the
 * "Push" animation ({@link BellowsAnimations#PUSH}) can drive the {@code Bellows_Top}/{@code Spine}
 * bones by name when the player jumps on it.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class BellowsModel extends HierarchicalModel<Entity> {
    public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "bellows"), "main");

    private final ModelPart root;

    public BellowsModel(ModelPart root) {
        this.root = root;
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        root.addOrReplaceChild("Bellows_Top", CubeListBuilder.create()
                .texOffs(0, 14).addBox(-3.0F, -2.0F, -1.0F, 4.0F, 2.0F, 12.0F, new CubeDeformation(0.0F))
                .texOffs(28, 40).addBox(-2.0F, -2.0F, -2.0F, 2.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(36, 33).addBox(-7.0F, -2.0F, 0.0F, 4.0F, 2.0F, 10.0F, new CubeDeformation(0.0F))
                .texOffs(0, 37).addBox(1.0F, -2.0F, 0.0F, 4.0F, 2.0F, 10.0F, new CubeDeformation(0.0F)),
            PartPose.offsetAndRotation(1.0F, 21.0F, -5.0F, 0.4363F, 0.0F, 0.0F));

        root.addOrReplaceChild("Spine", CubeListBuilder.create()
                .texOffs(0, 28).addBox(-9.0F, -1.0F, 0.0F, 10.0F, 1.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(32, 0).addBox(-9.0F, -3.0F, 0.0F, 10.0F, 1.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(28, 45).addBox(-8.0F, -2.0F, 0.0F, 8.0F, 1.0F, 7.0F, new CubeDeformation(0.0F))
                .texOffs(30, 53).addBox(-8.0F, -4.0F, 3.0F, 8.0F, 1.0F, 4.0F, new CubeDeformation(0.0F))
                .texOffs(0, 53).addBox(-9.0F, -5.0F, 3.0F, 10.0F, 1.0F, 5.0F, new CubeDeformation(0.0F)),
            PartPose.offset(4.0F, 22.0F, -4.0F));

        root.addOrReplaceChild("BellowsBase", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-3.0F, -2.0F, -1.0F, 4.0F, 2.0F, 12.0F, new CubeDeformation(0.0F))
                .texOffs(28, 37).addBox(-2.0F, -2.0F, -2.0F, 2.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(32, 9).addBox(-7.0F, -2.0F, 0.0F, 4.0F, 2.0F, 10.0F, new CubeDeformation(0.0F))
                .texOffs(36, 21).addBox(1.0F, -2.0F, 0.0F, 4.0F, 2.0F, 10.0F, new CubeDeformation(0.0F)),
            PartPose.offset(1.0F, 24.0F, -5.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    @Override
    public ModelPart root() {
        return root;
    }

    @Override
    public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                          float netHeadYaw, float headPitch) {
        // Animation is driven by the block entity renderer (the jump-push timer), not entity state.
    }
}
