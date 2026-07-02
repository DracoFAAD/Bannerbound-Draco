package com.bannerbound.antiquity.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.deco.FaceDecoEntry;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server → client: the full face-decoration set of one chunk (sent when a player starts tracking
 *  it). An empty list clears the client's cache for that chunk. */
@ApiStatus.Internal
public record DecoChunkSyncPayload(int chunkX, int chunkZ, List<FaceDecoEntry> entries)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DecoChunkSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "deco_chunk_sync"));

    public static final StreamCodec<ByteBuf, DecoChunkSyncPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DecoChunkSyncPayload::chunkX,
            ByteBufCodecs.VAR_INT, DecoChunkSyncPayload::chunkZ,
            FaceDecoEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), DecoChunkSyncPayload::entries,
            DecoChunkSyncPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
