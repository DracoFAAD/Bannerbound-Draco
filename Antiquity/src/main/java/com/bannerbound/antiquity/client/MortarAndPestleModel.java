package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

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
 * Baked model for the Mortar and Pestle block, drawn by {@link MortarAndPestleRenderer}.
 * <p>
 * Geometry is the Blockbench "Modded Entity" export of {@code MortarandPestle.bbmodel}, cleaned
 * up for Minecraft 1.21.1. Extends {@link HierarchicalModel} so {@code KeyframeAnimations.animate}
 * can drive the pestle's "Mix" grind animation by bone name.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class MortarAndPestleModel extends HierarchicalModel<Entity> {
    public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "mortar_and_pestle"), "main");

    private final ModelPart root;
    private final ModelPart master;
    private final ModelPart pestle;
    private final ModelPart liquidHolder;

    public MortarAndPestleModel(ModelPart root) {
        this.root = root;
        this.master = root.getChild("Master");
        this.pestle = this.master.getChild("Pestle");
        this.liquidHolder = this.master.getChild("Liquid Holder");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        PartDefinition partMaster = root.addOrReplaceChild("Master", CubeListBuilder.create(),
            PartPose.offsetAndRotation(1.0F, 24.0F, -1.0F, 0.0F, -1.5708F, 0.0F));

        partMaster.addOrReplaceChild("Base", CubeListBuilder.create()
                .texOffs(0, 26).addBox(-4.0F, -1.0F, -1.0F, 5.0F, 1.0F, 5.0F, new CubeDeformation(0.0F))
                .texOffs(0, 0).addBox(-6.0F, 0.0F, -3.0F, 9.0F, 1.0F, 9.0F, new CubeDeformation(0.0F)),
            PartPose.offset(2.0F, -1.0F, -1.0F));

        PartDefinition partMortar = partMaster.addOrReplaceChild("Mortar", CubeListBuilder.create()
                .texOffs(16, 36).addBox(0.0F, -1.0F, -1.0F, 1.0F, 1.0F, 7.0F, new CubeDeformation(0.0F))
                .texOffs(20, 26).addBox(1.0F, -4.0F, -1.0F, 1.0F, 3.0F, 7.0F, new CubeDeformation(0.0F))
                .texOffs(36, 20).addBox(-6.0F, -1.0F, -1.0F, 1.0F, 1.0F, 7.0F, new CubeDeformation(0.0F))
                .texOffs(28, 10).addBox(-7.0F, -4.0F, -1.0F, 1.0F, 3.0F, 7.0F, new CubeDeformation(0.0F))
                .texOffs(0, 10).addBox(-6.0F, 0.0F, -1.0F, 7.0F, 1.0F, 7.0F, new CubeDeformation(0.0F)),
            PartPose.offset(3.0F, -3.0F, -2.0F));

        partMortar.addOrReplaceChild("Bowl Side_r1", CubeListBuilder.create()
                .texOffs(36, 0).addBox(0.0F, -3.0F, -1.0F, 1.0F, 3.0F, 7.0F, new CubeDeformation(0.0F)),
            PartPose.offsetAndRotation(0.0F, -1.0F, 6.0F, 0.0F, -1.5708F, 0.0F));
        partMortar.addOrReplaceChild("Bowl_Side_r2", CubeListBuilder.create()
                .texOffs(0, 32).addBox(0.0F, -3.0F, -1.0F, 1.0F, 3.0F, 7.0F, new CubeDeformation(0.0F)),
            PartPose.offsetAndRotation(0.0F, -1.0F, -2.0F, 0.0F, -1.5708F, 0.0F));
        partMortar.addOrReplaceChild("Bowl_Side_r3", CubeListBuilder.create()
                .texOffs(32, 36).addBox(0.0F, -1.0F, -1.0F, 1.0F, 1.0F, 5.0F, new CubeDeformation(0.0F)),
            PartPose.offsetAndRotation(-1.0F, 0.0F, 5.0F, 0.0F, -1.5708F, 0.0F));
        partMortar.addOrReplaceChild("Bowl_Side_r4", CubeListBuilder.create()
                .texOffs(36, 28).addBox(0.0F, -1.0F, -1.0F, 1.0F, 1.0F, 5.0F, new CubeDeformation(0.0F)),
            PartPose.offsetAndRotation(-1.0F, 0.0F, -1.0F, 0.0F, -1.5708F, 0.0F));

        PartDefinition partPestle = partMaster.addOrReplaceChild("Pestle", CubeListBuilder.create(),
            PartPose.offset(0.0F, -4.0F, 0.0F));
        partPestle.addOrReplaceChild("Pestle_r1", CubeListBuilder.create()
                .texOffs(0, 42).addBox(-1.0F, -7.0F, -1.0F, 2.0F, 7.0F, 2.0F, new CubeDeformation(0.0F)),
            PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, -0.5672F));

        partMaster.addOrReplaceChild("Liquid Holder", CubeListBuilder.create()
                .texOffs(0, 18).addBox(-6.0F, -1.0F, -1.0F, 7.0F, 1.0F, 7.0F, new CubeDeformation(0.0F)),
            PartPose.offset(3.0F, -4.0F, -2.0F));

        return LayerDefinition.create(mesh, 64, 64);
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

    /** Renders the mortar body (base, bowl, pestle) — everything except the liquid surface. */
    public void renderBody(PoseStack pose, VertexConsumer vc, int light, int overlay) {
        liquidHolder.visible = false;
        master.render(pose, vc, light, overlay, 0xFFFFFFFF);
        liquidHolder.visible = true;
    }

    /** The Master bone — apply its transform before drawing anything in model space. */
    public ModelPart master() {
        return master;
    }

    /** The Pestle bone — the "Mix" animation drives it; a press offset can be added on top. */
    public ModelPart pestle() {
        return pestle;
    }

    /** The Liquid Holder bone — its authored position is where the liquid surface sits. */
    public ModelPart liquidHolder() {
        return liquidHolder;
    }
}
