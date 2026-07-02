package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The designed helmet's 3D geometry — the first piece of the player-designed armor system (ARMOR_PLAN.md).
 * Translated by hand from {@code assets/.../models/armor/helmet.bbmodel} (Blockbench "Modded Entity",
 * box-UV, 64×64, flip-Y): each cube reads {@code addBox(x0, -y1, z0, w, h, d)} with
 * {@code texOffs(uv_offset)} — the canonical flip-Y export transform (verified against the bbmodel's
 * {@code REF_head} → the vanilla {@code (-4,-8,-4,8,8,8)} head).
 *
 * <p>The cubes are split into the four <b>zone bones</b> the bbmodel groups them into — {@link #DOME},
 * {@link #FRONT}, {@link #CHEEKS}, {@link #NECK} — so the design screen can render each zone
 * independently with its own material (the {@code zones:{zone→material}} schema from the plan). All
 * bones share the head/neck origin {@code (0,0,0)}, so the same model attaches to the head bone for
 * worn rendering later.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class HelmetModel {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "helmet_armor"), "main");

    // Zone bone names (match the bbmodel groups). Public so the screen can iterate them.
    public static final String DOME = "dome";
    public static final String FRONT = "front";
    public static final String CHEEKS = "cheeks";
    public static final String NECK = "neck";

    private final ModelPart root;

    public HelmetModel(ModelPart root) {
        this.root = root;
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartPose zero = PartPose.offset(0.0F, 0.0F, 0.0F);

        // Zone_dome → the crown plate.
        root.addOrReplaceChild(DOME, CubeListBuilder.create()
                .texOffs(0, 16).addBox(-4.0F, -9.0F, -4.0F, 8.0F, 1.0F, 8.0F, CubeDeformation.NONE), // Top
            zero);

        // Zone_front → the brow band + its two front uprights.
        root.addOrReplaceChild(FRONT, CubeListBuilder.create()
                .texOffs(22, 25).addBox(-4.0F, -9.0F, -5.0F, 8.0F, 3.0F, 1.0F, CubeDeformation.NONE) // Front
                .texOffs(10, 32).addBox(-5.0F, -9.0F, -5.0F, 1.0F, 4.0F, 1.0F, CubeDeformation.NONE) // Front Right
                .texOffs(14, 32).addBox(4.0F, -9.0F, -5.0F, 1.0F, 4.0F, 1.0F, CubeDeformation.NONE), // Front Left
            zero);

        // Zone_cheeks → both cheek guards (left + right). These are the only cubes whose LARGE faces
        // point east/west, so they're the only place a box-UV east/west swap is visible — .mirror()
        // puts the outer-face texture back on the outer face.
        root.addOrReplaceChild(CHEEKS, CubeListBuilder.create().mirror()
                .texOffs(22, 29).addBox(-5.0F, -9.0F, 0.0F, 1.0F, 5.0F, 4.0F, CubeDeformation.NONE)  // Right Back
                .texOffs(32, 0).addBox(-5.0F, -9.0F, -4.0F, 1.0F, 4.0F, 4.0F, CubeDeformation.NONE)  // Right Front
                .texOffs(32, 8).addBox(4.0F, -9.0F, -4.0F, 1.0F, 4.0F, 4.0F, CubeDeformation.NONE)   // Left Front
                .texOffs(0, 32).addBox(4.0F, -9.0F, 0.0F, 1.0F, 5.0F, 4.0F, CubeDeformation.NONE),   // Left Back
            zero);

        // Zone_neck → the rear gorget.
        root.addOrReplaceChild(NECK, CubeListBuilder.create()
                .texOffs(0, 25).addBox(-5.0F, -9.0F, 4.0F, 10.0F, 6.0F, 1.0F, CubeDeformation.NONE)  // Back
                .texOffs(32, 16).addBox(-3.0F, -3.0F, 4.0F, 6.0F, 1.0F, 1.0F, CubeDeformation.NONE), // Back Bottom
            zero);

        // Texture is 64×64 (matches the bbmodel resolution).
        return LayerDefinition.create(mesh, 64, 64);
    }

    /** Draws one zone bone. The texture is bound by the {@code vc}'s RenderType (material-specific). */
    public void renderZone(String zone, PoseStack pose, VertexConsumer vc, int light, int overlay, int color) {
        root.getChild(zone).render(pose, vc, light, overlay, color);
    }
}
