package com.bannerbound.core.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C: a fresh snapshot of the player's town inventory (and the camp's current goods) for an open
 * barter screen, in reply to {@link RequestBarterStoragePayload}. The screen swaps these in and
 * re-validates the live offer so Accept and the item-add buttons grey out the moment storage runs short.
 */
@ApiStatus.Internal
public record BarterStoragePayload(
    int messengerEntityId,
    List<BarterEntry> yourStorage,
    List<BarterEntry> theirGoods
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BarterStoragePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "barter_storage"));

    public static final StreamCodec<ByteBuf, BarterStoragePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.messengerEntityId);
            BarterEntry.LIST.encode(buf, p.yourStorage);
            BarterEntry.LIST.encode(buf, p.theirGoods);
        },
        buf -> new BarterStoragePayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            BarterEntry.LIST.decode(buf),
            BarterEntry.LIST.decode(buf)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
