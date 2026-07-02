package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

@ApiStatus.Internal
public record ClaimSyncPayload(List<ClaimEntry> claims) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ClaimSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "claim_sync"));

    public static final StreamCodec<ByteBuf, ClaimSyncPayload> STREAM_CODEC = StreamCodec.composite(
        ClaimEntry.CODEC.apply(ByteBufCodecs.list()), ClaimSyncPayload::claims,
        ClaimSyncPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
