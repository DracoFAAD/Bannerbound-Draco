package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.entity.SpearProjectile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Renders the flying {@link SpearProjectile} as its 3D spear model, oriented along flight. The
 * model is pulled from the carried spear stack and drawn in {@link ItemDisplayContext#NONE} — the
 * spear item models route NONE to the 3D geometry (see {@code models/item/*_spear.json}), so this
 * gets the raw model with no in-hand transform baked in.
 *
 * <p>The authored spear model lies along the diagonal of its local XY plane (butt at the bottom,
 * tip up-and-to-the-right ≈ 45° up). After the arrow-style yaw/pitch orientation, {@link
 * #MODEL_PITCH_CORRECTION} rotates that diagonal down so the tip leads the flight, and the
 * recenter translate moves the off-origin model onto the entity. All four constants below are
 * <b>visual tunables</b> — tweak in-game until the spear points the way it flies and sits centered.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class SpearProjectileRenderer extends EntityRenderer<SpearProjectile> {
    /** Brings the model's 45°-up diagonal down to the flight axis (the "rotate it 45° down"). */
    private static final float MODEL_PITCH_CORRECTION = -45.0F;
    /** Overall size of the rendered spear. */
    private static final float MODEL_SCALE = 1.0F;

    // Anchor the spear's TIP at the entity origin (the hit point) rather than its center, so the
    // shaft extends back OUT of the ground / out of the mob and only the head is buried — instead
    // of the model floating beside a mob or sinking half-under the surface. The authored model's
    // tip is at ~(30, 28, 8.5) model units; in blocks (units / 16) that's the point below.
    private static final float TIP_X = 30.0F / 16.0F;  // 1.875
    private static final float TIP_Y = 28.0F / 16.0F;  // 1.75
    private static final float TIP_Z = 8.5F / 16.0F;   // 0.53125
    /** How far (blocks) to back the tip out along the shaft so the head still reads as embedded but
     *  not fully swallowed. 0 = tip exactly at the hit point; ~0.10–0.18 looks "stuck". Tunable. */
    private static final float TIP_BURY = 0.15F;
    // ItemRenderer.renderStatic applies an internal translate(-0.5,-0.5,-0.5) before drawing the
    // model, so to land the model's TIP on the entity origin we offset by (0.5 - tip), not (-tip)
    // — otherwise the whole (off-origin) model is pushed ~0.5/axis away and the rotation swings it
    // far off the hitbox (the "spear floating beside its box" bug).
    private static final float ANCHOR_X = 0.5F - TIP_X + TIP_BURY;
    private static final float ANCHOR_Y = 0.5F - TIP_Y + TIP_BURY;
    private static final float ANCHOR_Z = 0.5F - TIP_Z;

    /** Unused for item-model rendering (the model carries its own texture), but the base class
     *  requires a texture; point it at one of the spear model textures. */
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "textures/item/wooden_spear_model.png");

    private final ItemRenderer itemRenderer;

    public SpearProjectileRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.itemRenderer = ctx.getItemRenderer();
    }

    @Override
    public ResourceLocation getTextureLocation(SpearProjectile entity) {
        return TEXTURE;
    }

    @Override
    public void render(SpearProjectile spear, float entityYaw, float partialTicks, PoseStack pose,
                       MultiBufferSource buffer, int packedLight) {
        ItemStack stack = spear.getSpearItem();
        if (stack.isEmpty()) {
            super.render(spear, entityYaw, partialTicks, pose, buffer, packedLight);
            return;
        }
        pose.pushPose();
        // Arrow-style orientation: face the travel heading, then pitch up/down.
        pose.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTicks, spear.yRotO, spear.getYRot()) - 90.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(
            Mth.lerp(partialTicks, spear.xRotO, spear.getXRot()) + MODEL_PITCH_CORRECTION));
        pose.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
        pose.translate(ANCHOR_X, ANCHOR_Y, ANCHOR_Z);
        this.itemRenderer.renderStatic(stack, ItemDisplayContext.NONE, packedLight,
            OverlayTexture.NO_OVERLAY, pose, buffer, spear.level(), spear.getId());
        pose.popPose();
        // Rope-tethered throw: draw the green rope back to the thrower's hand (pose is at origin).
        // LivingEntity covers a player OR a spear-fisher citizen NPC.
        if (spear.isRopeTethered() && spear.getOwner() instanceof net.minecraft.world.entity.LivingEntity owner) {
            RopeRenderer.render(pose, buffer, packedLight, partialTicks, spear, owner, 0.1F);
        }
        super.render(spear, entityYaw, partialTicks, pose, buffer, packedLight);
    }
}
