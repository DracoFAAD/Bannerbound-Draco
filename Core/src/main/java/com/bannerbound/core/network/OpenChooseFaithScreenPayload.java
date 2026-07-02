package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C, in response to {@link RequestFaithScreenPayload}: opens the Choose-Faith screen
 * with the current vote tallies plus every adoptable faith on the server (the
 * "bigger than factions" list — see FAITH_PLAN.md Part 1).
 */
public record OpenChooseFaithScreenPayload(
        int astrologyVotes,
        int totemicVotes,
        int onlineMembers,
        String playerVote,        // option key the player already cast, or ""
        List<String> faithIds,    // parallel lists: one entry per adoptable faith
        List<String> faithNames,
        List<Integer> faithPaths,
        List<Integer> faithMemberCounts,
        List<Integer> faithAdoptVotes) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OpenChooseFaithScreenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_choose_faith"));

    public static final StreamCodec<ByteBuf, OpenChooseFaithScreenPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.astrologyVotes());
            ByteBufCodecs.VAR_INT.encode(buf, p.totemicVotes());
            ByteBufCodecs.VAR_INT.encode(buf, p.onlineMembers());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.playerVote());
            ByteBufCodecs.VAR_INT.encode(buf, p.faithIds().size());
            for (int i = 0; i < p.faithIds().size(); i++) {
                ByteBufCodecs.STRING_UTF8.encode(buf, p.faithIds().get(i));
                ByteBufCodecs.STRING_UTF8.encode(buf, p.faithNames().get(i));
                ByteBufCodecs.VAR_INT.encode(buf, p.faithPaths().get(i));
                ByteBufCodecs.VAR_INT.encode(buf, p.faithMemberCounts().get(i));
                ByteBufCodecs.VAR_INT.encode(buf, p.faithAdoptVotes().get(i));
            }
        },
        buf -> {
            int astrology = ByteBufCodecs.VAR_INT.decode(buf);
            int totemic = ByteBufCodecs.VAR_INT.decode(buf);
            int online = ByteBufCodecs.VAR_INT.decode(buf);
            String playerVote = ByteBufCodecs.STRING_UTF8.decode(buf);
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            List<String> ids = new ArrayList<>(n);
            List<String> names = new ArrayList<>(n);
            List<Integer> paths = new ArrayList<>(n);
            List<Integer> members = new ArrayList<>(n);
            List<Integer> adoptVotes = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                ids.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                names.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                paths.add(ByteBufCodecs.VAR_INT.decode(buf));
                members.add(ByteBufCodecs.VAR_INT.decode(buf));
                adoptVotes.add(ByteBufCodecs.VAR_INT.decode(buf));
            }
            return new OpenChooseFaithScreenPayload(astrology, totemic, online, playerVote,
                ids, names, paths, members, adoptVotes);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
