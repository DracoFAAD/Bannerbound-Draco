package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: "I'm in-world now, (re)send me the starting-items set." The push from
 * {@code OnDatapackSyncEvent} can miss during the join sequence; this pull lets the client ask
 * again once it is fully connected, so {@link com.bannerbound.core.client.ClientStartingItems}
 * always ends up populated (and JEI's gate hides unknowns instead of showing a wall of "?").
 */
@ApiStatus.Internal
public record RequestStartingItemsPayload() implements CustomPacketPayload {
    public static final RequestStartingItemsPayload INSTANCE = new RequestStartingItemsPayload();

    public static final CustomPacketPayload.Type<RequestStartingItemsPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "request_starting_items"));

    public static final StreamCodec<ByteBuf, RequestStartingItemsPayload> STREAM_CODEC =
        StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
