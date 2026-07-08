package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: replay a tutorial popup by id (the Chronicle's View Tutorial button).
 *  Answered with an immediate ShowTutorialPopupPayload; ignores the fired/once state. */
@ApiStatus.Internal
public record RequestTutorialPopupPayload(String popupId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RequestTutorialPopupPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "request_tutorial_popup"));

    public static final StreamCodec<ByteBuf, RequestTutorialPopupPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8,
        RequestTutorialPopupPayload::popupId,
        RequestTutorialPopupPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
