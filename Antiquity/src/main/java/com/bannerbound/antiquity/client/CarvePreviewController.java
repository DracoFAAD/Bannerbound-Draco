package com.bannerbound.antiquity.client;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Carves;
import com.bannerbound.core.client.UnknownItemHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Drives the in-world "what will this carve into?" ghost preview (see {@link Carves}). While the
 * local player aims at a carveable block with the right tool, the looked-at block(s) are hidden on
 * the CLIENT — set to air, the supported relight/remesh path, the same idiom as {@code RopeTies}'
 * coil preview — and {@link CarveGhostRenderer} draws the result blockstate(s) as a translucent
 * silhouette in their place. Looking away restores the real block(s); the carve is committed through
 * {@link CarveClickEvents} (the hidden block can't be ray-clicked, so the use-key is intercepted and
 * replayed server-side via {@code Carves.commit}).
 *
 * <p>Everything here is client-only and cosmetic: the server is never told the block is "air", and
 * the authoritative carve still runs server-side through the normal handlers.
 *
 * <h4>Why the hysteresis</h4>
 * Hiding the looked-at block means the crosshair ray now passes straight through it, so re-resolving
 * against that air every tick would drop and re-acquire the preview forever (flicker). Once a
 * preview is anchored we instead KEEP it while the player is still looking through the anchor cell
 * with the same tool, and only drop it when the aim leaves the cell, the tool changes, or the cell
 * stops being air (the carve committed / the world changed).
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class CarvePreviewController {

    /** Ticks the same target must be aimed at before the swap commits — stops a crosshair sweep
     *  across many carveable blocks from churning section re-meshes. */
    private static final int DEBOUNCE_TICKS = 2;
    /** A generous reach for "still looking through the anchor cell" (blocks). */
    private static final double KEEP_REACH = 6.0;

    /** Result states to draw as ghosts, at positions we've blanked to air. Read by the renderer. */
    private static Map<BlockPos, BlockState> ghosts = Map.of();
    /** The real states we replaced with air, to restore on look-away / change. */
    private static final Map<BlockPos, BlockState> originals = new LinkedHashMap<>();
    /** The primary block the active preview is anchored to (null = no active preview). */
    private static @Nullable BlockPos anchor;
    /** The tool that produced the active preview — the preview drops if the player swaps away. */
    private static @Nullable Item anchorTool;

    /** Debounce bookkeeping for the candidate target during acquisition. */
    private static @Nullable String pendingKey;
    private static int pendingAge;

    private CarvePreviewController() {}

    /** Result blockstates to draw, keyed by the (client-hidden) world position. Empty when inactive. */
    public static Map<BlockPos, BlockState> ghosts() {
        return ghosts;
    }

    /** The anchor of the active preview, or null — {@link CarveClickEvents} forwards this on commit. */
    public static @Nullable BlockPos activeAnchor() {
        return anchor;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null || mc.screen != null || player.isSecondaryUseActive()) {
            clear(level); // sneaking = "place the held block", so never preview then
            return;
        }

        if (anchor != null) {
            if (stillValid(player, level)) {
                return; // keep the active preview
            }
            clear(level);
            return; // re-acquire next tick, once the restored block is back under the crosshair
        }

        acquire(mc, level, player);
    }

    private static void acquire(Minecraft mc, Level level, LocalPlayer player) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            resetPending();
            return;
        }
        ItemStack held = player.getMainHandItem();
        Carves.Result candidate = Carves.resolve(level, hit.getBlockPos(), player, held);
        if (candidate == null
                || !UnknownItemHelper.isKnown(candidate.gateItem())   // don't ghost-spoil locked carves
                || standingOn(player, candidate)) {                   // never blank the block under our feet
            resetPending();
            return;
        }
        // Debounce: only commit after the same target has been aimed at for a couple of ticks.
        String key = signature(candidate);
        if (!key.equals(pendingKey)) {
            pendingKey = key;
            pendingAge = 0;
            return;
        }
        if (++pendingAge < DEBOUNCE_TICKS) {
            return;
        }
        apply(level, hit.getBlockPos().immutable(), held.getItem(), candidate);
    }

    private static void apply(Level level, BlockPos anchorPos, Item tool, Carves.Result candidate) {
        Map<BlockPos, BlockState> drawn = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : candidate.blocks().entrySet()) {
            BlockPos p = e.getKey().immutable();
            if (!level.isLoaded(p)) {
                continue;
            }
            originals.put(p, level.getBlockState(p));
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            drawn.put(p, e.getValue());
        }
        if (drawn.isEmpty()) {
            originals.clear();
            return;
        }
        ghosts = drawn;
        anchor = anchorPos;
        anchorTool = tool;
        resetPending();
    }

    /** Whether the active preview should persist this tick. */
    private static boolean stillValid(LocalPlayer player, Level level) {
        if (anchor == null || anchorTool == null || player.getMainHandItem().getItem() != anchorTool) {
            return false;
        }
        // If the carve committed (server placed the result) or the world otherwise changed a blanked
        // cell, it's no longer air — drop the preview. restore()'s isAir guard then leaves it alone.
        for (BlockPos p : originals.keySet()) {
            if (!level.isLoaded(p) || !level.getBlockState(p).isAir()) {
                return false;
            }
        }
        // Still looking through the anchor cell, within reach.
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(KEEP_REACH));
        return new AABB(anchor).inflate(0.05).clip(eye, end).isPresent();
    }

    /** Restore the blanked cells we still own (those the server hasn't overwritten), then forget them. */
    private static void restore(@Nullable Level level) {
        if (level != null) {
            for (Map.Entry<BlockPos, BlockState> e : originals.entrySet()) {
                BlockPos p = e.getKey();
                if (level.isLoaded(p) && level.getBlockState(p).isAir()) {
                    level.setBlock(p, e.getValue(), Block.UPDATE_CLIENTS);
                }
            }
        }
        originals.clear();
    }

    private static void clear(@Nullable Level level) {
        if (!originals.isEmpty()) {
            restore(level);
        }
        ghosts = Map.of();
        anchor = null;
        anchorTool = null;
        resetPending();
    }

    /**
     * Called when the player commits the carve: restore the world and drop the preview. The server
     * places the real result a moment later (or, if it rejects the carve, the block simply stays as
     * it was — so there's no stuck ghost on a server-side gate failure).
     */
    public static void clearForCommit() {
        clear(Minecraft.getInstance().level);
    }

    private static boolean standingOn(LocalPlayer player, Carves.Result candidate) {
        BlockPos feet = player.blockPosition();
        for (BlockPos p : candidate.blocks().keySet()) {
            if (p.equals(feet) || p.equals(feet.below())) {
                return true;
            }
        }
        return false;
    }

    private static String signature(Carves.Result candidate) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<BlockPos, BlockState> e : candidate.blocks().entrySet()) {
            sb.append(e.getKey().asLong()).append('=').append(Block.getId(e.getValue())).append(';');
        }
        return sb.toString();
    }

    private static void resetPending() {
        pendingKey = null;
        pendingAge = 0;
    }
}
