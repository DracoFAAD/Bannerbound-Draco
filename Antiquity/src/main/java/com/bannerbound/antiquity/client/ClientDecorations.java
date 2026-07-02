package com.bannerbound.antiquity.client;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.deco.ChunkDecorations;
import com.bannerbound.antiquity.deco.FaceDecoEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client mirror of the server's per-face plaster/trim decorations, keyed by chunk. Fed by {@link
 * DecoClientHandler} from the sync payloads; read by {@link DecoSectionRenderer} during chunk meshing.
 * Any change re-bakes the affected section(s) so overlays appear/update without a relog.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientDecorations {
    private static final Map<Long, ChunkDecorations> CHUNKS = new ConcurrentHashMap<>();

    private ClientDecorations() {}

    /** The decorations of the chunk a section origin belongs to, or {@code null} if none. */
    public static ChunkDecorations chunkAt(int chunkX, int chunkZ) {
        return CHUNKS.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** Replace a chunk's decorations (full sync). Empty list clears it. Re-bakes that chunk's column. */
    public static void putChunk(int chunkX, int chunkZ, List<FaceDecoEntry> entries) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        if (entries.isEmpty()) {
            CHUNKS.remove(key);
        } else {
            CHUNKS.put(key, ChunkDecorations.fromEntries(entries));
        }
        invalidateChunkColumn(chunkX, chunkZ);
    }

    /** Apply a single-face edit and re-bake its section. */
    public static void applyUpdate(FaceDecoEntry e) {
        int cx = e.pos().getX() >> 4;
        int cz = e.pos().getZ() >> 4;
        long key = ChunkPos.asLong(cx, cz);
        ChunkDecorations cd = e.deco().isEmpty() ? CHUNKS.get(key)
            : CHUNKS.computeIfAbsent(key, k -> new ChunkDecorations());
        if (cd != null) {
            cd.set(e.pos(), e.dir(), e.deco());
            if (cd.isEmpty()) {
                CHUNKS.remove(key);
            }
        }
        invalidateSection(e.pos());
    }

    /** True if the local client believes any face of {@code pos} is plastered (mining-speed hook). */
    public static boolean hasPlaster(BlockPos pos) {
        ChunkDecorations cd = chunkAt(pos.getX() >> 4, pos.getZ() >> 4);
        return cd != null && cd.anyPlaster(pos);
    }

    public static void forgetChunk(int chunkX, int chunkZ) {
        if (CHUNKS.remove(ChunkPos.asLong(chunkX, chunkZ)) != null) {
            invalidateChunkColumn(chunkX, chunkZ);
        }
    }

    public static void clear() {
        CHUNKS.clear();
    }

    private static void invalidateSection(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer != null) {
            mc.levelRenderer.setSectionDirty(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        }
    }

    private static void invalidateChunkColumn(int chunkX, int chunkZ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.levelRenderer == null) {
            return;
        }
        for (int sy = mc.level.getMinSection(); sy < mc.level.getMaxSection(); sy++) {
            mc.levelRenderer.setSectionDirty(chunkX, sy, chunkZ);
        }
    }
}
