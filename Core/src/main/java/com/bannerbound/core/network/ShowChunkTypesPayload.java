package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: a (2·radius+1)² grid of {@code ChunkResource} ordinals (row-major, north→south,
 * west→east) centred on a chunk, for the {@code /bannerbound chunktype <radius>} debug overlay. The
 * client can't compute the typing itself (it lacks the world seed), so the server sends the rolled
 * grid and the client floats an icon + label over each non-empty chunk for {@code durationTicks}.
 */
@ApiStatus.Internal
public record ShowChunkTypesPayload(int centerX, int centerZ, int radius, byte[] ordinals, int durationTicks)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ShowChunkTypesPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "show_chunk_types"));

    public static final StreamCodec<ByteBuf, ShowChunkTypesPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ShowChunkTypesPayload::centerX,
            ByteBufCodecs.VAR_INT, ShowChunkTypesPayload::centerZ,
            ByteBufCodecs.VAR_INT, ShowChunkTypesPayload::radius,
            ByteBufCodecs.BYTE_ARRAY, ShowChunkTypesPayload::ordinals,
            ByteBufCodecs.VAR_INT, ShowChunkTypesPayload::durationTicks,
            ShowChunkTypesPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
