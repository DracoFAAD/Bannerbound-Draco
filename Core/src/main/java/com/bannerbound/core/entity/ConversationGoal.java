package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Citizen;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.social.Conversation;
import com.bannerbound.core.social.Conversation.Phase;
import com.bannerbound.core.social.ConversationTopic;
import com.bannerbound.core.social.SocialEvents;
import com.bannerbound.core.social.ThoughtKind;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * Per-citizen AI goal that drives one side of a Bannerbound Conversation: meet at a midpoint
 * near the town hall, face the partner, exchange three speech bubbles, and apply a relationship
 * delta based on how many bubbles matched. See {@code MDK/docs/citizens.md} for the system
 * overview.
 * <p>
 * <b>Initiator vs joiner.</b> Two of these goals — one per citizen — share a single
 * {@link Conversation} object held on the settlement. The initiator (the citizen whose
 * {@link #canUse} created the {@code Conversation}) is the sole writer of phase transitions
 * and outcome resolution; the joiner only writes its own facing / navigation / bubble icon.
 * Both citizens compute "time in phase" from {@link Conversation#phaseStartGameTime} so the
 * order in which they're ticked within a single game tick doesn't matter.
 */
public class ConversationGoal extends Goal {
    /** Min per-citizen cooldown after a conversation ends — 2 in-game minutes (2400 ticks). */
    public static final int CONV_COOLDOWN_MIN_TICKS = 2_400;
    /** Max per-citizen cooldown — 7 in-game minutes (8400 ticks). Each conversation rolls a
     *  uniform random cooldown in [MIN, MAX] so a settlement doesn't develop a chat heartbeat. */
    public static final int CONV_COOLDOWN_MAX_TICKS = 8_400;
    /** Throttle the partner-finding scan so an unemployable settlement doesn't burn CPU on it. */
    private static final int PARTNER_SCAN_INTERVAL = 20;
    /** No conversations for the first ~30s after a citizen (re)loads. {@code tickCount} resets to 0
     *  every load, so without this grace a freshly opened world erupts into chatter the moment it
     *  appears — every citizen's cooldown rolled before the save doesn't survive the reload window
     *  and they all "arrive" simultaneously. Applies to initiating, joining, and being picked. */
    private static final int LOAD_CHAT_GRACE_TICKS = 600;

    private final CitizenEntity citizen;
    private final double speedModifier;

    @Nullable private Conversation active;
    private boolean isInitiator;
    @Nullable private CitizenEntity partnerEntity;
    private int scanCooldown = 0;

    public ConversationGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ─── canUse ────────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean canUse() {
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        // Activation tier: skip the partner scan entirely when no player is nearby to witness a chat.
        if (!citizen.isAiActive()) return false;
        // Freshly (re)loaded citizens settle in before chatting — kills the world-open chatter burst.
        if (citizen.tickCount < LOAD_CHAT_GRACE_TICKS) return false;
        // Seated on a vessel (a sailing fisher mid-trip): no starting or joining chats from a boat.
        if (citizen.isPassenger()) return false;
        if (citizen.getConversationCooldown() > 0) return false;

        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return false;
        // During a scripted crisis the settlement is not in its normal dusk social rhythm.
        if (settlement.activeCrisis() != null) return false;
        // Nightshift policy: citizens with a job don't socialize — they work right up to bed.
        if (settlement.hasPolicy(com.bannerbound.core.api.settlement.PolicyRegistry.NIGHTSHIFT)
                && citizen.isEmployed()) {
            return false;
        }
        BlockPos townHall = settlement.townHallPos();
        if (townHall == null) return false;

        Vec3 thVec = Vec3.atCenterOf(townHall);
        double socialSq = Conversation.SOCIAL_RADIUS * Conversation.SOCIAL_RADIUS;
        if (citizen.position().distanceToSqr(thVec) > socialSq) return false;

        // Joiner path: a conversation that lists me as a participant already exists. This is the
        // mechanism the initiator's partner uses to "join" — initiator created the Conversation
        // last tick; partner sees it this tick and attaches.
        Conversation existing = settlement.findActiveConversationFor(citizen.getUUID());
        if (existing != null && existing.phase != Phase.DONE) {
            UUID otherId = existing.otherSide(citizen.getUUID());
            if (otherId != null && sl.getEntity(otherId) instanceof CitizenEntity other) {
                this.active = existing;
                this.isInitiator = existing.a.equals(citizen.getUUID());
                this.partnerEntity = other;
                return true;
            }
            return false;
        }

        // Initiator path: throttle scans — N² behaviour otherwise. The joiner path above is left
        // un-gated so a partner attaches promptly; only the cost of *initiating* (scan + path to
        // midpoint) is staggered onto this citizen's think tick so starts don't cluster.
        if (!citizen.isThinkTick()) return false;
        if (scanCooldown > 0) { scanCooldown--; return false; }
        scanCooldown = PARTNER_SCAN_INTERVAL;
        CitizenEntity partner = findPartner(sl, settlement, thVec, socialSq);
        if (partner == null) return false;

        // Both stand at a single midpoint BlockPos. The pathfinder won't let them overlap so
        // they end up adjacent; faceOther() in tickFaceOff snaps them into a face-to-face pair.
        BlockPos midpoint = BlockPos.containing(
            (citizen.getX() + partner.getX()) * 0.5,
            citizen.getY(),
            (citizen.getZ() + partner.getZ()) * 0.5);

        Conversation conv = Conversation.begin(
            citizen.getUUID(), partner.getUUID(), midpoint, midpoint,
            citizen.getHappiness(), partner.getHappiness(),
            settlement.isTribe(), sl.random);
        settlement.startConversation(conv);
        this.active = conv;
        this.isInitiator = true;
        this.partnerEntity = partner;
        return true;
    }

    private CitizenEntity findPartner(ServerLevel sl, Settlement settlement, Vec3 thVec, double socialSq) {
        List<CitizenEntity> candidates = new ArrayList<>();
        for (Citizen c : settlement.citizens()) {
            UUID id = c.entityId();
            if (id.equals(citizen.getUUID())) continue;
            if (!(sl.getEntity(id) instanceof CitizenEntity other)) continue;
            if (!other.isAlive()) continue;
            if (other.getSettlement() != settlement) continue;
            if (other.tickCount < LOAD_CHAT_GRACE_TICKS) continue;   // partner just loaded too
            if (other.isPassenger()) continue;                       // don't drag a sailor into a chat
            if (other.getConversationCooldown() > 0) continue;
            if (other.getBubbleTopic() != 0) continue;
            if (settlement.findActiveConversationFor(id) != null) continue;
            if (other.position().distanceToSqr(thVec) > socialSq) continue;
            candidates.add(other);
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(sl.random.nextInt(candidates.size()));
    }

    // ─── start / continue / stop ───────────────────────────────────────────────────────────────

    @Override
    public void start() {
        if (active == null || partnerEntity == null) return;
        // Path to the PARTNER ENTITY rather than a fixed midpoint BlockPos. Vanilla's
        // entity-target moveTo updates as the partner moves and uses the same pathfinder
        // semantics follow-style goals rely on — much more reliable than a midpoint that may
        // land in an unwalkable tile. The "arrived" check in tickWalk compares against the
        // partner's live position too, so the two are consistent.
        citizen.getNavigation().moveTo(partnerEntity, speedModifier);
        citizen.setBubbleTopic(0);
        if (isInitiator && citizen.level() instanceof ServerLevel sl) {
            active.transitionTo(Phase.WALK_TO_MEET, sl.getGameTime());
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (active == null) return false;
        if (active.phase == Phase.DONE) return false;
        if (partnerEntity == null || !partnerEntity.isAlive()) return false;
        return true;
    }

    @Override
    public void stop() {
        citizen.setBubbleTopic(0);
        citizen.setConversationCooldown(rollCooldown());
        citizen.getNavigation().stop();
        if (isInitiator && active != null) {
            Settlement s = citizen.getSettlement();
            if (s != null) s.endConversation(active);
        }
        active = null;
        partnerEntity = null;
        isInitiator = false;
    }

    // ─── tick state machine ────────────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        if (active == null) return;
        if (!(citizen.level() instanceof ServerLevel sl)) return;
        long now = sl.getGameTime();
        int t = active.ticksInPhase(now);
        switch (active.phase) {
            case WALK_TO_MEET -> tickWalk(t, now);
            case FACE_OFF    -> tickFaceOff(t, now);
            case BUBBLE      -> tickBubble(t, now);
            case GAP         -> tickGap(t, now);
            case RESOLVING   -> tickResolving(t, now);
            case DONE        -> {}
        }
    }

    private void tickWalk(int t, long now) {
        if (partnerEntity == null) return;
        // "Arrived" = within ~2 blocks of the partner, regardless of where the midpoint is.
        double d2 = citizen.distanceToSqr(partnerEntity);
        if (d2 < 4.0) {
            active.markArrived(citizen.getUUID());
            citizen.getNavigation().stop();
        } else if (citizen.getNavigation().isDone()) {
            // Pathfinder gave up (e.g. partner moved). Re-target so we keep approaching.
            citizen.getNavigation().moveTo(partnerEntity, speedModifier);
        }
        if (!isInitiator) return;
        if (t > Conversation.WALK_TIMEOUT_TICKS) {
            active.transitionTo(Phase.DONE, now);
            return;
        }
        if (active.bothArrived()) {
            active.transitionTo(Phase.FACE_OFF, now);
        }
    }

    private void tickFaceOff(int t, long now) {
        faceOther();
        if (!isInitiator) return;
        if (t >= Conversation.FACE_OFF_TICKS) {
            active.currentBubble = 0;
            active.transitionTo(Phase.BUBBLE, now);
            onBubbleEntry();
        }
    }

    private void tickBubble(int t, long now) {
        faceOther();
        // Idempotent bubble-icon write — both sides do this every tick. Synched-data only emits
        // a packet on actual value change, so repeated writes are cheap. The initiator-only
        // entry actions (pop sound, match accumulation) happen at the transition site instead
        // of here, because the same-tick transitionTo means we never see t == 0 in this tick.
        // Pack the per-citizen subType (happiness bucket / workstation type ordinal) into the
        // bubble id so the client can render the right icon without a separate sync slot.
        ConversationTopic mine = active.topicFor(citizen.getUUID(), active.currentBubble);
        int packed = mine.packBubbleId(resolveSubType(mine));
        if (citizen.getBubbleTopic() != packed) {
            citizen.setBubbleTopic(packed);
        }
        if (!isInitiator) return;
        if (t >= Conversation.BUBBLE_TICKS) {
            // Clear both sides' bubbles so the gap actually looks blank, then advance.
            citizen.setBubbleTopic(0);
            if (partnerEntity != null) partnerEntity.setBubbleTopic(0);
            Phase next = active.currentBubble < 2 ? Phase.GAP : Phase.RESOLVING;
            active.transitionTo(next, now);
            if (next == Phase.RESOLVING) onResolvingEntry();
        }
    }

    private void tickGap(int t, long now) {
        faceOther();
        if (!isInitiator) return;
        if (t >= Conversation.GAP_TICKS) {
            active.currentBubble++;
            active.transitionTo(Phase.BUBBLE, now);
            onBubbleEntry();
        }
    }

    private void tickResolving(int t, long now) {
        faceOther();
        if (!isInitiator) return;
        // Outcome was applied at the transitionTo site in tickBubble — just count down here.
        if (t >= Conversation.RESOLVING_TICKS) {
            active.transitionTo(Phase.DONE, now);
        }
    }

    /** Initiator-only entry actions for a BUBBLE phase: play the pop sound and count matches.
     *  Called by the transition site (tickFaceOff / tickGap) rather than from tickBubble because
     *  same-tick transitions never let tickBubble see {@code t == 0}. */
    private void onBubbleEntry() {
        if (active == null || partnerEntity == null) return;
        playPop();
        UUID partnerId = active.otherSide(citizen.getUUID());
        if (partnerId != null) {
            ConversationTopic mine = active.topicFor(citizen.getUUID(), active.currentBubble);
            ConversationTopic theirs = active.topicFor(partnerId, active.currentBubble);
            if (mine == theirs) active.matches++;
        }
    }

    /** Initiator-only entry actions for RESOLVING: apply the relationship delta, the outcome
     *  particle burst, and (for non-neutral outcomes) the matching per-partner thought on each
     *  citizen. The thought has to be added to BOTH sides — applyMutual handles the relationship
     *  symmetry but thoughts are per-citizen, so this method does the symmetric write itself.
     *  Called at the tickBubble transition site for the same reason {@link #onBubbleEntry} is. */
    private void onResolvingEntry() {
        if (active == null || active.outcomeApplied || partnerEntity == null) return;
        if (!(citizen.level() instanceof ServerLevel sl)) return;
        int delta = Conversation.outcomeDelta(active.matches);
        SocialEvents.applyMutual(citizen, partnerEntity, delta);
        SocialEvents.spawnOutcomeParticles(citizen, partnerEntity, active.matches);
        // Map match count → per-partner thought kind. Neutral (2) gets no thought — it's a
        // forgettable interaction, not memorable in either direction.
        ThoughtKind kind = switch (active.matches) {
            case 0 -> ThoughtKind.FIGHT_WITH;
            case 1 -> ThoughtKind.ARGUMENT_WITH;
            case 3 -> ThoughtKind.GREAT_CONVERSATION_WITH;
            default -> null;
        };
        if (kind != null) {
            long now = sl.getGameTime();
            citizen.getThoughts().add(kind, partnerEntity.getUUID(), now, sl.random);
            citizen.recomputeHappiness();
            partnerEntity.getThoughts().add(kind, citizen.getUUID(), now, sl.random);
            partnerEntity.recomputeHappiness();
        }
        // Step 14: a 0-match FIGHT_WITH can escalate to a real fight during anarchy or low
        // compliance. A 25% chance on top of the conversation outcome — most arguments still
        // resolve as just a relationship hit. Both sides need to be still alive and adult
        // (children don't brawl). Escalation arms a one-swing retaliation via the same brawl
        // mechanic that handles citizen-on-citizen punches.
        if (active.matches == 0 && shouldEscalateConflict(sl)) {
            ConflictGoal.escalate(citizen, partnerEntity, sl);
        }
        active.outcomeApplied = true;
    }

    /** Step 14 gate. Escalation fires only when the settlement is in anarchy OR either
     *  participant's compliance is below the threshold — same shape as the plan's per-tick
     *  refusal trigger. Returns false if either side is a child / already injured / dead, so
     *  the brawl can't be the killing blow on someone already on death's door. */
    private boolean shouldEscalateConflict(ServerLevel sl) {
        if (partnerEntity == null) return false;
        if (citizen.isChild() || partnerEntity.isChild()) return false;
        if (!citizen.isAlive() || !partnerEntity.isAlive()) return false;
        com.bannerbound.core.api.settlement.Settlement s = citizen.getSettlement();
        if (s == null) return false;
        boolean anarchy = s.governmentType()
            == com.bannerbound.core.api.settlement.Settlement.Government.NONE;
        boolean lowCompliance = citizen.getCompliance() < CONFLICT_LOW_COMPLIANCE_THRESHOLD
            || partnerEntity.getCompliance() < CONFLICT_LOW_COMPLIANCE_THRESHOLD;
        if (!anarchy && !lowCompliance) return false;
        return sl.random.nextDouble() < CONFLICT_ESCALATION_CHANCE;
    }

    private static final double CONFLICT_ESCALATION_CHANCE = 0.25;
    private static final int CONFLICT_LOW_COMPLIANCE_THRESHOLD = 30;

    // ─── Helpers ───────────────────────────────────────────────────────────────────────────────

    /** Point head + body at the partner each tick. Matches {@code FisherWorkGoal.faceOutward()}. */
    private void faceOther() {
        if (partnerEntity == null) return;
        double dx = partnerEntity.getX() - citizen.getX();
        double dz = partnerEntity.getZ() - citizen.getZ();
        if (dx * dx + dz * dz < 1.0e-4) return; // stacked — no yaw to compute
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        citizen.setYBodyRot(yaw);
        citizen.setYHeadRot(yaw);
        citizen.getLookControl().setLookAt(
            partnerEntity.getX(), partnerEntity.getEyeY(), partnerEntity.getZ());
    }

    /** Roll a uniform random cooldown in [MIN, MAX] ticks. Server-only — falls back to MIN if
     *  the citizen happens not to be on a ServerLevel at stop() time (shouldn't happen for an
     *  AI goal but cheap to guard). */
    private int rollCooldown() {
        int span = CONV_COOLDOWN_MAX_TICKS - CONV_COOLDOWN_MIN_TICKS;
        if (span <= 0) return CONV_COOLDOWN_MIN_TICKS;
        if (citizen.level() instanceof ServerLevel sl) {
            return CONV_COOLDOWN_MIN_TICKS + sl.random.nextInt(span + 1);
        }
        return CONV_COOLDOWN_MIN_TICKS;
    }

    /** Resolves the per-citizen subType to pack alongside the bubble's topic id. The packed int
     *  ships to the client which uses it to pick the icon variant. Static topics return 0. */
    private int resolveSubType(ConversationTopic topic) {
        return switch (topic) {
            case CULTURE, FOOD, SCIENCE -> 0;
            case HAPPINESS -> happinessBucket(citizen.getHappiness());
            case JOB -> jobSubType(citizen);
        };
    }

    /** Three happiness buckets matching the client's {@code Icons.happiness} thresholds:
     *  0 = low (&lt;40%), 1 = mid (40-70%), 2 = high (≥70%). Keep these thresholds in sync with
     *  {@link com.bannerbound.core.client.Icons#happiness(int, int)} or the bucket→glyph map
     *  will go out of register. */
    private static int happinessBucket(int happiness) {
        if (happiness >= 70) return 2;
        if (happiness >= 40) return 1;
        return 0;
    }

    /** Job-type ordinal for the JOB topic. 0 = no job (no icon); 1.. = the citizen's job type,
     *  mapped via {@link com.bannerbound.core.social.WorkstationIcons} which the client also reads
     *  to resolve the ordinal back into an item icon. */
    private static int jobSubType(CitizenEntity c) {
        String jobType = c.getJobType();
        if (jobType == null) return 0;
        return com.bannerbound.core.social.WorkstationIcons.ordinalOf(jobType);
    }

    private void playPop() {
        if (!(citizen.level() instanceof ServerLevel sl)) return;
        if (active == null) return;
        BlockPos meet = active.standFor(citizen.getUUID());
        sl.playSound(null, meet, BannerboundCore.BUBBLE_POP_SOUND.get(),
            SoundSource.NEUTRAL, 0.35f, 1.0f);
    }
}
