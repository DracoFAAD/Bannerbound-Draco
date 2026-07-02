package com.bannerbound.core.network;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: the player's Choose-Faith vote. {@code optionKey} is one of
 * {@code found:ASTROLOGY}, {@code found:TOTEMIC} or {@code adopt:<faithUuid>};
 * {@code proposedName} carries the faith name for found-votes (the winning option's
 * earliest proposal names the faith — no second naming round-trip).
 */
public record CastFaithVotePayload(String optionKey, String proposedName) implements CustomPacketPayload {
    public static final int MAX_OPTION_LENGTH = 64;
    public static final int MAX_NAME_LENGTH = 32;

    public static final CustomPacketPayload.Type<CastFaithVotePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "cast_faith_vote"));

    public static final StreamCodec<ByteBuf, CastFaithVotePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.stringUtf8(MAX_OPTION_LENGTH).encode(buf, p.optionKey());
            ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH).encode(buf, p.proposedName());
        },
        buf -> new CastFaithVotePayload(
            ByteBufCodecs.stringUtf8(MAX_OPTION_LENGTH).decode(buf),
            ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH).decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
