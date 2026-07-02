package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.FisherBobber;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Matrix4f;

/**
 * Renders the {@link FisherBobber} entity — a small camera-facing quad textured with vanilla's
 * fishing_hook sprite, plus a fishing line strip back to the owning citizen's hand. The line
 * math is a simplified copy of vanilla's {@code FishingHookRenderer}: hand-position offset is
 * derived from the citizen's body rotation + main arm, line stretched as a single segment
 * (vanilla draws 16 sub-segments for a slight curve; we keep it straight for v1).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class FisherBobberRenderer extends EntityRenderer<FisherBobber> {
    private static final ResourceLocation TEXTURE_LOCATION =
        ResourceLocation.withDefaultNamespace("textures/entity/fishing_hook.png");
    private static final RenderType BOBBER_TYPE = RenderType.entityCutout(TEXTURE_LOCATION);

    public FisherBobberRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public ResourceLocation getTextureLocation(FisherBobber entity) {
        return TEXTURE_LOCATION;
    }

    @Override
    public void render(FisherBobber bobber, float entityYaw, float partialTicks, PoseStack pose,
                       MultiBufferSource buffer, int packedLight) {
        CitizenEntity owner = bobber.getOwnerCitizen(bobber.level());

        // ─── Bobber quad ───────────────────────────────────────────────────────────────────────
        pose.pushPose();
        // Match vanilla FishingHookRenderer: scale + billboard, NO extra Y rotation. The errant
        // 180° spin previously here flipped the quad backwards so face-culling hid it entirely.
        pose.scale(0.5f, 0.5f, 0.5f);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        PoseStack.Pose entry = pose.last();
        Matrix4f matrix = entry.pose();
        VertexConsumer consumer = buffer.getBuffer(BOBBER_TYPE);
        vertex(consumer, matrix, entry, packedLight, 0.0f, 0, 0, 1);
        vertex(consumer, matrix, entry, packedLight, 1.0f, 0, 1, 1);
        vertex(consumer, matrix, entry, packedLight, 1.0f, 1, 1, 0);
        vertex(consumer, matrix, entry, packedLight, 0.0f, 1, 0, 0);
        pose.popPose();

        // ─── Fishing line back to the citizen's hand ──────────────────────────────────────────
        if (owner != null) {
            Vec3 handWorld = approximateHandPosition(owner, partialTicks);
            Vec3 bobberWorld = new Vec3(
                Mth.lerp(partialTicks, bobber.xo, bobber.getX()),
                Mth.lerp(partialTicks, bobber.yo, bobber.getY()) + 0.25,
                Mth.lerp(partialTicks, bobber.zo, bobber.getZ()));
            // Vector from bobber → hand, in the bobber's local frame (pose stack is already
            // translated to the bobber's position by the caller before render()).
            float dx = (float) (handWorld.x - bobberWorld.x);
            float dy = (float) (handWorld.y - bobberWorld.y);
            float dz = (float) (handWorld.z - bobberWorld.z);
            // RenderType.lines() (GL_LINES, vertex pairs), NOT lineStrip(): all fishers'
            // line vertices share one batched buffer, and a line STRIP would connect the end
            // of one fisher's line to the start of the next's, drawing spurious lines between
            // citizens (visible especially under Iris, which flushes the buffer differently).
            // GL_LINES treats each 2-vertex pair as an independent segment, so they stay separate.
            VertexConsumer line = buffer.getBuffer(RenderType.lines());
            Matrix4f m = pose.last().pose();
            // Bobber end: 0.25 blocks above the entity origin so the line attaches above the
            // bobber sprite, not through it. Hand end: exact hand position (no extra offset).
            line.addVertex(m, 0, 0.25f, 0).setColor(0, 0, 0, 255).setNormal(entry, 0f, 1f, 0f);
            line.addVertex(m, dx, dy, dz).setColor(0, 0, 0, 255).setNormal(entry, 0f, 1f, 0f);
        }

        super.render(bobber, entityYaw, partialTicks, pose, buffer, packedLight);
    }

    /** World position of the citizen's rod tip — where the fishing line should attach. Ports
     *  vanilla {@code FishingHookRenderer.getPlayerHandPos}: forward offset is 0.8 (not 0.2 —
     *  that put the anchor at the hand inside the citizen's body, so the line emerged through
     *  the torso instead of from the rod tip), lateral offset is 0.35 in the body's right
     *  direction (negated for left-handed), and Y sits at eye-height-minus-0.6 (chest height,
     *  where the rod is held out — keeps the line off the shoulder).
     *  <p>Reads body yaw (not head yaw) so the rod tip moves with the body. The fisher goal
     *  locks body yaw to the cardinal outward direction during the cast, which keeps this
     *  anchor stable and prevents the "line clipping through torso" symptom. */
    private static Vec3 approximateHandPosition(CitizenEntity citizen, float partialTicks) {
        double cx = Mth.lerp(partialTicks, citizen.xo, citizen.getX());
        double cy = Mth.lerp(partialTicks, citizen.yo, citizen.getY());
        double cz = Mth.lerp(partialTicks, citizen.zo, citizen.getZ());
        float bodyYaw = Mth.lerp(partialTicks, citizen.yBodyRotO, citizen.yBodyRot);
        double rad = Math.toRadians(bodyYaw);
        double sinY = Math.sin(rad);
        double cosY = Math.cos(rad);
        int armOffset = (citizen.getMainArm() == HumanoidArm.RIGHT) ? 1 : -1;
        // Anchor the line at the rod tip, which the citizen holds out around chest height — lower
        // than the eye-height-minus-0.2 vanilla uses, so the line emerges from the rod rather than
        // up by the shoulder. Seated on a vessel (the sailing fisher) the body sits lower and the
        // rod is held over the gunwale: pull the anchor down and in so the line doesn't appear to
        // start out past the bow or up at standing height.
        boolean seated = citizen.isPassenger();
        double forward = seated ? 0.55 : 0.8;
        double handY = cy + citizen.getEyeHeight() - (seated ? 0.85 : 0.6);
        // Vanilla formula: x' = x - cos(yaw) * arm * 0.35 - sin(yaw) * forward
        //                  z' = z - sin(yaw) * arm * 0.35 + cos(yaw) * forward
        double handX = cx - cosY * armOffset * 0.35 - sinY * forward;
        double handZ = cz - sinY * armOffset * 0.35 + cosY * forward;
        return new Vec3(handX, handY, handZ);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, PoseStack.Pose entry,
                                int packedLight, float u, int v, int texU, int texV) {
        consumer.addVertex(matrix, u - 0.5f, (float) v - 0.5f, 0.0f)
            .setColor(0xFFFFFFFF)
            .setUv((float) texU, (float) texV)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(packedLight)
            .setNormal(entry, 0.0f, 1.0f, 0.0f);
    }
}
