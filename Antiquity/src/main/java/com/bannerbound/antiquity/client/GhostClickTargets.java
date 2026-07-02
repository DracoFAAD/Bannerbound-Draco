package com.bannerbound.antiquity.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.WoodworkingTableBlockEntity;
import com.bannerbound.antiquity.block.entity.CraftingStoneBlockEntity;
import com.bannerbound.antiquity.block.entity.GhostRecipeWorkstation;
import com.bannerbound.antiquity.network.GhostActionPayload;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Click/hover target geometry for a workstation's ghost-preview UI: the floating ghost result (fill)
 * plus, when there are ≥2 candidate recipes, two browse arrows flanking it. Arrows sit perpendicular
 * to the camera's view so they always read as screen-left/right of the result — both the renderer
 * (billboard positions + hover highlight) and the click handler ray-test through here, so what you
 * see is exactly what you can click.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class GhostClickTargets {
    /** Horizontal distance from the floating result's center to each arrow. */
    private static final double ARROW_OFFSET = 0.55;
    private static final double ARROW_BOX = 0.32;
    private static final double RESULT_BOX = 0.5;

    /** One clickable element; {@code action} is the {@link GhostActionPayload} action it sends. */
    public record Target(int action, Vec3 center, AABB box) {}

    /** A ray-picked target and its squared distance from the ray origin. */
    public record Picked(Target target, double distSqr) {}

    /** The workstation + target the crosshair is currently over. */
    public record Hover(BlockPos pos, Picked picked) {}

    /** Horizontal / vertical block radius scanned for workstations with live ghost previews. */
    private static final int SCAN_XZ = 6;
    private static final int SCAN_Y = 4;

    private GhostClickTargets() {}

    /** The ghost target the player is aiming at right now (nearest, and only when it beats the vanilla
     *  block under the crosshair), or {@code null}. Shared by the click handler ({@code GhostClickEvents})
     *  and the green-crosshair affordance so they always agree on what's clickable. */
    @Nullable
    public static Hover findHovered(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.screen != null || player.isSpectator()) return null;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getViewVector(1.0F);
        double reach = player.blockInteractionRange();

        Picked best = null;
        BlockPos bestPos = null;
        for (BlockPos p : BlockPos.betweenClosed(
                player.blockPosition().offset(-SCAN_XZ, -SCAN_Y, -SCAN_XZ),
                player.blockPosition().offset(SCAN_XZ, SCAN_Y, SCAN_XZ))) {
            BlockEntity be = mc.level.getBlockEntity(p);
            if (!(be instanceof GhostRecipeWorkstation)) continue;
            Picked picked = pick(targetsFor(be, camera), eye, view, reach);
            if (picked != null && (best == null || picked.distSqr() < best.distSqr())) {
                best = picked;
                bestPos = p.immutable();
            }
        }
        if (best == null) return null;

        // Whatever the crosshair hits FIRST wins. If a solid block (even the workstation itself) is
        // closer than the floating target, normal block interaction takes it; otherwise the floating
        // target claims the click. Distance-based so a result floating above a short block (you're
        // aiming down at it from up close) is still clickable rather than ceded to the block below.
        HitResult vanilla = mc.hitResult;
        if (vanilla != null && vanilla.getType() != HitResult.Type.MISS
                && vanilla.getLocation().distanceToSqr(eye) < best.distSqr()) {
            return null;
        }
        return new Hover(bestPos, best);
    }

    /** This frame's targets for {@code be} (empty unless its ghost preview is showing). The ghost
     *  preview can coexist with a solid result (locked recipe + incidental exact match floating
     *  above it), so only the ghost matters here. */
    public static List<Target> targetsFor(BlockEntity be, Camera camera) {
        if (!(be instanceof GhostRecipeWorkstation ws)) return List.of();
        boolean hasGhost = !ws.getGhostResult().isEmpty();
        // The crafting stone floats a SOLID craftable result (an exact match, no ghost) at the same
        // spot — make THAT clickable too, so the player crafts straight from the floating preview
        // instead of the click falling through to the block (which now needs shift to craft).
        boolean exactMatch = !hasGhost && be instanceof CraftingStoneBlockEntity cs
            && !cs.getResult().isEmpty();
        if (!hasGhost && !exactMatch) return List.of();
        // The carpenter's table + mason's bench use the same compact picker band (ghostPreviewY 1.62),
        // so they share the tighter arrow/result hitboxes.
        boolean compact = be instanceof WoodworkingTableBlockEntity
            || be instanceof com.bannerbound.antiquity.block.entity.MasonsBenchBlockEntity;
        double arrowOffset = compact ? 0.38 : ARROW_OFFSET;
        double arrowBox = compact ? 0.24 : ARROW_BOX;
        double resultBox = compact ? 0.38 : RESULT_BOX;
        BlockPos pos = be.getBlockPos();
        Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + ws.ghostPreviewY(), pos.getZ() + 0.5);
        List<Target> out = new ArrayList<>(3);
        out.add(new Target(GhostActionPayload.FILL, center,
            AABB.ofSize(center, resultBox, resultBox, resultBox)));
        if (ws.getGhostCandidateCount() >= 2) {
            // Horizontal screen-left, from the camera (pitch keeps it level; degenerate looking
            // straight down with a rolled camera falls back to +X).
            var leftVec = camera.getLeftVector();
            Vec3 left = new Vec3(leftVec.x(), 0.0, leftVec.z());
            left = left.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : left.normalize();
            Vec3 l = center.add(left.scale(arrowOffset));
            Vec3 r = center.subtract(left.scale(arrowOffset));
            out.add(new Target(GhostActionPayload.CYCLE_LEFT, l,
                AABB.ofSize(l, arrowBox, arrowBox, arrowBox)));
            out.add(new Target(GhostActionPayload.CYCLE_RIGHT, r,
                AABB.ofSize(r, arrowBox, arrowBox, arrowBox)));
        }
        return out;
    }

    /** The nearest target the ray from {@code from} along {@code dir} hits within {@code reach}. */
    @Nullable
    public static Picked pick(List<Target> targets, Vec3 from, Vec3 dir, double reach) {
        Vec3 to = from.add(dir.normalize().scale(reach));
        Target best = null;
        double bestDist = Double.MAX_VALUE;
        for (Target t : targets) {
            Optional<Vec3> hit = t.box().clip(from, to);
            if (hit.isPresent()) {
                double d = hit.get().distanceToSqr(from);
                if (d < bestDist) {
                    bestDist = d;
                    best = t;
                }
            }
        }
        return best == null ? null : new Picked(best, bestDist);
    }
}
