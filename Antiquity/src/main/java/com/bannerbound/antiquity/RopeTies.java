package com.bannerbound.antiquity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.antiquity.block.RopeCollisionBlock;
import com.bannerbound.antiquity.block.RopeFenceGateBlock;
import com.bannerbound.antiquity.block.RopeFencePostBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Shared rope-tie logic for everything that hosts ropes ({@link RopeTieHost}: posts and gates). Owns
 * the in-progress tie state, the link/break operations, the invisible collision fillers along a span,
 * and the per-anchor "roped" model flag — so posts and gates behave identically and can rope to each
 * other (post↔post, post↔gate, gate↔gate) through one code path.
 */
@ApiStatus.Internal
public final class RopeTies {
    /** Longest HORIZONTAL span (blocks) a single rope may bridge — keeps you from fencing a whole
     *  village off a handful of posts. */
    public static final double MAX_ROPE_HORIZONTAL = 4.0;
    /** Largest VERTICAL drop/rise (blocks) a single rope may bridge — generous enough for terraced ground. */
    public static final double MAX_ROPE_VERTICAL = 5.0;

    /** Server-side only: each player's loose end mid-tie. Transient — not worth persisting. */
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    /** Per-dimension count of rope tie hosts per chunk — a cheap, reliable "is a rope nearby" gate for
     *  the collision (markers can have coverage gaps; tie hosts always cover their whole rope span). */
    private static final Map<ResourceKey<Level>, Map<Long, Integer>> HOST_CHUNKS = new HashMap<>();

    private record Pending(RopeAnchor anchor, ResourceKey<Level> dimension) {}

    private RopeTies() {}

    /** A rope tie host BE entered the world (placement or chunk load) — register its chunk. */
    public static void onHostLoad(Level level, ChunkPos chunk) {
        HOST_CHUNKS.computeIfAbsent(level.dimension(), k -> new HashMap<>())
            .merge(chunk.toLong(), 1, Integer::sum);
    }

    /** A rope tie host BE left the world (break or chunk unload) — deregister its chunk. */
    public static void onHostUnload(Level level, ChunkPos chunk) {
        Map<Long, Integer> m = HOST_CHUNKS.get(level.dimension());
        if (m == null) {
            return;
        }
        Integer c = m.get(chunk.toLong());
        if (c != null) {
            if (c <= 1) {
                m.remove(chunk.toLong());
            } else {
                m.put(chunk.toLong(), c - 1);
            }
        }
    }

    /** Is there a rope tie host in the 3×3 chunks around ({@code chunkX},{@code chunkZ})? */
    public static boolean hostChunkNear(Level level, int chunkX, int chunkZ) {
        Map<Long, Integer> m = HOST_CHUNKS.get(level.dimension());
        if (m == null || m.isEmpty()) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (m.containsKey(ChunkPos.asLong(chunkX + dx, chunkZ + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ── Tie host lookup ─────────────────────────────────────────────────────────────────────

    @Nullable
    public static RopeTieHost hostAt(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof RopeTieHost host ? host : null;
    }

    /** Which tie slot a click at {@code hit} targets — for a gate, the nearer upright; else slot 0. */
    public static int slotForHit(Level level, BlockPos pos, Vec3 hit) {
        if (!(level.getBlockState(pos).getBlock() instanceof RopeFenceGateBlock)) {
            return 0;
        }
        Vec3 left = RopeAnchor.worldTie(level, new RopeAnchor(pos, 0));
        Vec3 right = RopeAnchor.worldTie(level, new RopeAnchor(pos, 1));
        if (left == null || right == null) {
            return 0;
        }
        return hit.distanceToSqr(left) <= hit.distanceToSqr(right) ? 0 : 1;
    }

    // ── Tie interaction (right-click with rope) ─────────────────────────────────────────────

    /** Client-side: track the local player's anchor for the preview line (idempotent, no toggle). */
    public static void handleTieClient(Level level, RopeAnchor anchor) {
        if (RopeTieState.get() == null) {
            RopeTieState.set(anchor.immutable(), level.dimension());
        } else if (!RopeTieState.isAt(anchor, level.dimension())) {
            // Second tie point → link ends the preview; record the new rope so it animates zipping taut.
            RopeTieState.recordZip(RopeTieState.get(), anchor, level.getGameTime());
            RopeTieState.clear();
        }
    }

    /** Server-side: first click selects, a second (different) tie point links, re-clicking is a no-op.
     *  Returns true if a rope was actually created (so the caller can spend a fiber rope). */
    public static boolean handleTieServer(Level level, Player player, RopeAnchor anchor) {
        UUID id = player.getUUID();
        Pending pending = PENDING.get(id);
        if (pending == null) {
            startTie(player, level, anchor);
            return false;
        }
        if (pending.dimension().equals(level.dimension()) && pending.anchor().equals(anchor)) {
            return false; // re-clicking the same tie point: keep tying (no toggle)
        }
        PENDING.remove(id);
        if (!pending.dimension().equals(level.dimension())) {
            restoreModelInDimension(player, pending);
            startTie(player, level, anchor);
            return false;
        }
        Vec3 from = RopeAnchor.worldTie(level, pending.anchor());
        Vec3 to = RopeAnchor.worldTie(level, anchor);
        if (from == null || to == null) {
            return false; // a tie host is gone
        }
        double horizontal = Math.sqrt((to.x - from.x) * (to.x - from.x) + (to.z - from.z) * (to.z - from.z));
        if (horizontal > MAX_ROPE_HORIZONTAL || Math.abs(to.y - from.y) > MAX_ROPE_VERTICAL) {
            player.displayClientMessage(Component.translatable("message.bannerboundantiquity.rope_fence.too_far"), true);
            level.playSound(null, anchor.pos(), SoundEvents.LEASH_KNOT_BREAK, SoundSource.BLOCKS, 1.0F, 1.0F);
            refreshRoped(level, pending.anchor());
            return false;
        }
        RopeTieHost a = hostAt(level, pending.anchor().pos());
        RopeTieHost b = hostAt(level, anchor.pos());
        if (a == null || b == null || a.connections(pending.anchor().slot()).contains(anchor)) {
            refreshRoped(level, pending.anchor()); // already roped (or host gone): no-op, no action-bar text
            return false;
        }
        link(level, pending.anchor(), anchor);
        level.playSound(null, anchor.pos(), SoundEvents.LEASH_KNOT_PLACE, SoundSource.BLOCKS, 1.0F, 0.8F);
        return true;
    }

    private static void startTie(Player player, Level level, RopeAnchor anchor) {
        PENDING.put(player.getUUID(), new Pending(anchor.immutable(), level.dimension()));
        setRopedModel(level, anchor, true); // show its coil while tying (server-authoritative)
        // No action-bar text — the knot sound + the coil model + the live preview rope are feedback enough.
        level.playSound(null, anchor.pos(), SoundEvents.LEASH_KNOT_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    public static boolean isPendingAnchorAt(Player player, BlockPos pos) {
        Pending p = PENDING.get(player.getUUID());
        return p != null && p.anchor().pos().equals(pos) && p.dimension().equals(player.level().dimension());
    }

    /** Cancel a player's in-progress tie and restore the anchor's model. */
    public static void clearPending(Player player) {
        Pending p = PENDING.remove(player.getUUID());
        if (p != null) {
            restoreModelInDimension(player, p);
        }
    }

    private static void restoreModelInDimension(Player player, Pending p) {
        Level lvl = player.level().dimension().equals(p.dimension())
            ? player.level()
            : (player.getServer() != null ? player.getServer().getLevel(p.dimension()) : null);
        if (lvl != null) {
            refreshRoped(lvl, p.anchor());
        }
    }

    // ── Link / break ────────────────────────────────────────────────────────────────────────

    public static void link(Level level, RopeAnchor a, RopeAnchor b) {
        RopeTieHost ha = hostAt(level, a.pos());
        RopeTieHost hb = hostAt(level, b.pos());
        if (ha == null || hb == null) {
            return;
        }
        ha.addConnection(a.slot(), b.immutable());
        hb.addConnection(b.slot(), a.immutable());
        refreshRoped(level, a);
        refreshRoped(level, b);
        placeFillers(level, a, b);
    }

    /** Remove the rope between {@code a} and {@code b}: both endpoints, the fillers, the model flags. */
    public static void removeLink(Level level, RopeAnchor a, RopeAnchor b) {
        RopeTieHost ha = hostAt(level, a.pos());
        RopeTieHost hb = hostAt(level, b.pos());
        RopeAnchor owner = a.compareTo(b) <= 0 ? a : b;
        RopeAnchor other = owner == a ? b : a;
        RopeTieHost ownerHost = owner == a ? ha : hb;
        if (ownerHost != null) {
            removeFillerCells(level, ownerHost.getFillers(other));
            ownerHost.putFillers(other, List.of());
        }
        if (ha != null) {
            ha.removeConnection(a.slot(), b);
            refreshRoped(level, a);
        }
        if (hb != null) {
            hb.removeConnection(b.slot(), a);
            refreshRoped(level, b);
        }
    }

    /** Break the most recent rope on {@code host} (any slot) and drop the rope. For the left-click QOL. */
    public static boolean breakOne(Level level, BlockPos pos, RopeTieHost host) {
        for (int slot = host.slotCount() - 1; slot >= 0; slot--) {
            RopeAnchor other = null;
            for (RopeAnchor c : host.connections(slot)) {
                other = c; // insertion-ordered → last is most recent
            }
            if (other != null) {
                removeLink(level, new RopeAnchor(pos, slot), other);
                Block.popResource(level, pos, new ItemStack(BannerboundAntiquity.FIBER_ROPE.get(), 1));
                level.playSound(null, pos, SoundEvents.LEASH_KNOT_BREAK, SoundSource.BLOCKS, 1.0F, 1.0F);
                return true;
            }
        }
        return false;
    }

    /** On block removal: cut every rope on this host, drop them all, clear all fillers. */
    public static void breakAllAndDrop(Level level, BlockPos pos, RopeTieHost host) {
        int dropped = 0;
        for (int slot = 0; slot < host.slotCount(); slot++) {
            for (RopeAnchor other : new ArrayList<>(host.connections(slot))) {
                removeLink(level, new RopeAnchor(pos, slot), other);
                dropped++;
            }
        }
        if (dropped > 0 && !level.isClientSide) {
            Block.popResource(level, pos, new ItemStack(BannerboundAntiquity.FIBER_ROPE.get(), dropped));
        }
    }

    public static boolean isConnectedAnySlot(RopeTieHost host) {
        for (int slot = 0; slot < host.slotCount(); slot++) {
            if (host.isConnected(slot)) {
                return true;
            }
        }
        return false;
    }

    // ── "Roped" model flag ──────────────────────────────────────────────────────────────────

    /** Set the post's ROPED / the gate slot's ROPED_LEFT|RIGHT to match its real connection state. */
    public static void refreshRoped(Level level, RopeAnchor anchor) {
        RopeTieHost host = hostAt(level, anchor.pos());
        setRopedModel(level, anchor, host != null && host.isConnected(anchor.slot()));
    }

    /** Force the post/gate-slot roped model on or off. Server uses it during a tie; the client also uses
     *  it to PREVIEW the coil on a post you're aiming at (reverted via {@link #refreshRoped}). */
    public static void setRopedModel(Level level, RopeAnchor anchor, boolean roped) {
        BlockState st = level.getBlockState(anchor.pos());
        if (st.getBlock() instanceof RopeFencePostBlock) {
            if (st.getValue(RopeFencePostBlock.ROPED) != roped) {
                level.setBlock(anchor.pos(), st.setValue(RopeFencePostBlock.ROPED, roped), Block.UPDATE_CLIENTS);
            }
        } else if (st.getBlock() instanceof RopeFenceGateBlock) {
            var prop = anchor.slot() == 0 ? RopeFenceGateBlock.ROPED_LEFT : RopeFenceGateBlock.ROPED_RIGHT;
            if (st.getValue(prop) != roped) {
                level.setBlock(anchor.pos(), st.setValue(prop, roped), Block.UPDATE_CLIENTS);
            }
        }
    }

    // ── Collision fillers along the span ──────────────────────────────────────────────────────

    private static void placeFillers(Level level, RopeAnchor a, RopeAnchor b) {
        Vec3 from = RopeAnchor.worldTie(level, a);
        Vec3 to = RopeAnchor.worldTie(level, b);
        if (from == null || to == null) {
            return;
        }
        RopeAnchor owner = a.compareTo(b) <= 0 ? a : b;
        RopeAnchor other = owner == a ? b : a;
        RopeTieHost ownerHost = hostAt(level, owner.pos());
        if (ownerHost == null) {
            return;
        }
        // Orient each filler band along the rope and offset it to where the rope crosses that cell.
        double dx = to.x - from.x, dz = to.z - from.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        double ux = len == 0 ? 1.0 : dx / len, uz = len == 0 ? 0.0 : dz / len;
        int seg = Math.floorMod((int) Math.round(Math.atan2(dz, dx) / (Math.PI / 8.0)), 16);
        double segAng = seg * (Math.PI / 8.0);
        double nX = -Math.sin(segAng), nZ = Math.cos(segAng);
        BlockState base = BannerboundAntiquity.ROPE_COLLISION.get().defaultBlockState()
            .setValue(RopeCollisionBlock.ROTATION, seg);
        List<BlockPos> placed = new ArrayList<>();
        for (BlockPos cell : gapCells(from, to, a.pos(), b.pos())) {
            BlockState cellState = level.getBlockState(cell);
            // Fill air AND replaceable plants (grass, ferns, flowers, snow) — but never fluids — so the
            // rope blocks (and mobs path around) even where it crosses vegetation.
            if (cellState.getFluidState().isEmpty() && cellState.canBeReplaced()) {
                double cx = cell.getX() + 0.5, cz = cell.getZ() + 0.5;
                double proj = (cx - from.x) * ux + (cz - from.z) * uz;
                double qx = from.x + proj * ux, qz = from.z + proj * uz;
                double offPx = (nX * (qx - cx) + nZ * (qz - cz)) * 16.0;
                int off = Mth.clamp((int) Math.round(offPx) + RopeCollisionBlock.OFFSET_ZERO,
                    0, RopeCollisionBlock.OFFSET_MAX);
                level.setBlock(cell, base.setValue(RopeCollisionBlock.OFFSET, off), Block.UPDATE_ALL);
                placed.add(cell);
            }
        }
        ownerHost.putFillers(other, placed);
    }

    private static void removeFillerCells(Level level, List<BlockPos> cells) {
        for (BlockPos c : cells) {
            if (level.getBlockState(c).is(BannerboundAntiquity.ROPE_COLLISION.get())) {
                level.removeBlock(c, false);
            }
        }
    }

    /** Owner hosts whose fillers were already re-validated this session (see {@link #refreshFillersOnce}). */
    private static final Map<ResourceKey<Level>, Set<Long>> FILLERS_REFRESHED = new HashMap<>();

    /** One-time (per session) filler re-placement for every rope OWNED by {@code host} — heals spans saved
     *  by older versions whose markers only covered the rope line's own cells, leaving A*-walkable holes
     *  over mid-span rises/dips and occupied cells. Called from the collision segment gather, so it only
     *  runs server-side while an entity moves nearby — both ends' chunks are then already loaded and
     *  setBlock is safe (never during chunk load, which could force-generate neighbours). Idempotent. */
    public static void refreshFillersOnce(Level level, BlockPos hostPos, RopeTieHost host) {
        if (level.isClientSide) {
            return;
        }
        Set<Long> done = FILLERS_REFRESHED.computeIfAbsent(level.dimension(), k -> new HashSet<>());
        if (!done.add(hostPos.asLong())) {
            return;
        }
        for (int slot = 0; slot < host.slotCount(); slot++) {
            RopeAnchor local = new RopeAnchor(hostPos, slot);
            for (RopeAnchor other : new ArrayList<>(host.connections(slot))) {
                if (local.compareTo(other) > 0) {
                    continue; // the other end owns this rope's fillers and refreshes them itself
                }
                if (level.getChunkSource().getChunkNow(other.pos().getX() >> 4, other.pos().getZ() >> 4) == null) {
                    done.remove(hostPos.asLong()); // far end not loaded — retry on a later gather
                    return;
                }
                removeFillerCells(level, host.getFillers(other));
                placeFillers(level, local, other);
            }
        }
    }

    /** Height of the analytical rope wall — MUST match {@code RopeFenceCollision.FENCE_HEIGHT}, so the
     *  marker band covers exactly the cells the wall physically blocks. */
    private static final double WALL_HEIGHT = 2.0;

    /** Marker cells for the span between the two tie points, minus the end blocks. For every COLUMN the
     *  rope crosses, this yields the full vertical band the analytical wall blocks in that column
     *  ({@code ropeY - TIE_Y} up {@link #WALL_HEIGHT}) — not just the single cell the rope line passes
     *  through. Sampling only the line left A*-walkable holes wherever terrain rose or dipped mid-span
     *  (a 1-block rise put the line cell inside the ground, and the walk cell on TOP of the rise had no
     *  marker) or the line cell held a crop/torch/water: the pathfinder then routed straight "through"
     *  the rope and the clamp ground the mob against the invisible wall forever — the classic
     *  citizen-stuck-at-the-pen bug. Banding also covers the head cell, so a mob whose feet are below
     *  the wall (1-deep dip) but whose body the wall still blocks sees a FENCE node too. */
    private static List<BlockPos> gapCells(Vec3 from, Vec3 to, BlockPos endA, BlockPos endB) {
        List<BlockPos> out = new ArrayList<>();
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, Mth.ceil(dist * 3.0));
        Set<Long> seen = new HashSet<>();
        int bandCells = Mth.ceil(WALL_HEIGHT); // [wallBottom, wallBottom + WALL_HEIGHT) exclusive top plane
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int x = Mth.floor(from.x + dx * t);
            int z = Mth.floor(from.z + dz * t);
            // Skip the tie hosts' WHOLE columns, not just their own cells: the post/gate block already
            // blocks its cell, and a head-height marker ABOVE an open GATE would read as a FENCE inside
            // the bounding box of any mob pathing through it (the node evaluator merges path types over
            // the full mob BB) — silently re-sealing the one legal way into the pen.
            if ((x == endA.getX() && z == endA.getZ()) || (x == endB.getX() && z == endB.getZ())) {
                continue;
            }
            // Bottom of the analytical wall in this column (the rope ties TIE_Y above the post base).
            int yBase = Mth.floor(from.y + dy * t - RopeAnchor.TIE_Y);
            for (int y = yBase; y < yBase + bandCells; y++) {
                if (seen.add(BlockPos.asLong(x, y, z))) {
                    out.add(new BlockPos(x, y, z));
                }
            }
        }
        return out;
    }

    // ── Rendering ───────────────────────────────────────────────────────────────────────────

    /** Width of the AABB padding so a rope isn't frustum-culled when only the far end is on screen. */
    public static net.minecraft.world.phys.AABB renderBounds(BlockPos pos, RopeTieHost host) {
        double minX = pos.getX(), minY = pos.getY(), minZ = pos.getZ();
        double maxX = minX + 1, maxY = minY + 1, maxZ = minZ + 1;
        for (int slot = 0; slot < host.slotCount(); slot++) {
            for (RopeAnchor o : host.connections(slot)) {
                minX = Math.min(minX, o.pos().getX());
                minY = Math.min(minY, o.pos().getY());
                minZ = Math.min(minZ, o.pos().getZ());
                maxX = Math.max(maxX, o.pos().getX() + 1.0);
                maxY = Math.max(maxY, o.pos().getY() + 1.0);
                maxZ = Math.max(maxZ, o.pos().getZ() + 1.0);
            }
        }
        return new net.minecraft.world.phys.AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
