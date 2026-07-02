package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.RopeAnchor;
import com.bannerbound.antiquity.RopeTieHost;
import com.bannerbound.antiquity.RopeTieState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Matrix4f;

/**
 * Draws the green plant rope from a player's hand to a tethered spear / speared-fish catch — vanilla's
 * braided {@code leash} ribbon geometry (two offset triangle strips with alternating 0.7/1.0 shading
 * for the twist), but plant-green and a touch thicker. Mirrors {@code MobRenderer.renderLeash} /
 * {@code addVertexPair} on {@link RenderType#leash()} (POSITION_COLOR_LIGHTMAP), with the current
 * vertex API ({@code addVertex/setColor/setLight}, as used by {@code FisherBobberRenderer}).
 *
 * <p>The rope sags like a REAL fixed-length rope: it has a fixed paid-out length ({@link #ROPE_LENGTH}),
 * so when its two ends are close it droops in a deep U (lots of slack) and when they're near max span it
 * pulls nearly taut — rather than a line that just scales. See {@link #slackSag}.</p>
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class RopeRenderer {
    /** Plant-fibre green (0–255). */
    private static final int R = 96, G = 150, B = 58;
    /** Ribbon segments (vanilla uses 24). */
    private static final int SEGMENTS = 24;
    /** Rope thickness in blocks — a bit chunkier than the vanilla 0.025 lead for a "ropier" read. */
    private static final float THICKNESS = 0.05F;

    /** Fixed length of rope paid out (blocks) while LOOSE. Slack (and thus the preview droop) =
     *  this − span. A bit past the max horizontal tie so a long loose rope still has slack. */
    public static final float ROPE_LENGTH = 4.6F;
    /** Half the slack becomes sag depth (a rope folded in half hangs ~half its length deep). */
    private static final float SAG_FACTOR = 0.5F;
    /** Cap on the loose droop so the preview never dips through the ground (the tie sits ~0.8 up). */
    private static final float MAX_SLACK_SAG = 0.65F;
    /** Resting droop of a TIED (pulled-tight) rope — small and span-scaled, so it holds taut but still
     *  reads as a rope, never clipping the ground. */
    private static final float TIED_SAG_PER_BLOCK = 0.05F;
    private static final float MAX_TIED_SAG = 0.25F;
    /** Tie "zip": ticks the just-tied rope takes to pull from its loose droop up to taut (and stay). */
    private static final float ZIP_TICKS = 6.0F;

    private RopeRenderer() {}

    /** World-space point the rope ties to on the owner's hand. Players have the exact vanilla method;
     *  a citizen (or any other humanoid) gets a close estimate at the main-arm hand — a touch below the
     *  eyes, forward along the look, offset to the main-arm side — good enough for a rope anchor. */
    private static Vec3 ropeHoldPosition(LivingEntity owner, float partialTick) {
        if (owner instanceof Player player) {
            return player.getRopeHoldPosition(partialTick);
        }
        Vec3 eye = owner.getEyePosition(partialTick);
        Vec3 look = owner.getViewVector(partialTick);
        Vec3 side = new Vec3(-look.z, 0.0, look.x).normalize()
            .scale(owner.getMainArm() == HumanoidArm.RIGHT ? 0.35 : -0.35);
        return eye.add(look.scale(0.4)).add(side).add(0.0, -0.4, 0.0);
    }

    /** LOOSE sag depth (blocks) for a rope being paid out — deep U when the ends are close, shrinking
     *  toward taut as it nears full length; capped so it can't dip through the ground. */
    public static float slackSag(double dx, double dy, double dz) {
        double span = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return (float) Math.min(MAX_SLACK_SAG, Math.max(0.0, SAG_FACTOR * (ROPE_LENGTH - span)));
    }

    /** TIGHT sag depth (blocks) for a committed rope — pulled taut so it "holds", just a faint span-
     *  scaled catenary. */
    public static float tiedSag(double dx, double dy, double dz) {
        double span = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return (float) Math.min(MAX_TIED_SAG, TIED_SAG_PER_BLOCK * span);
    }

    /**
     * @param packedLight light to draw the rope at (the tethered entity's render light is fine).
     * @param attachY     height (blocks) above the entity origin where the rope ties on.
     */
    public static void render(PoseStack pose, MultiBufferSource buffer, int packedLight,
                              float partialTick, Entity tethered, LivingEntity owner, float attachY) {
        pose.pushPose();
        Vec3 hand = ropeHoldPosition(owner, partialTick);
        double ex = Mth.lerp(partialTick, tethered.xo, tethered.getX());
        double ey = Mth.lerp(partialTick, tethered.yo, tethered.getY());
        double ez = Mth.lerp(partialTick, tethered.zo, tethered.getZ());
        pose.translate(0.0, attachY, 0.0); // tie-on point on the entity
        float dx = (float) (hand.x - ex);
        float dy = (float) (hand.y - (ey + attachY));
        float dz = (float) (hand.z - ez);
        // The spear tether is a held line, not a slack fence rope: keep a mild, distance-scaled sag.
        float sag = (float) Math.min(0.4, Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.1);
        drawRibbon(pose, buffer, packedLight, dx, dy, dz, sag);
        pose.popPose();
    }

    /**
     * Draws the braided rope ribbon from the current pose origin out to the offset {@code (dx,dy,dz)}
     * (pose-space blocks), sagging by {@code sag} blocks at its middle. The caller owns the pose
     * push/translate.
     */
    public static void drawRibbon(PoseStack pose, MultiBufferSource buffer, int packedLight,
                                  float dx, float dy, float dz, float sag) {
        drawRibbon(pose, buffer, packedLight, dx, dy, dz, sag, R, G, B);
    }

    /** As {@link #drawRibbon(PoseStack, MultiBufferSource, int, float, float, float, float)} but with
     *  an explicit base colour (0–255) — e.g. plant-green for fiber rope, leather-brown for a lead. */
    public static void drawRibbon(PoseStack pose, MultiBufferSource buffer, int packedLight,
                                  float dx, float dy, float dz, float sag, int cr, int cg, int cb) {
        VertexConsumer line = buffer.getBuffer(RenderType.leash());
        Matrix4f matrix = pose.last().pose();
        // Perpendicular (in the XZ plane) that gives the ribbon its width, scaled to THICKNESS.
        float perp = Mth.invSqrt(dx * dx + dz * dz) * THICKNESS / 2.0F;
        float offZ = dz * perp;
        float offX = dx * perp;
        for (int i = 0; i <= SEGMENTS; i++) {
            addPair(line, matrix, dx, dy, dz, sag, packedLight, THICKNESS, offZ, offX, i, false, cr, cg, cb);
        }
        for (int i = SEGMENTS; i >= 0; i--) {
            addPair(line, matrix, dx, dy, dz, sag, packedLight, 0.0F, offZ, offX, i, true, cr, cg, cb);
        }
    }

    /**
     * Draws every rope this tie host owns the near end of — for each of its slots, to each connected
     * anchor, but only when this end is the lower-ordered one (so each rope is drawn exactly once).
     * Works for any tie host (post or gate) because {@link RopeAnchor#worldTie} resolves either kind.
     * {@code partialTick} drives the tie "zip" settle animation.
     */
    public static void renderHostRopes(BlockEntity be, RopeTieHost host, PoseStack pose,
                                       MultiBufferSource buffer, int packedLight, float partialTick) {
        Level level = be.getLevel();
        if (level == null) {
            return;
        }
        BlockPos pos = be.getBlockPos();
        for (int slot = 0; slot < host.slotCount(); slot++) {
            RopeAnchor local = new RopeAnchor(pos, slot);
            Vec3 lt = RopeAnchor.worldTie(level, local);
            if (lt == null) {
                continue;
            }
            for (RopeAnchor other : host.connections(slot)) {
                if (local.compareTo(other) >= 0) {
                    continue; // the lower-ordered end draws this rope
                }
                Vec3 ot = RopeAnchor.worldTie(level, other);
                if (ot == null) {
                    continue;
                }
                float dx = (float) (ot.x - lt.x), dy = (float) (ot.y - lt.y), dz = (float) (ot.z - lt.z);
                float sag = zipSag(dx, dy, dz, local, other, level.getGameTime(), partialTick);
                pose.pushPose();
                pose.translate(lt.x - pos.getX(), lt.y - pos.getY(), lt.z - pos.getZ());
                drawRibbon(pose, buffer, packedLight, dx, dy, dz, sag);
                pose.popPose();
            }
        }
    }

    /** Sag of a tied rope: normally its TIGHT resting droop; but for the first moments after it's tied
     *  (see {@link RopeTieState}) it pulls from the LOOSE droop up to tight — the rope cinching taut and
     *  staying that way. */
    private static float zipSag(float dx, float dy, float dz, RopeAnchor a, RopeAnchor b,
                               long gameTime, float partialTick) {
        float tight = tiedSag(dx, dy, dz);
        float p = RopeTieState.zipProgress(a, b, gameTime, partialTick, ZIP_TICKS);
        if (p < 0.0F) {
            return tight; // settled: stays taut
        }
        float loose = slackSag(dx, dy, dz);
        float ease = p * p; // ease-in: hangs loose, then snaps tight at the end
        return Mth.lerp(ease, loose, tight);
    }

    /** One vertex pair of the ribbon at segment {@code i} (vanilla's leash geometry + a slack sag). */
    private static void addPair(VertexConsumer line, Matrix4f matrix, float dx, float dy, float dz,
                                float sag, int light, float w2, float offZ, float offX, int i, boolean flip,
                                int cr, int cg, int cb) {
        float t = (float) i / SEGMENTS;
        float shade = (i % 2 == (flip ? 1 : 0)) ? 0.7F : 1.0F; // alternating strands → braided look
        int r = (int) (cr * shade);
        int g = (int) (cg * shade);
        int b = (int) (cb * shade);
        float x = dx * t;
        // Straight-line height interpolation, pulled DOWN by a parabolic sag (peak at the middle).
        float y = dy * t - sag * 4.0F * t * (1.0F - t);
        float z = dz * t;
        line.addVertex(matrix, x - offZ, y + w2, z + offX).setColor(r, g, b, 255).setLight(light);
        line.addVertex(matrix, x + offZ, y + THICKNESS - w2, z - offX).setColor(r, g, b, 255).setLight(light);
    }
}
