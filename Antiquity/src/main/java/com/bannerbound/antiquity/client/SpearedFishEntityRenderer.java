package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.entity.SpearedFishEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.model.CodModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.PufferfishBigModel;
import net.minecraft.client.model.SalmonModel;
import net.minecraft.client.model.TropicalFishModelA;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Renders a {@link SpearedFishEntity}: the spear's 3D model angled tip-down into the water with the
 * speared fish's real vanilla model impaled near the tip. The spear half reuses {@link
 * SpearProjectileRenderer}'s tip-anchor approach; the fish half bakes the vanilla cod/salmon/
 * pufferfish/tropical models (no custom layer definitions — vanilla already registers these).
 *
 * <p>Every numeric constant below is a <b>visual tunable</b> — the spear/fish poses are eyeballed and
 * meant to be nudged in-game until the shaft reads as passing through the fish at the waterline (the
 * same convention the project's other renderers follow).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class SpearedFishEntityRenderer extends EntityRenderer<SpearedFishEntity> {
    // ── Spear pose (mirrors SpearProjectileRenderer's tip anchor + flight orientation) ──
    /** Brings the model's authored 45°-up diagonal onto the flight axis (same as the in-flight
     *  renderer's MODEL_PITCH_CORRECTION) — so the planted catch reads like the spear that flew. */
    private static final float SPEAR_MODEL_PITCH = -45.0F;
    private static final float SPEAR_SCALE = 1.0F;
    private static final float TIP_X = 30.0F / 16.0F;
    private static final float TIP_Y = 28.0F / 16.0F;
    private static final float TIP_Z = 8.5F / 16.0F;
    // +0.5 cancels ItemRenderer.renderStatic's internal translate(-0.5) so the TIP lands on origin.
    private static final float ANCHOR_X = 0.5F - TIP_X;
    private static final float ANCHOR_Y = 0.5F - TIP_Y;
    private static final float ANCHOR_Z = 0.5F - TIP_Z;

    // ── Fish pose (impaled at the spear tip ≈ the entity origin) ──
    private static final float FISH_SCALE = 0.9F;
    /** Final position nudge of the fish on the tip (the spear tip already sits at the entity origin,
     *  so 0,0,0 = right on the point). */
    private static final float FISH_X = 0.0F;
    private static final float FISH_Y = 0.0F;
    private static final float FISH_Z = 0.0F;
    /** Tilt the fish head-up/down about its swim axis. 0 = flat. */
    private static final float FISH_PITCH = 0.0F;
    /** Roll the fish onto its side for a limp, speared "caught" read. */
    private static final float FISH_FLOP = 90.0F;
    /** Bring the fish's BODY onto the local origin so the flop rotates it about its own centre (not
     *  throwing it sideways). Vanilla fish models place the body ~22px (= 1.375 blocks) above the
     *  root pivot and a few px back along Z — this is {@code -bodyOffset/16}. NOT the 1.501 lift mob
     *  renderers use (that's for ~2-block humanoids). Salmon's body is at 20px; the 0.12-block
     *  difference is negligible. Tune in-game if a fish sits slightly off the point. */
    private static final float FISH_CENTER_Y = -1.375F; // -22/16
    private static final float FISH_CENTER_Z = -0.19F;  // ≈ -3/16, pierce mid-body
    /** Solid tint for the tropical fish (its base texture is an untinted silhouette). */
    private static final int TROPICAL_TINT = 0xFFF08000;
    private static final int NO_TINT = 0xFFFFFFFF;

    private static final ResourceLocation COD_TEX =
        ResourceLocation.withDefaultNamespace("textures/entity/fish/cod.png");
    private static final ResourceLocation SALMON_TEX =
        ResourceLocation.withDefaultNamespace("textures/entity/fish/salmon.png");
    private static final ResourceLocation PUFFERFISH_TEX =
        ResourceLocation.withDefaultNamespace("textures/entity/fish/pufferfish.png");
    private static final ResourceLocation TROPICAL_TEX =
        ResourceLocation.withDefaultNamespace("textures/entity/fish/tropical_a.png");

    private final ItemRenderer itemRenderer;
    private final EntityModel<Entity> codModel;
    private final EntityModel<Entity> salmonModel;
    private final EntityModel<Entity> pufferfishModel;
    private final EntityModel<Entity> tropicalModel;

    public SpearedFishEntityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.itemRenderer = ctx.getItemRenderer();
        this.codModel = new CodModel<>(ctx.bakeLayer(ModelLayers.COD));
        this.salmonModel = new SalmonModel<>(ctx.bakeLayer(ModelLayers.SALMON));
        this.pufferfishModel = new PufferfishBigModel<>(ctx.bakeLayer(ModelLayers.PUFFERFISH_BIG));
        this.tropicalModel = new TropicalFishModelA<>(ctx.bakeLayer(ModelLayers.TROPICAL_FISH_SMALL));
    }

    @Override
    public ResourceLocation getTextureLocation(SpearedFishEntity entity) {
        return COD_TEX; // unused (each part picks its own render type); base class requires it
    }

    @Override
    public void render(SpearedFishEntity entity, float entityYaw, float partialTicks, PoseStack pose,
                       MultiBufferSource buffer, int packedLight) {
        pose.pushPose();
        // Angle the WHOLE speared-fish along the direction the spear was travelling when it struck
        // (mirrors SpearProjectileRenderer's in-flight orientation), so spear + fish stay one rigid
        // catch tilted the way it pierced — not a fixed planted pose.
        pose.mulPose(Axis.YP.rotationDegrees(entity.getPierceYaw() - 90.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(entity.getPiercePitch() + SPEAR_MODEL_PITCH));

        // ── Spear: tip anchored at the origin, shaft trailing back along the flight axis ──
        ItemStack spear = entity.getSpearItem();
        if (!spear.isEmpty()) {
            pose.pushPose();
            pose.scale(SPEAR_SCALE, SPEAR_SCALE, SPEAR_SCALE);
            pose.translate(ANCHOR_X, ANCHOR_Y, ANCHOR_Z);
            this.itemRenderer.renderStatic(spear, ItemDisplayContext.NONE, packedLight,
                OverlayTexture.NO_OVERLAY, pose, buffer, entity.level(), entity.getId());
            pose.popPose();
        }

        // ── Fish: the real vanilla model, impaled near the tip ──
        EntityModel<Entity> model = modelFor(entity.getFishType());
        ResourceLocation texture = textureFor(entity.getFishType());
        int tint = "minecraft:tropical_fish".equals(entity.getFishType()) ? TROPICAL_TINT : NO_TINT;
        pose.pushPose();
        pose.translate(FISH_X, FISH_Y, FISH_Z);             // sit on the tip (= origin)
        pose.mulPose(Axis.ZP.rotationDegrees(FISH_FLOP));   // flop onto its side
        pose.mulPose(Axis.XP.rotationDegrees(FISH_PITCH));  // head-up/down tilt
        // Entity models are authored Y-down → mirror with scale(-1,-1,1), then translate the model's
        // off-pivot body onto the local origin so the flop above rotated it about its own centre.
        pose.scale(-FISH_SCALE, -FISH_SCALE, FISH_SCALE);
        pose.translate(0.0F, FISH_CENTER_Y, FISH_CENTER_Z);
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));
        model.renderToBuffer(pose, vc, packedLight, OverlayTexture.NO_OVERLAY, tint);
        pose.popPose();

        pose.popPose();

        // Rope-tethered catch: green rope back to the thrower's hand (base pose, no per-catch spin).
        // The owner is resolved via the synced entity id so it works for a player OR a spear-fisher
        // citizen NPC (the client can't find a citizen by UUID alone).
        if (entity.isTethered()
                && entity.getTetherOwner() instanceof net.minecraft.world.entity.LivingEntity owner) {
            RopeRenderer.render(pose, buffer, packedLight, partialTicks, entity, owner, 0.15F);
        }
        super.render(entity, entityYaw, partialTicks, pose, buffer, packedLight);
    }

    private EntityModel<Entity> modelFor(String fishType) {
        return switch (fishType) {
            case "minecraft:salmon" -> this.salmonModel;
            case "minecraft:pufferfish" -> this.pufferfishModel;
            case "minecraft:tropical_fish" -> this.tropicalModel;
            default -> this.codModel; // cod + any unexpected fish
        };
    }

    private ResourceLocation textureFor(String fishType) {
        return switch (fishType) {
            case "minecraft:salmon" -> SALMON_TEX;
            case "minecraft:pufferfish" -> PUFFERFISH_TEX;
            case "minecraft:tropical_fish" -> TROPICAL_TEX;
            default -> COD_TEX;
        };
    }
}
