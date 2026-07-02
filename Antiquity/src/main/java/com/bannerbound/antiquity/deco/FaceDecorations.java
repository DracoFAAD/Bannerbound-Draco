package com.bannerbound.antiquity.deco;

import java.util.EnumMap;
import java.util.Map;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.network.DecoUpdatePayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side API for the per-face plaster/trim decorations. Data lives in a chunk
 * {@linkplain net.neoforged.neoforge.attachment.AttachmentType attachment}
 * ({@link BannerboundAntiquity#CHUNK_DECORATIONS}) so it persists with the chunk; edits are pushed to
 * tracking clients via {@link DecoUpdatePayload}. The client mirror is
 * {@code com.bannerbound.antiquity.client.ClientDecorations}.
 */
public final class FaceDecorations {
    private FaceDecorations() {}

    public static ChunkDecorations of(LevelChunk chunk) {
        return chunk.getData(BannerboundAntiquity.CHUNK_DECORATIONS.get());
    }

    /** Server-side read of one face's decoration ({@link FaceDeco#EMPTY} if none). */
    public static FaceDeco get(ServerLevel level, BlockPos pos, Direction dir) {
        return of(level.getChunkAt(pos)).get(pos, dir);
    }

    /** Set or clear (when {@code deco.isEmpty()}) one face's decoration; persists + syncs to trackers. */
    public static void set(ServerLevel level, BlockPos pos, Direction dir, FaceDeco deco) {
        LevelChunk chunk = level.getChunkAt(pos);
        ChunkDecorations cd = of(chunk);
        FaceDeco value = deco == null ? FaceDeco.EMPTY : deco;
        if (cd.set(pos, dir, value)) {
            chunk.setData(BannerboundAntiquity.CHUNK_DECORATIONS.get(), cd);
            chunk.setUnsaved(true);
            PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(pos),
                new DecoUpdatePayload(new FaceDecoEntry(pos.immutable(), dir, value)));
        }
    }

    /**
     * Called when the block at {@code pos} is destroyed (player break / explosion): clear every face
     * decoration there, sync the removal, and — when {@code dropItems} — drop one plaster per
     * plastered face and one matching dye per trimmed face.
     */
    public static void onBlockRemoved(ServerLevel level, BlockPos pos, boolean dropItems) {
        LevelChunk chunk = level.getChunkAt(pos);
        ChunkDecorations cd = of(chunk);
        EnumMap<Direction, FaceDeco> removed = cd.removeAll(pos);
        if (removed == null || removed.isEmpty()) {
            return;
        }
        chunk.setData(BannerboundAntiquity.CHUNK_DECORATIONS.get(), cd);
        chunk.setUnsaved(true);

        ChunkPos chunkPos = new ChunkPos(pos);
        BlockPos key = pos.immutable();
        int plaster = 0;
        Map<DyeColor, Integer> dyes = new EnumMap<>(DyeColor.class);
        for (Map.Entry<Direction, FaceDeco> e : removed.entrySet()) {
            // Tell trackers this face is gone.
            PacketDistributor.sendToPlayersTrackingChunk(level, chunkPos,
                new DecoUpdatePayload(new FaceDecoEntry(key, e.getKey(), FaceDeco.EMPTY)));
            FaceDeco d = e.getValue();
            if (d.plaster()) {
                plaster++;
            }
            if (d.hasTrim()) {
                dyes.merge(d.trimColor(), 1, Integer::sum);
            }
        }
        if (!dropItems) {
            return;
        }
        if (plaster > 0) {
            Block.popResource(level, pos, new ItemStack(BannerboundAntiquity.PLASTER.get(), plaster));
        }
        dyes.forEach((color, count) ->
            Block.popResource(level, pos, new ItemStack(DyeItem.byColor(color), count)));
    }
}
