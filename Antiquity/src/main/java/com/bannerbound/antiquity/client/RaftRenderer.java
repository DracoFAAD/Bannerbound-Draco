package com.bannerbound.antiquity.client;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.entity.RaftEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Renders the {@link RaftEntity}. The pose transform matches vanilla {@code BoatRenderer} (so the
 * raft sits and tilts on the water the same way), but it draws our {@link RaftModel} and plays a
 * {@link RaftAnimations} paddle loop chosen from the raft's rowing state. No water-patch / variant
 * map — there's a single raft model and texture.
 */
@OnlyIn(Dist.CLIENT)
public class RaftRenderer extends EntityRenderer<RaftEntity> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "textures/entity/raft.png");

    private final RaftModel model;
    private final Vector3f animationCache = new Vector3f();

    public RaftRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.9F;
        this.model = new RaftModel(context.bakeLayer(RaftModel.LAYER_LOCATION));
    }

    @Override
    public void render(RaftEntity entity, float entityYaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        // The model was exported at the Blockbench ground baseline (parts around y≈24px), so it needs
        // a large lift to align the deck with the entity. In water the entity floats ~0.45 below the
        // surface, so the deck rides right at 1.2–1.8 (the damage gauge: full integrity = 1.8, wrecked
        // = 1.2, see RaftEntity#getIntegrityFraction). On dry land the entity sits ON the ground, so
        // we drop that float-depth back off — otherwise the raft visibly hovers.
        // Shared with the collision deck-part height (RaftEntity#renderFloatHeight) so the walkable
        // surface always matches the model — and it keys off "water below" rather than isInWater(),
        // which toggled as the raft bobbed and made the model jump.
        poseStack.translate(0.0F, entity.renderFloatHeight(), 0.0F);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - entityYaw));

        float hurt = (float) entity.getHurtTime() - partialTicks;
        float damage = Math.max(entity.getDamage() - partialTicks, 0.0F);
        if (hurt > 0.0F) {
            poseStack.mulPose(Axis.XP.rotationDegrees(
                Mth.sin(hurt) * hurt * damage / 10.0F * (float) entity.getHurtDir()));
        }

        float bubble = entity.getBubbleAngle(partialTicks);
        if (!Mth.equal(bubble, 0.0F)) {
            poseStack.mulPose(new Quaternionf().setAngleAxis(bubble * (float) (Math.PI / 180.0), 1.0F, 0.0F, 1.0F));
        }

        poseStack.scale(-1.0F, -1.0F, 1.0F);
        // Vanilla adds +90° here to point its X-long boat model down the Z (travel) axis. This raft
        // model is already built long-axis along Z, so no extra spin — it would otherwise sit
        // broadside. If the pointed bow ends up facing aft, change this to 180°.
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));

        this.model.root().getAllParts().forEach(ModelPart::resetPose);
        AnimationDefinition rowing = rowingAnimation(entity);
        if (rowing != null) {
            long timeMs = (long) ((entity.tickCount + partialTicks) * 50.0F);
            KeyframeAnimations.animate(this.model, rowing, timeMs, 1.0F, this.animationCache);
        }

        VertexConsumer vc = buffer.getBuffer(this.model.renderType(TEXTURE));
        this.model.renderToBuffer(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);

        poseStack.popPose();

        // NB: we deliberately do NOT call super.render — NeoForge's EntityRenderer#render auto-draws a
        // vanilla leash for any Leashable, which would be a SECOND rope from the wrong anchor. We draw
        // the one rope ourselves (from the notch, coloured by rope type) and replicate the name tag.
        Entity holder = entity.getLeashHolder();
        if (holder != null) {
            renderTowRope(entity, partialTicks, poseStack, buffer, packedLight, holder);
        }
        if (this.shouldShowName(entity)) {
            this.renderNameTag(entity, entity.getDisplayName(), poseStack, buffer, packedLight, partialTicks);
        }
    }

    /** Plant-fibre green / leather-brown base colours (0–255) for the two rope kinds. */
    private static final int FIBER_R = 96, FIBER_G = 150, FIBER_B = 58;
    private static final int LEAD_R = 128, LEAD_G = 102, LEAD_B = 76;

    private static void renderTowRope(RaftEntity raft, float partialTick, PoseStack poseStack,
                                      MultiBufferSource buffer, int packedLight, Entity holder) {
        Vec3 notch = raft.getNotchPosition(partialTick);
        double ex = Mth.lerp(partialTick, raft.xo, raft.getX());
        double ey = Mth.lerp(partialTick, raft.yo, raft.getY());
        double ez = Mth.lerp(partialTick, raft.zo, raft.getZ());
        Vec3 hold = holder.getRopeHoldPosition(partialTick);
        float dx = (float) (hold.x - notch.x);
        float dy = (float) (hold.y - notch.y);
        float dz = (float) (hold.z - notch.z);
        float sag = (float) Math.min(0.5, Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.12);
        poseStack.pushPose();
        poseStack.translate(notch.x - ex, notch.y - ey, notch.z - ez);
        if (raft.isFiberLeash()) {
            RopeRenderer.drawRibbon(poseStack, buffer, packedLight, dx, dy, dz, sag, FIBER_R, FIBER_G, FIBER_B);
        } else {
            RopeRenderer.drawRibbon(poseStack, buffer, packedLight, dx, dy, dz, sag, LEAD_R, LEAD_G, LEAD_B);
        }
        poseStack.popPose();
    }

    /**
     * Pick a paddle loop from the synced paddle state. Vanilla sets both paddles while rowing
     * forward and a single side while turning (see {@code Boat#controlBoat}). Returns null when
     * idle, so the model stays in its rest pose.
     */
    @Nullable
    private static AnimationDefinition rowingAnimation(RaftEntity entity) {
        boolean left = entity.getPaddleState(0);
        boolean right = entity.getPaddleState(1);
        if (left && right) {
            return RaftAnimations.PADDLE_FORWARD;
        } else if (left) {
            return RaftAnimations.PADDLE_LEFT;
        } else if (right) {
            return RaftAnimations.PADDLE_RIGHT;
        }
        return null;
    }

    @Override
    public ResourceLocation getTextureLocation(RaftEntity entity) {
        return TEXTURE;
    }
}
