package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: the player clicked Trade on a Diplomacy-tab row — build and send back the
 * {@link OpenTradeScreenPayload} snapshot for settlement {@code targetId} (also marks an
 * awaiting deal as read, clearing the badge).
 */
@ApiStatus.Internal
public record RequestOpenTradePayload(String targetId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RequestOpenTradePayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "request_open_trade"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestOpenTradePayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RequestOpenTradePayload::targetId,
            RequestOpenTradePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
