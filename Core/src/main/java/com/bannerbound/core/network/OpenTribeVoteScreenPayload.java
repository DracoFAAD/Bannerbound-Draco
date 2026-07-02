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
 * Server → client: open the "Tribe vote" reveal screen. Sent to every settlement member
 * when a chief election ends in a tie and the citizens are about to break it. Each row in
 * the parallel lists {@code voterNames} / {@code candidateNames} is one citizen's vote,
 * ordered the way they'll appear (the client reveals them one at a time with an
 * exponentially-shortening delay — first vote slow, last vote fast, "ta...ta..ta.ta.tatata").
 *
 * <p>The screen is purely cosmetic — the actual chief-enactment is server-scheduled to fire
 * after the reveal completes. Players who closed the screen still see the chat broadcast and
 * the elected Chief takes effect identically.
 */
@ApiStatus.Internal
public record OpenTribeVoteScreenPayload(
    List<String> voterNames,
    List<String> candidateNames
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenTribeVoteScreenPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_tribe_vote_screen"));

    public static final StreamCodec<ByteBuf, OpenTribeVoteScreenPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), OpenTribeVoteScreenPayload::voterNames,
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), OpenTribeVoteScreenPayload::candidateNames,
        OpenTribeVoteScreenPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
