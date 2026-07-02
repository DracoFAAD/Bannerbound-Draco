package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: a proximity chat line. Sent (instead of a normal broadcast) once per in-range
 * listener when the {@code globalChat} game rule is off, carrying the fully-formatted chat / whisper
 * component and an {@code alpha} (0–1) audibility factor derived from the listener's distance to the
 * speaker. The client adds it to chat at that transparency via the chat render mixin.
 *
 * @see com.bannerbound.core.event.ChatEvents
 * @see com.bannerbound.core.chat.ProximityChat
 */
@ApiStatus.Internal
public record ProximityChatPayload(Component message, float alpha) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ProximityChatPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "proximity_chat"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProximityChatPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ComponentSerialization.STREAM_CODEC.encode(buf, p.message());
            ByteBufCodecs.FLOAT.encode(buf, p.alpha());
        },
        buf -> new ProximityChatPayload(
            ComponentSerialization.STREAM_CODEC.decode(buf),
            ByteBufCodecs.FLOAT.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
