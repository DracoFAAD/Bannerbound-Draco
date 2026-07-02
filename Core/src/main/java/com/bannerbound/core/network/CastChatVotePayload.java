package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C→S: a Yes/No on an in-flight council chat vote, clicked in the Town Hall "Votes" tab. (The
 *  chat [Yes]/[No] route runs {@code /bannerbound vote <id> yes|no} instead — same server path.) */
@ApiStatus.Internal
public record CastChatVotePayload(int voteId, boolean yes) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CastChatVotePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "cast_chat_vote"));

    public static final StreamCodec<ByteBuf, CastChatVotePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.voteId());
            ByteBufCodecs.BOOL.encode(buf, p.yes());
        },
        buf -> new CastChatVotePayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.BOOL.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
