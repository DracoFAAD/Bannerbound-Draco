package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C: the receiving player's snapshot of their settlement's in-flight chat votes (exile / tablet)
 * for the Town Hall "Votes" tab. Per-player (not a shared broadcast instance) because {@code myVote}
 * differs per member. Sent on town-hall open and after every cast/start/resolve/expiry; the client
 * ticks {@code secondsLeft} down locally between syncs.
 *
 * @param entries one row per active vote, oldest first
 */
@ApiStatus.Internal
public record ChatVotesStatePayload(List<Entry> entries) implements CustomPacketPayload {
    /**
     * @param voteId        server-side vote id (what [Yes]/[No] casts against)
     * @param kind          0 = EXILE, 1 = TABLET (matches {@code ChatVoteManager.Kind} ordinal)
     * @param initiatorName player who started the vote
     * @param targetName    EXILE: citizen name; TABLET: ""
     * @param yes/no        current counts
     * @param secondsLeft   until expiry, at send time
     * @param myVote        1 = receiver voted yes, -1 = no, 0 = hasn't voted
     */
    public record Entry(int voteId, int kind, String initiatorName, String targetName,
                        int yes, int no, int secondsLeft, int myVote) {}

    public static final CustomPacketPayload.Type<ChatVotesStatePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "chat_votes_state"));

    public static final StreamCodec<ByteBuf, ChatVotesStatePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.entries().size());
            for (Entry e : p.entries()) {
                ByteBufCodecs.VAR_INT.encode(buf, e.voteId());
                ByteBufCodecs.VAR_INT.encode(buf, e.kind());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.initiatorName());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.targetName());
                ByteBufCodecs.VAR_INT.encode(buf, e.yes());
                ByteBufCodecs.VAR_INT.encode(buf, e.no());
                ByteBufCodecs.VAR_INT.encode(buf, e.secondsLeft());
                ByteBufCodecs.VAR_INT.encode(buf, e.myVote() + 1);   // shift -1..1 → 0..2 for VAR_INT
            }
        },
        buf -> {
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            List<Entry> entries = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                entries.add(new Entry(
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf) - 1));
            }
            return new ChatVotesStatePayload(entries);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
