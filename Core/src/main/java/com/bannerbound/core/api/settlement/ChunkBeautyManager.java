package com.bannerbound.core.api.settlement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Server-side driver for chunk beauty. Owns the tracked-chunk set, the debounced rescan loop,
 * and the culture contribution + beauty-tag queries the rest of the mod reads.
 *
 * <ul>
 *   <li><b>Tracked chunks</b> = every settlement's claimed chunks plus their 8-neighbour ring
 *       (the ring is scored only for the expand-territory preview). Rebuilt periodically by
 *       {@link #recomputeTrackedSet} straight from {@link SettlementData}, so no claim/disband
 *       hooks are needed.</li>
 *   <li><b>Scanning</b> is lazy + budgeted: a tracked chunk is scanned the first second it is
 *       loaded; dirty chunks are rescanned (at most {@value #MAX_SCANS_PER_SECOND}/second).</li>
 *   <li><b>Safety net</b>: one chunk per second is re-marked dirty round-robin, so block changes
 *       that don't fire player events (fluids, explosions, leaf decay) self-heal within minutes.</li>
 * </ul>
 */
@ApiStatus.Internal
public final class ChunkBeautyManager {
    private ChunkBeautyManager() {
    }

    /** Hard cap on full block-rescans per second, so a burst of edits can't spike a tick. */
    private static final int MAX_SCANS_PER_SECOND = 8;
    /** How often the tracked-chunk set is rebuilt from current claims. */
    private static final int TRACKED_SET_REFRESH_SECONDS = 10;

    private static int tickCounter = 0;
    private static int secondCounter = 0;
    private static int safetyCursor = 0;

    /** Per-tick entry point (called every server tick); acts once per second. */
    public static void tickAll(MinecraftServer server) {
        if (server == null) return;
        if (++tickCounter < 20) return;
        tickCounter = 0;
        secondCounter++;
        ServerLevel overworld = server.overworld();

        if (secondCounter % TRACKED_SET_REFRESH_SECONDS == 1) {
            recomputeTrackedSet(overworld);
        }

        ChunkBeautyData data = ChunkBeautyData.get(overworld);
        if (data.chunks().isEmpty()) return;
        SettlementData sd = SettlementData.get(overworld);

        // Safety net: re-dirty one tracked chunk per second, round-robin.
        List<Long> keys = new ArrayList<>(data.chunks().keySet());
        long safety = keys.get(Math.floorMod(safetyCursor++, keys.size()));
        ChunkAppealData safetyEntry = data.chunks().get(safety);
        if (safetyEntry != null && safetyEntry.isScanned()) {
            safetyEntry.markDirty();
        }

        // Budgeted rescan of unscanned + dirty chunks.
        int budget = MAX_SCANS_PER_SECOND;
        for (Map.Entry<Long, ChunkAppealData> e : data.chunks().entrySet()) {
            if (budget <= 0) break;
            ChunkAppealData cad = e.getValue();
            if (cad.isScanned() && !cad.isDirty()) continue;
            if (scanChunk(overworld, sd, e.getKey(), cad)) {
                budget--;
                data.setDirty();
            }
        }

        // Refresh every scanned chunk's base score under its owning settlement's styles, so the
        // culture + adjacency reads below see fresh base tags. Cheap — no block reads, and
        // recomputeScore self-skips when neither queues nor styles changed.
        for (Map.Entry<Long, ChunkAppealData> e : data.chunks().entrySet()) {
            ChunkAppealData cad = e.getValue();
            if (!cad.isScanned()) continue;
            Settlement owner = sd.getByChunk(e.getKey());
            cad.recomputeScore(owner != null ? owner.cultureStyles() : List.of(),
                owner != null ? owner.activePalettes() : List.of());
        }

        // Homes: rescore when the owning settlement's styles/palettes CHANGED (the audited
        // stale-cache bug — chunks refreshed on style changes, home scores didn't until the next
        // block edit) plus a slow safety sweep. Budgeted so a big settlement's style change
        // spreads its rescans over a few seconds instead of spiking one.
        int homeBudget = 4;
        long now = overworld.getGameTime();
        for (Settlement s : sd.all()) {
            if (homeBudget <= 0) break;
            int styleHash = s.cultureStyles().hashCode() * 31 + s.activePalettes().hashCode();
            for (Home h : s.homes().values()) {
                if (homeBudget <= 0) break;
                boolean styleChanged = h.lastScoredStyleHash() != styleHash;
                boolean stale = now - h.lastScoredTick() > 6_000; // 5 min safety sweep
                if (!styleChanged && !stale) continue;
                HouseAppealData.scoreOf(overworld, s, h);
                h.setLastScoredStyleHash(styleHash);
                homeBudget--;
            }
        }
    }

    /** Rebuilds the tracked-chunk set from current claims: claimed chunks + their 8-neighbours.
     *  New chunks start unscanned; chunks no longer near any claim are dropped. */
    public static void recomputeTrackedSet(ServerLevel level) {
        ChunkBeautyData data = ChunkBeautyData.get(level);
        SettlementData sd = SettlementData.get(level);

        Set<Long> desired = new HashSet<>();
        for (Settlement s : sd.all()) {
            for (long claimed : s.claimedChunks()) {
                ChunkPos cp = new ChunkPos(claimed);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        desired.add(new ChunkPos(cp.x + dx, cp.z + dz).toLong());
                    }
                }
            }
        }
        for (long c : desired) {
            data.track(c);
        }
        boolean removed = false;
        for (Iterator<Long> it = data.chunks().keySet().iterator(); it.hasNext(); ) {
            if (!desired.contains(it.next())) {
                it.remove();
                removed = true;
            }
        }
        if (removed) data.setDirty();
    }

    /** Records a player-placed block: it joins its chunk's diminishing-returns queue in
     *  placement order. If the chunk isn't tracked yet OR hasn't completed its initial scan,
     *  we still TRACK it and mark it dirty so the next tick batch (within ~125ms at the 8/s
     *  scan budget) picks it up — otherwise placements made in fresh territory or in the
     *  10-second tracked-set-refresh window were silently lost, producing a stale debug
     *  overlay and stale culture rates on freshly-claimed chunks. */
    public static void onBlockPlaced(ServerLevel level, BlockPos pos, Block block) {
        // Two-tall plants/doors are tracked at their lower half only — ignore an upper-half place so
        // the queue (and thus appeal) doesn't double-count. The lower half's own event records it.
        if (AppealResolver.isAppealDuplicateHalf(level.getBlockState(pos))) return;
        ChunkBeautyData data = ChunkBeautyData.get(level);
        long key = new ChunkPos(pos).toLong();
        ChunkAppealData cad = data.get(key);
        if (cad == null) {
            data.track(key);
            cad = data.get(key);
            data.setDirty();
        }
        if (cad == null) return;
        if (cad.isScanned()) {
            cad.recordPlace(pos, block);
        } else {
            cad.markDirty();
        }
        data.setDirty();
    }

    /** Records a player-broken block: it leaves its chunk's queue and the rest clamp up.
     *  Mirrors {@link #onBlockPlaced} — if the chunk wasn't yet scanned, force-track + dirty
     *  so the rescan picks the change up rather than losing it. */
    public static void onBlockRemoved(ServerLevel level, BlockPos pos) {
        ChunkBeautyData data = ChunkBeautyData.get(level);
        long key = new ChunkPos(pos).toLong();
        ChunkAppealData cad = data.get(key);
        if (cad == null) {
            data.track(key);
            cad = data.get(key);
            data.setDirty();
        }
        if (cad == null) return;
        if (cad.isScanned()) {
            cad.recordBreak(pos);
        } else {
            cad.markDirty();
        }
        data.setDirty();
    }

    /** Total culture-per-second the settlement's <i>claimed</i> chunks contribute. A chunk's
     *  <i>effective</i> beauty is its base score plus the {@link #adjacencyBonus} from its
     *  neighbours, re-mapped through the beauty thresholds; culture is read off that. Base
     *  scores are kept fresh by {@link #tickAll}. */
    public static double cultureBonus(ServerLevel level, Settlement s) {
        if (s == null) return 0.0;
        ChunkBeautyData data = ChunkBeautyData.get(level);
        double sum = 0.0;
        for (long claimed : s.claimedChunks()) {
            ChunkAppealData cad = data.get(claimed);
            if (cad == null || !cad.isScanned()) continue;
            ChunkBeauty effective =
                ChunkBeauty.fromScore(cad.score() + adjacencyBonus(level, claimed));
            sum += effective.culturePerSecond();
        }
        return sum;
    }

    /** The four orthogonally-adjacent chunk offsets (N/S/E/W) — adjacency counts faces only. */
    private static final int[][] ADJACENT_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * Adjacency bonus for a chunk: the summed <i>base</i> tier index (−4..+4) of its four
     * orthogonally-adjacent (N/S/E/W) neighbours, then halved (rounded down) so adjacency stays
     * a gentle nudge rather than a dominant term. Diagonals do not count. This is a single
     * non-recursive hop — neighbours contribute their own base tier, never their
     * adjacency-adjusted tier — so it can't cascade into a feedback loop. Untracked / unscanned
     * neighbours count as bland (0).
     */
    public static int adjacencyBonus(ServerLevel level, long packedChunk) {
        ChunkBeautyData data = ChunkBeautyData.get(level);
        ChunkPos cp = new ChunkPos(packedChunk);
        int sum = 0;
        for (int[] d : ADJACENT_DIRS) {
            ChunkAppealData n = data.get(new ChunkPos(cp.x + d[0], cp.z + d[1]).toLong());
            if (n != null && n.isScanned()) {
                sum += n.tag().tierIndex();
            }
        }
        return Math.floorDiv(sum, 2);
    }

    /** Base beauty tag of a tracked chunk, or {@code null} if it isn't tracked / scanned yet.
     *  Kept fresh by {@link #tickAll}. Used by the expand-territory preview. */
    public static ChunkBeauty beautyOf(ServerLevel level, long packedChunk) {
        ChunkAppealData cad = ChunkBeautyData.get(level).get(packedChunk);
        return (cad != null && cad.isScanned()) ? cad.tag() : null;
    }

    /** A tracked chunk's <i>effective</i> beauty — base score + adjacency bonus, re-mapped
     *  through the beauty thresholds. {@code null} if the chunk isn't tracked / scanned. */
    public static ChunkBeauty effectiveBeautyOf(ServerLevel level, long packedChunk) {
        ChunkAppealData cad = ChunkBeautyData.get(level).get(packedChunk);
        if (cad == null || !cad.isScanned()) return null;
        return ChunkBeauty.fromScore(cad.score() + adjacencyBonus(level, packedChunk));
    }

    /** Captures references (first time) + recounts a chunk's blocks. Returns false if the chunk
     *  isn't loaded yet — the scan is simply retried a later second. */
    private static boolean scanChunk(ServerLevel level, SettlementData sd, long packed,
                                     ChunkAppealData cad) {
        ChunkPos cp = new ChunkPos(packed);
        if (!level.hasChunk(cp.x, cp.z)) return false;
        LevelChunk chunk = level.getChunk(cp.x, cp.z);
        if (!cad.isScanned()) {
            cad.captureReferences(chunk);
        }
        cad.fullScan(chunk);
        Settlement owner = sd.getByChunk(packed);
        cad.recomputeScore(owner != null ? owner.cultureStyles() : List.of(),
            owner != null ? owner.activePalettes() : List.of());
        cad.markScanned();
        cad.clearDirty();
        return true;
    }
}
