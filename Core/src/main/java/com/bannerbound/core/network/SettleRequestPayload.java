package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: the founding request. Carries the settlement name, the chosen color index,
 * and the chosen culture-style id (may be blank — the server then falls back to a default).
 */
@ApiStatus.Internal
public record SettleRequestPayload(String name, int colorIndex, String cultureStyle)
        implements CustomPacketPayload {
    public static final int MAX_NAME_LENGTH = 24;
    public static final int MAX_STYLE_LENGTH = 64;

    public static final CustomPacketPayload.Type<SettleRequestPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "settle_request"));

    public static final StreamCodec<FriendlyByteBuf, SettleRequestPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH), SettleRequestPayload::name,
        ByteBufCodecs.INT, SettleRequestPayload::colorIndex,
        ByteBufCodecs.stringUtf8(MAX_STYLE_LENGTH), SettleRequestPayload::cultureStyle,
        SettleRequestPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
