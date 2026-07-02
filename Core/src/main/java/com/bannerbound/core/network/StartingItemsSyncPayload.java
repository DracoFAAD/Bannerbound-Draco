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
public record StartingItemsSyncPayload(List<String> itemIds) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StartingItemsSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "starting_items_sync"));

    public static final StreamCodec<ByteBuf, StartingItemsSyncPayload> STREAM_CODEC =
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list())
            .map(StartingItemsSyncPayload::new, StartingItemsSyncPayload::itemIds);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
