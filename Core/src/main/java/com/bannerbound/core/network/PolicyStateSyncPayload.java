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
 * Server → client snapshot of a settlement's policy state, consumed by {@code ClientPolicyState}
 * and rendered by the town hall's Policies tab. Sent to every member whenever the policy state
 * changes (propose / vote / suggest / enact / retract) so all members' tabs stay live without
 * re-opening the town hall.
 *
 * <p>{@code pendingSlot} is -1 when there's no pending change. {@code pendingAddId} /
 * {@code pendingRemoveId} are empty strings when not applicable. The confirm-vote lists are
 * parallel (voter[i] cast agree[i]); they're only populated in a Council with a pending change.
 * The suggestion lists are parallel (suggestionPolicyIds[i] was suggested by suggestionVoters[i]).
 */
@ApiStatus.Internal
public record PolicyStateSyncPayload(
    List<String> availablePolicyIds,
    List<String> activePolicyIds,
    List<String> slotTypes,
    int pendingSlot,
    String pendingAddId,
    String pendingRemoveId,
    int onlineMemberCount,
    List<UUID> confirmVoterIds,
    List<Boolean> confirmVoteAgrees,
    List<String> suggestionPolicyIds,
    List<List<UUID>> suggestionVoters
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PolicyStateSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "policy_state_sync"));

    private static final StreamCodec<ByteBuf, List<String>> STRING_LIST =
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list());

    public static final StreamCodec<ByteBuf, PolicyStateSyncPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            STRING_LIST.encode(buf, p.availablePolicyIds());
            STRING_LIST.encode(buf, p.activePolicyIds());
            STRING_LIST.encode(buf, p.slotTypes());
            ByteBufCodecs.VAR_INT.encode(buf, p.pendingSlot());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.pendingAddId());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.pendingRemoveId());
            ByteBufCodecs.VAR_INT.encode(buf, p.onlineMemberCount());
            ByteBufCodecs.VAR_INT.encode(buf, p.confirmVoterIds().size());
            for (int i = 0; i < p.confirmVoterIds().size(); i++) {
                UUIDUtil.STREAM_CODEC.encode(buf, p.confirmVoterIds().get(i));
                buf.writeBoolean(p.confirmVoteAgrees().get(i));
            }
            ByteBufCodecs.VAR_INT.encode(buf, p.suggestionPolicyIds().size());
            for (int i = 0; i < p.suggestionPolicyIds().size(); i++) {
                ByteBufCodecs.STRING_UTF8.encode(buf, p.suggestionPolicyIds().get(i));
                List<UUID> voters = p.suggestionVoters().get(i);
                ByteBufCodecs.VAR_INT.encode(buf, voters.size());
                for (UUID u : voters) UUIDUtil.STREAM_CODEC.encode(buf, u);
            }
        },
        buf -> {
            List<String> available = STRING_LIST.decode(buf);
            List<String> active = STRING_LIST.decode(buf);
            List<String> slotTypes = STRING_LIST.decode(buf);
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
            return new PolicyStateSyncPayload(available, active, slotTypes, pendingSlot, addId, removeId,
                online, voterIds, voteAgrees, sugIds, sugVoters);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
