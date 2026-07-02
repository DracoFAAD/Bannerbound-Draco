package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: the chief clicked [Ignore] on a Suggestions-tab row. The server clears that suggestion and
 * tells each suggester their suggestion was ignored.
 *
 * @param kind 0 = science research, 1 = culture research, 2 = policy, 3 = palette,
 *             4 = exile (id = citizen UUID string), 5 = tablet (id unused, "")
 * @param id   the suggested thing's id within its kind
 */
@ApiStatus.Internal
public record IgnoreSuggestionPayload(int kind, String id) implements CustomPacketPayload {
    public static final int KIND_SCIENCE = 0;
    public static final int KIND_CULTURE = 1;
    public static final int KIND_POLICY = 2;
    public static final int KIND_PALETTE = 3;
    public static final int KIND_EXILE = 4;
    public static final int KIND_TABLET = 5;

    public static final CustomPacketPayload.Type<IgnoreSuggestionPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "ignore_suggestion"));

    public static final StreamCodec<ByteBuf, IgnoreSuggestionPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.kind());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.id());
        },
        buf -> new IgnoreSuggestionPayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
