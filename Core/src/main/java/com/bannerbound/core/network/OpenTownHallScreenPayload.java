package com.bannerbound.core.network;

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
 * Server → client snapshot of every datum the town-hall screen needs to render. Sent once per
 * town-hall open and re-sent after votes / state changes that affect the buttons.
 *
 * <p><b>Government fields</b> drive the Code-of-Laws workflow: when
 * {@link #governmentChoiceWindowOpen} is true and {@link #governmentOrdinal} is 0 (NONE), the
 * screen shows the "Choose Government" button. Vote tallies + the per-player pick let the
 * button render live progress. {@link #onlineMembers} is the denominator — vote threshold
 * runs against this, not {@link #totalMembers}, per the confirmed design where offline
 * players forfeit their vote.
 */
@ApiStatus.Internal
public record OpenTownHallScreenPayload(
    String settlementName,
    int colorOrdinal,
    int eraOrdinal,
    int tabletsIssued,
    int tabletCapacity,
    int disbandVoteCount,
    int disbandTotalMembers,
    boolean playerHasVotedToDisband,
    boolean disbandVoteActive,
    // ── Code of Laws + government state ─────────────────────────────────────────────────
    int governmentOrdinal,
    boolean codeOfLawsPromptShown,
    boolean governmentChoiceWindowOpen,
    boolean governmentVoteActive,
    int councilVoteCount,
    int chiefdomVoteCount,
    int onlineMembers,
    /** Player's current pick in the Choose-Government vote: 0 = none, 1 = council, 2 = chiefdom. */
    int playerGovernmentVote,
    // ── Chief election (Chiefdom only, after government type is set) ────────────────────
    boolean chiefdomElectionActive,
    /** Member UUIDs that can be nominated as chief — parallel arrays with names + counts. */
    List<UUID> chiefCandidates,
    List<String> chiefCandidateNames,
    List<Integer> chiefCandidateVotes,
    /** Player's current nomination (which candidate they voted for), or all-zero UUID = none. */
    UUID playerChiefNomination,
    /** True iff this player is the currently-seated Chief in a CHIEFDOM. Drives the Disband /
     *  Expand-Territory gates client-side. Server-side gates re-check independently — this is
     *  just the UI hint. False in COUNCIL / NONE governments. */
    boolean playerIsChief,
    /** Step 15: true iff this player is the current REGENT — temporary stand-in while the
     *  real Chief is offline. Regents get routine-action authority (Research, suggestions
     *  are skipped — they CAN start research) but NOT weighty authority (Disband / Expand
     *  stay greyed). False in Council / NONE / chief-online states. */
    boolean playerIsRegent,
    /** Absolute game tick at which this player (if the seated Chief) may Step Down — i.e. when
     *  the anti-cheese term cooldown elapses. {@code -1} when not applicable (not the chief, or a
     *  pre-feature chief with no anchor). The Step-Down button greys out with a live mm:ss
     *  countdown computed against the client's synced {@code level.getGameTime()} until then. */
    long chiefStepDownReadyTick,
    /** Absolute game tick at which this player may Leave the settlement — i.e. when the
     *  anti-cheese join/found cooldown elapses. {@code 0} when already free to leave. The
     *  Leave button greys out with a live mm:ss countdown against the client's synced
     *  {@code level.getGameTime()} until then. */
    long leaveReadyTick,
    /** Banner-driven identity colors as 0xRRGGBB, most-present dye first, AS MANY as the
     *  banner has (never empty — founding rgb fallback resolved server-side). Drive the
     *  screen's name color and every accent gradient. */
    List<Integer> identityRgbs
) implements CustomPacketPayload {

    private static final StreamCodec<ByteBuf, List<Integer>> INT_LIST =
        ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list());
    public static final CustomPacketPayload.Type<OpenTownHallScreenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_townhall_screen"));

    public static final StreamCodec<ByteBuf, OpenTownHallScreenPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, p.settlementName());
            ByteBufCodecs.VAR_INT.encode(buf, p.colorOrdinal());
            ByteBufCodecs.VAR_INT.encode(buf, p.eraOrdinal());
            ByteBufCodecs.VAR_INT.encode(buf, p.tabletsIssued());
            ByteBufCodecs.VAR_INT.encode(buf, p.tabletCapacity());
            ByteBufCodecs.VAR_INT.encode(buf, p.disbandVoteCount());
            ByteBufCodecs.VAR_INT.encode(buf, p.disbandTotalMembers());
            buf.writeBoolean(p.playerHasVotedToDisband());
            buf.writeBoolean(p.disbandVoteActive());
            ByteBufCodecs.VAR_INT.encode(buf, p.governmentOrdinal());
            buf.writeBoolean(p.codeOfLawsPromptShown());
            buf.writeBoolean(p.governmentChoiceWindowOpen());
            buf.writeBoolean(p.governmentVoteActive());
            ByteBufCodecs.VAR_INT.encode(buf, p.councilVoteCount());
            ByteBufCodecs.VAR_INT.encode(buf, p.chiefdomVoteCount());
            ByteBufCodecs.VAR_INT.encode(buf, p.onlineMembers());
            ByteBufCodecs.VAR_INT.encode(buf, p.playerGovernmentVote());
            // Chief election block.
            buf.writeBoolean(p.chiefdomElectionActive());
            ByteBufCodecs.VAR_INT.encode(buf, p.chiefCandidates().size());
            for (int i = 0; i < p.chiefCandidates().size(); i++) {
                UUIDUtil.STREAM_CODEC.encode(buf, p.chiefCandidates().get(i));
                ByteBufCodecs.STRING_UTF8.encode(buf, p.chiefCandidateNames().get(i));
                ByteBufCodecs.VAR_INT.encode(buf, p.chiefCandidateVotes().get(i));
            }
            UUIDUtil.STREAM_CODEC.encode(buf, p.playerChiefNomination());
            buf.writeBoolean(p.playerIsChief());
            buf.writeBoolean(p.playerIsRegent());
            buf.writeLong(p.chiefStepDownReadyTick());
            buf.writeLong(p.leaveReadyTick());
            INT_LIST.encode(buf, p.identityRgbs());
        },
        buf -> {
            String settlementName = ByteBufCodecs.STRING_UTF8.decode(buf);
            int colorOrdinal = ByteBufCodecs.VAR_INT.decode(buf);
            int eraOrdinal = ByteBufCodecs.VAR_INT.decode(buf);
            int tabletsIssued = ByteBufCodecs.VAR_INT.decode(buf);
            int tabletCapacity = ByteBufCodecs.VAR_INT.decode(buf);
            int disbandVoteCount = ByteBufCodecs.VAR_INT.decode(buf);
            int disbandTotalMembers = ByteBufCodecs.VAR_INT.decode(buf);
            boolean playerHasVotedToDisband = buf.readBoolean();
            boolean disbandVoteActive = buf.readBoolean();
            int governmentOrdinal = ByteBufCodecs.VAR_INT.decode(buf);
            boolean codeOfLawsPromptShown = buf.readBoolean();
            boolean governmentChoiceWindowOpen = buf.readBoolean();
            boolean governmentVoteActive = buf.readBoolean();
            int councilVoteCount = ByteBufCodecs.VAR_INT.decode(buf);
            int chiefdomVoteCount = ByteBufCodecs.VAR_INT.decode(buf);
            int onlineMembers = ByteBufCodecs.VAR_INT.decode(buf);
            int playerGovernmentVote = ByteBufCodecs.VAR_INT.decode(buf);
            boolean chiefdomElectionActive = buf.readBoolean();
            int candidateCount = ByteBufCodecs.VAR_INT.decode(buf);
            java.util.ArrayList<UUID> chiefCandidates = new java.util.ArrayList<>(candidateCount);
            java.util.ArrayList<String> chiefCandidateNames = new java.util.ArrayList<>(candidateCount);
            java.util.ArrayList<Integer> chiefCandidateVotes = new java.util.ArrayList<>(candidateCount);
            for (int i = 0; i < candidateCount; i++) {
                chiefCandidates.add(UUIDUtil.STREAM_CODEC.decode(buf));
                chiefCandidateNames.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                chiefCandidateVotes.add(ByteBufCodecs.VAR_INT.decode(buf));
            }
            UUID playerChiefNomination = UUIDUtil.STREAM_CODEC.decode(buf);
            boolean playerIsChief = buf.readBoolean();
            boolean playerIsRegent = buf.readBoolean();
            long chiefStepDownReadyTick = buf.readLong();
            long leaveReadyTick = buf.readLong();
            List<Integer> identityRgbs = INT_LIST.decode(buf);
            return new OpenTownHallScreenPayload(
                settlementName, colorOrdinal, eraOrdinal, tabletsIssued, tabletCapacity,
                disbandVoteCount, disbandTotalMembers, playerHasVotedToDisband, disbandVoteActive,
                governmentOrdinal, codeOfLawsPromptShown, governmentChoiceWindowOpen,
                governmentVoteActive, councilVoteCount, chiefdomVoteCount, onlineMembers,
                playerGovernmentVote, chiefdomElectionActive,
                chiefCandidates, chiefCandidateNames, chiefCandidateVotes, playerChiefNomination,
                playerIsChief, playerIsRegent, chiefStepDownReadyTick, leaveReadyTick,
                identityRgbs);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
