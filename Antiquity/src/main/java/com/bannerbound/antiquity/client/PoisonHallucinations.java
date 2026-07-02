package com.bannerbound.antiquity.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Belladonna's phantom dread — featureless black FIGURES (a translucent humanoid model wearing the
 * {@code phantom.png} skin) that appear at the EDGE of the player's vision and vanish the instant
 * they're looked at directly. Pure client-side hallucination: no real entity, no collision, no sound
 * of their own. Driven each client tick by {@link PoisonClientEffects} while belladonna-poisoned.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public final class PoisonHallucinations {
    private static final class Phantom {
        final double x;
        final double y;
        final double z;
        final long spawnTick;
        final long expireTick;
        long lookFadeStart = -1L; // game-time the player looked at it (-1 = not yet dissolving)

        Phantom(double x, double y, double z, long spawnTick, long expireTick) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.spawnTick = spawnTick;
            this.expireTick = expireTick;
        }
    }

    private static final List<Phantom> PHANTOMS = new ArrayList<>();
    private static final int MAX = 2;
    /** Looking within this cosine of a phantom (≈ this side-angle) makes it begin to dissolve. */
    private static final double LOOK_VANISH_DOT = 0.86; // ~31°
    /** How long a phantom takes to fade out once you look at it (≈ 0.2s) — a quick dissolve, not a pop. */
    private static final long LOOK_FADE_TICKS = 4L;
    /** Peak opacity (0-255) — kept low so the figures are faint/ghostly. */
    private static final float MAX_ALPHA = 105.0F;
    private static final ResourceLocation PHANTOM_TEX =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "textures/entity/phantom.png");

    private static PlayerModel<LivingEntity> model;

    private PoisonHallucinations() {}

    public static void clear() {
        PHANTOMS.clear();
    }

    /** Cull figures that expired or that the player turned to face; occasionally spawn a new one at
     *  the edge of view (more often at higher stages). */
    public static void tick(LocalPlayer player, int stage, RandomSource rng) {
        long now = player.level().getGameTime();
        Vec3 eye = player.getEyePosition();
        Vec3 lookFlat = horiz(player.getViewVector(1.0F));
        // Drop figures that naturally expired or that have finished dissolving from a look.
        PHANTOMS.removeIf(p -> now >= p.expireTick
            || (p.lookFadeStart >= 0 && now - p.lookFadeStart >= LOOK_FADE_TICKS));
        // Looking at one BEGINS its dissolve (a smooth fade, not an instant pop).
        for (Phantom p : PHANTOMS) {
            if (p.lookFadeStart < 0) {
                Vec3 to = horiz(new Vec3(p.x - eye.x, 0.0, p.z - eye.z));
                if (lookFlat.dot(to) > LOOK_VANISH_DOT) {
                    p.lookFadeStart = now;
                }
            }
        }
        // Steep per-stage curve so low stages are rare and stage 4 is frequent: stage² × 0.004 →
        // ~1 every 12s at stage 1, ~1 every 4s at stage 2, climbing to ~1 per second at stage 4.
        if (PHANTOMS.size() < MAX && rng.nextFloat() < stage * stage * 0.004F) {
            spawn(player, rng, now);
        }
    }

    private static void spawn(LocalPlayer player, RandomSource rng, long now) {
        double side = rng.nextBoolean() ? 1.0 : -1.0;
        double offset = Math.toRadians(48.0 + rng.nextDouble() * 34.0); // 48-82° off to the side
        double yaw = Math.toRadians(player.getYRot()) + side * offset;
        double dx = -Math.sin(yaw);
        double dz = Math.cos(yaw);
        double dist = 6.0 + rng.nextDouble() * 7.0;
        double x = player.getX() + dx * dist;
        double z = player.getZ() + dz * dist;
        double y = player.getY(); // stands at the player's feet height (fine on roughly level ground)
        long life = 40L + rng.nextInt(60); // 2–5 s
        PHANTOMS.add(new Phantom(x, y, z, now, now + life));
    }

    private static Vec3 horiz(Vec3 v) {
        Vec3 f = new Vec3(v.x, 0.0, v.z);
        return f.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0) : f.normalize();
    }

    private static PlayerModel<LivingEntity> model() {
        if (model == null) {
            try {
                ModelPart root = Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER);
                model = new PlayerModel<>(root, false);
                model.young = false; // EntityModel.young defaults TRUE → baby proportions; force adult
            } catch (Exception e) {
                return null;
            }
        }
        return model;
    }

    @SubscribeEvent
    static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || PHANTOMS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (mc.level == null || player == null) {
            return;
        }
        PlayerModel<LivingEntity> m = model();
        if (m == null) {
            return;
        }
        long now = mc.level.getGameTime();
        Camera cam = event.getCamera();
        Vec3 camPos = cam.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        RenderType rt = RenderType.entityTranslucent(PHANTOM_TEX);
        var vc = buf.getBuffer(rt);
        for (Phantom p : PHANTOMS) {
            int alpha = phantomAlpha(p, now);
            if (alpha <= 0) {
                continue;
            }
            // Body yaw to face the player.
            float faceYaw = (float) Math.toDegrees(Math.atan2(p.x - player.getX(), player.getZ() - p.z));
            pose.pushPose();
            pose.translate(p.x - camPos.x, p.y - camPos.y, p.z - camPos.z);
            pose.mulPose(Axis.YP.rotationDegrees(faceYaw));
            pose.scale(-1.0F, -1.0F, 1.0F);   // entity-model space (Y/X flipped)
            pose.translate(0.0F, -1.501F, 0.0F); // feet to the ground
            int color = (alpha << 24) | 0xFFFFFF;
            m.renderToBuffer(pose, vc, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, color);
            pose.popPose();
        }
        buf.endBatch(rt);
    }

    /** Fade in over the first ~8 ticks, hold, fade out over the last ~8 — and dissolve when looked at. */
    private static int phantomAlpha(Phantom p, long now) {
        long age = now - p.spawnTick;
        long left = p.expireTick - now;
        float fade = Math.min(age / 8.0F, Math.min(left / 8.0F, 1.0F));
        if (p.lookFadeStart >= 0) {
            float lookFade = 1.0F - (now - p.lookFadeStart) / (float) LOOK_FADE_TICKS;
            fade = Math.min(fade, Math.max(0.0F, lookFade));
        }
        return (int) (Math.max(0.0F, Math.min(1.0F, fade)) * MAX_ALPHA);
    }
}
