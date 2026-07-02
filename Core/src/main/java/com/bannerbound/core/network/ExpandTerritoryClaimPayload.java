package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → server. Sent when the player clicks a purchasable chunk in the
 *  ExpandTerritoryScreen. {@link #packedChunkPos} is a long-packed {@link
 *  net.minecraft.world.level.ChunkPos}; server re-validates all preconditions
 *  (adjacency, unclaimed, era cap, biome cost, item availability, population). */
@ApiStatus.Internal
public record ExpandTerritoryClaimPayload(long packedChunkPos) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ExpandTerritoryClaimPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "expand_territory_claim"));

    public static final StreamCodec<ByteBuf, ExpandTerritoryClaimPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeLong(p.packedChunkPos()),
        buf -> new ExpandTerritoryClaimPayload(buf.readLong())
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
