package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client snapshot of a settlement's palette state, consumed by {@code ClientPaletteState}
 * and rendered by the town hall's Palettes tab. Twin of {@link PolicyStateSyncPayload} (same
 * available/active/slot/pending/vote/suggestion shape) with one addition: because palettes are
 * data-driven, the payload also carries the <b>definitions</b> (id, name, and an ordered block-id
 * + bonus list per palette) for every available/active palette, so the client can render each
 * palette's block icons and per-block bonus tooltip without its own copy of the datapack.
 *
 * <p>The {@code def*} lists are parallel: {@code defIds[i]} has name {@code defNames[i]}, blocks
 * {@code defBlockIds[i]} with matching bonuses {@code defBonuses[i]}.
 */
@ApiStatus.Internal
public record PaletteStateSyncPayload(
    List<String> availablePaletteIds,
    List<String> activePaletteIds,
    int activePaletteSlots,
    int pendingSlot,
    String pendingAddId,
    String pendingRemoveId,
    int onlineMemberCount,
    List<UUID> confirmVoterIds,
    List<Boolean> confirmVoteAgrees,
    List<String> suggestionPaletteIds,
    List<List<UUID>> suggestionVoters,
    List<String> defIds,
    List<String> defNames,
    List<List<String>> defBlockIds,
    List<List<Float>> defBonuses
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PaletteStateSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "palette_state_sync"));

    private static final StreamCodec<ByteBuf, List<String>> STRING_LIST =
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list());

    public static final StreamCodec<ByteBuf, PaletteStateSyncPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            STRING_LIST.encode(buf, p.availablePaletteIds());
            STRING_LIST.encode(buf, p.activePaletteIds());
            ByteBufCodecs.VAR_INT.encode(buf, p.activePaletteSlots());
            ByteBufCodecs.VAR_INT.encode(buf, p.pendingSlot());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.pendingAddId());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.pendingRemoveId());
            ByteBufCodecs.VAR_INT.encode(buf, p.onlineMemberCount());
            ByteBufCodecs.VAR_INT.encode(buf, p.confirmVoterIds().size());
            for (int i = 0; i < p.confirmVoterIds().size(); i++) {
                UUIDUtil.STREAM_CODEC.encode(buf, p.confirmVoterIds().get(i));
                buf.writeBoolean(p.confirmVoteAgrees().get(i));
            }
            ByteBufCodecs.VAR_INT.encode(buf, p.suggestionPaletteIds().size());
            for (int i = 0; i < p.suggestionPaletteIds().size(); i++) {
                ByteBufCodecs.STRING_UTF8.encode(buf, p.suggestionPaletteIds().get(i));
                List<UUID> voters = p.suggestionVoters().get(i);
                ByteBufCodecs.VAR_INT.encode(buf, voters.size());
                for (UUID u : voters) UUIDUtil.STREAM_CODEC.encode(buf, u);
            }
            // Definitions.
            ByteBufCodecs.VAR_INT.encode(buf, p.defIds().size());
            for (int i = 0; i < p.defIds().size(); i++) {
                ByteBufCodecs.STRING_UTF8.encode(buf, p.defIds().get(i));
                ByteBufCodecs.STRING_UTF8.encode(buf, p.defNames().get(i));
                List<String> blockIds = p.defBlockIds().get(i);
                List<Float> bonuses = p.defBonuses().get(i);
                ByteBufCodecs.VAR_INT.encode(buf, blockIds.size());
                for (int j = 0; j < blockIds.size(); j++) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, blockIds.get(j));
                    buf.writeFloat(bonuses.get(j));
                }
            }
        },
        buf -> {
            List<String> available = STRING_LIST.decode(buf);
            List<String> active = STRING_LIST.decode(buf);
            int slots = ByteBufCodecs.VAR_INT.decode(buf);
            int pendingSlot = ByteBufCodecs.VAR_INT.decode(buf);
            String addId = ByteBufCodecs.STRING_UTF8.decode(buf);
            String removeId = ByteBufCodecs.STRING_UTF8.decode(buf);
            int online = ByteBufCodecs.VAR_INT.decode(buf);
            int voteCount = ByteBufCodecs.VAR_INT.decode(buf);
            List<UUID> voterIds = new ArrayList<>(voteCount);
            List<Boolean> voteAgrees = new ArrayList<>(voteCount);
            for (int i = 0; i < voteCount; i++) {
                voterIds.add(UUIDUtil.STREAM_CODEC.decode(buf));
                voteAgrees.add(buf.readBoolean());
            }
            int sugCount = ByteBufCodecs.VAR_INT.decode(buf);
            List<String> sugIds = new ArrayList<>(sugCount);
            List<List<UUID>> sugVoters = new ArrayList<>(sugCount);
            for (int i = 0; i < sugCount; i++) {
                sugIds.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                int n = ByteBufCodecs.VAR_INT.decode(buf);
                List<UUID> voters = new ArrayList<>(n);
                for (int j = 0; j < n; j++) voters.add(UUIDUtil.STREAM_CODEC.decode(buf));
                sugVoters.add(voters);
            }
            int defCount = ByteBufCodecs.VAR_INT.decode(buf);
            List<String> defIds = new ArrayList<>(defCount);
            List<String> defNames = new ArrayList<>(defCount);
            List<List<String>> defBlockIds = new ArrayList<>(defCount);
            List<List<Float>> defBonuses = new ArrayList<>(defCount);
            for (int i = 0; i < defCount; i++) {
                defIds.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                defNames.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                int n = ByteBufCodecs.VAR_INT.decode(buf);
                List<String> blockIds = new ArrayList<>(n);
                List<Float> bonuses = new ArrayList<>(n);
                for (int j = 0; j < n; j++) {
                    blockIds.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                    bonuses.add(buf.readFloat());
                }
                defBlockIds.add(blockIds);
                defBonuses.add(bonuses);
            }
            return new PaletteStateSyncPayload(available, active, slots, pendingSlot, addId, removeId,
                online, voterIds, voteAgrees, sugIds, sugVoters,
                defIds, defNames, defBlockIds, defBonuses);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
