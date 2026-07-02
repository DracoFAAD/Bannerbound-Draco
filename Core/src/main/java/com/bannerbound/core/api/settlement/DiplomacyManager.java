package com.bannerbound.core.api.settlement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.chat.BannerboundGameRules;
import com.bannerbound.core.network.CloseSettlementScreensPayload;
import com.bannerbound.core.network.DiplomacyActionPayload;
import com.bannerbound.core.network.DiplomacyObjectivePayload;
import com.bannerbound.core.network.DiplomacyStatePayload;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

public final class DiplomacyManager {
    private DiplomacyManager() {}

    public static final int DISCOVERY_RANGE_BLOCKS = 64;
    public static final int WAR_WARNING_TICKS = 20 * 60;
    public static final int PAIR_REDECLARE_COOLDOWN_TICKS = 20 * 60 * 30;
    public static final int WINNER_NEW_WAR_COOLDOWN_TICKS = 20 * 60 * 30;
    public static final int CAPTURE_TIMEOUT_TICKS = 20 * 60 * 30;
    public static final int DROPPED_STANDARD_RETURN_TICKS = 20 * 60 * 5;

    private static final Map<UUID, SupportBreak> RECENT_SUPPORT_BREAKS = new HashMap<>();
    private static int slowTick;
    private static int syncTick;

    private record SupportBreak(UUID actorSettlementId, UUID actorPlayerId, String actorName, int ticksLeft) {}

    public static void tick(MinecraftServer server) {
        if (server == null) return;
        ServerLevel level = server.overworld();
        SettlementData data = SettlementData.get(level);
        tickPendingWars(server, data);
        tickCapturedTimeouts(server, data);
        tickSupportBreaks();
        if (++slowTick >= 20) {
            slowTick = 0;
            discoverNearbyPlayers(server, data);
            reconcileStolenStandards(server, data);
            clearFinishedRallies(server, data);
        }
        if (++syncTick >= 20) {
            syncTick = 0;
            broadcastAllDiplomacyState(server, data);
            broadcastObjectiveState(server, data);
        }
    }

    public static boolean discover(MinecraftServer server, Settlement first, Settlement second, String reason) {
        if (server == null || first == null || second == null || first.id().equals(second.id())) return false;
        SettlementData data = SettlementData.get(server.overworld());
        SettlementData.DiplomacyRelation relation = data.relation(first.id(), second.id());
        if (relation == null || relation.discovered) return false;
        relation.discovered = true;
        data.setDirty();
        Component msg = Component.translatable("bannerbound.diplomacy.discovered",
            first.factionName(), second.factionName()).withStyle(ChatFormatting.AQUA);
        SettlementManager.broadcastToSettlement(server, first, msg);
        SettlementManager.broadcastToSettlement(server, second, msg);
        broadcastDiplomacyState(server, first);
        broadcastDiplomacyState(server, second);
        return true;
    }

    public static void discoverFromContact(MinecraftServer server, @Nullable Settlement actorSettlement,
                                           @Nullable Settlement otherSettlement, String reason) {
        if (actorSettlement == null || otherSettlement == null) return;
        discover(server, actorSettlement, otherSettlement, reason);
    }

    private static void discoverNearbyPlayers(MinecraftServer server, SettlementData data) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        double rangeSq = DISCOVERY_RANGE_BLOCKS * DISCOVERY_RANGE_BLOCKS;
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer a = players.get(i);
            Settlement sa = data.getByPlayer(a.getUUID());
            if (sa == null) continue;
            for (int j = i + 1; j < players.size(); j++) {
                ServerPlayer b = players.get(j);
                if (a.level() != b.level()) continue;
                if (a.distanceToSqr(b) > rangeSq) continue;
                Settlement sb = data.getByPlayer(b.getUUID());
                if (sb != null && !sa.id().equals(sb.id())) {
                    discover(server, sa, sb, "proximity");
                }
            }
            for (com.bannerbound.core.entity.CitizenEntity citizen
                    : a.serverLevel().getEntitiesOfClass(com.bannerbound.core.entity.CitizenEntity.class,
                        a.getBoundingBox().inflate(DISCOVERY_RANGE_BLOCKS))) {
                if (citizen.getSettlementId() == null || citizen.getSettlementId().equals(sa.id())) continue;
                Settlement other = data.getById(citizen.getSettlementId());
                if (other != null) discover(server, sa, other, "citizen");
            }
        }
    }

    public static boolean isActiveWar(SettlementData data, UUID first, UUID second) {
        SettlementData.DiplomacyRelation relation = data.existingRelation(first, second);
        return relation != null && relation.warActive;
    }

    public static boolean isActiveWarEnemy(SettlementData data, UUID actorSettlementId, UUID ownerSettlementId) {
        if (actorSettlementId == null || ownerSettlementId == null || actorSettlementId.equals(ownerSettlementId)) {
            return false;
        }
        return isActiveWar(data, actorSettlementId, ownerSettlementId);
    }

    public static boolean canActInClaim(SettlementData data, ChunkPos chunk, UUID actorId) {
        Settlement owner = data.getByChunk(chunk.toLong());
        if (owner == null) return true;
        if (actorId == null) return false;
        Settlement actorSettlement = data.getByPlayer(actorId);
        if (actorSettlement == null) return false;
        if (actorSettlement.id().equals(owner.id())) return true;
        return isActiveWarEnemy(data, actorSettlement.id(), owner.id());
    }

    public static boolean canDamageInClaim(SettlementData data, ChunkPos chunk, @Nullable Entity sourceEntity) {
        Settlement owner = data.getByChunk(chunk.toLong());
        if (owner == null) return true;
        // Claim protection governs player/faction aggression. Source-less natural damage
        // (starvation, fall, fire) and non-player simulation damage (mobs, citizen brawls)
        // must still apply inside settlements.
        if (!(sourceEntity instanceof ServerPlayer player)) return true;
        Settlement attacker = data.getByPlayer(player.getUUID());
        return attacker != null
            && (attacker.id().equals(owner.id()) || isActiveWarEnemy(data, attacker.id(), owner.id()));
    }

    public static boolean canAccessStockpile(ServerPlayer player, BlockPos pos) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement owner = data.getByChunk(new ChunkPos(pos).toLong());
        if (owner == null) return true;
        Settlement mine = data.getByPlayer(player.getUUID());
        if (mine == null) return false;
        return mine.id().equals(owner.id()) || isActiveWarEnemy(data, mine.id(), owner.id());
    }

    public static boolean canOwnerBreakStandard(SettlementData data, Settlement settlement) {
        for (SettlementData.DiplomacyRelation relation : data.diplomacyRelations()) {
            if (!relation.involves(settlement.id())) continue;
            if (relation.pending() || relation.warActive) return false;
        }
        return true;
    }

    public static boolean hasStolenOrCapturedStandard(SettlementData data, UUID settlementId) {
        if (data.stolenStandards().containsKey(settlementId)) return true;
        for (SettlementData.DiplomacyRelation relation : data.diplomacyRelations()) {
            if (settlementId.equals(relation.capturedTarget)) return true;
        }
        return false;
    }

    public static boolean isPublicStandardValid(ServerLevel level, Settlement settlement) {
        BlockPos banner = settlement.bannerPos();
        return banner != null && isPublicStandardValidAt(level, settlement, banner);
    }

    public static boolean isPublicStandardValidAt(ServerLevel level, Settlement settlement, BlockPos banner) {
        BlockPos townHall = settlement.townHallPos();
        if (townHall == null || banner == null) return false;
        if (!level.isLoaded(banner)) return true;
        BlockState state = level.getBlockState(banner);
        if (!FactionBanner.isBanner(state)) return false;
        int dx = banner.getX() - townHall.getX();
        int dz = banner.getZ() - townHall.getZ();
        if (dx * dx + dz * dz > 24 * 24) return false;
        if (Math.abs(banner.getY() - townHall.getY()) > 8) return false;
        return hasOpenAccessSide(level, banner);
    }

    private static boolean hasOpenAccessSide(CollisionGetter level, BlockPos banner) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos feet = banner.relative(direction);
            BlockPos head = feet.above();
            if (isOpen(level, feet) && isOpen(level, head)) return true;
        }
        return false;
    }

    private static boolean isOpen(CollisionGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty();
    }

    public static void routeAction(ServerPlayer actor, int action, @Nullable UUID targetId) {
        MinecraftServer server = actor.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement actorSettlement = data.getByPlayer(actor.getUUID());
        if (actorSettlement == null || !actorSettlement.members().contains(actor.getUUID())) return;
        // City-state targets (war/peace/capture) route to their own manager (CITY_STATES §2).
        if (targetId != null) {
            com.bannerbound.core.citystate.CityState cs =
                com.bannerbound.core.citystate.CityStateData.get(server.overworld()).getById(targetId);
            if (cs != null) {
                com.bannerbound.core.citystate.CityStateWarManager.routeAction(actor, action, cs);
                return;
            }
        }
        Settlement target = targetId == null ? null : data.getById(targetId);
        if (action != DiplomacyActionPayload.TOGGLE_RALLY && target == null) return;

        switch (actorSettlement.governmentType()) {
            case NONE -> performAction(server, actorSettlement, actor.getUUID(), action, targetId, false);
            case COUNCIL -> {
                ChatVoteManager.Kind kind = voteKind(action);
                if (kind != null && councilProposalValid(server, actor, actorSettlement, action, targetId)) {
                    ChatVoteManager.start(server, actorSettlement, kind, actor,
                        targetId, target == null ? "" : target.factionName());
                }
            }
            case CHIEFDOM -> {
                if (actorSettlement.canActAsChief(actor.getUUID())) {
                    performAction(server, actorSettlement, actor.getUUID(), action, targetId, false);
                } else {
                    pingChief(server, actorSettlement, Component.translatable(
                        "bannerbound.diplomacy.suggested",
                        actor.getGameProfile().getName(), actionName(action),
                        target == null ? actorSettlement.factionName() : target.factionName())
                        .withStyle(ChatFormatting.GOLD));
                    actor.displayClientMessage(Component.translatable("bannerbound.suggest.sent")
                        .withStyle(ChatFormatting.GRAY), true);
                }
            }
        }
    }

    /** Validates a COUNCIL proposal up front so members aren't asked to vote on something that
     *  would silently fail when {@link #performAction} re-checks it at resolution (already at war,
     *  on cooldown, target offline, nothing to raze, rally with no war). On rejection the proposer
     *  gets a red message and no vote is started. Mirrors the guards in
     *  {@link #declareWar}/{@link #offerPeace}/{@link #razeCaptured}. */
    private static boolean councilProposalValid(MinecraftServer server, ServerPlayer actor,
                                                Settlement actorSettlement, int action,
                                                @Nullable UUID targetId) {
        SettlementData data = SettlementData.get(server.overworld());
        long now = server.overworld().getGameTime();
        Settlement target = targetId == null ? null : data.getById(targetId);
        SettlementData.DiplomacyRelation rel = target == null ? null
            : data.existingRelation(actorSettlement.id(), target.id());
        net.minecraft.network.chat.MutableComponent err = null;
        switch (action) {
            case DiplomacyActionPayload.DECLARE_WAR -> {
                if (target == null || actorSettlement.id().equals(target.id())) return false;
                Long winnerUntil = data.winnerNoNewWarUntil().get(actorSettlement.id());
                if (rel != null && (rel.warActive || rel.pending() || rel.capturedFinal())) {
                    err = Component.translatable("bannerbound.diplomacy.error.already_war", target.factionName());
                } else if (winnerUntil != null && winnerUntil > now) {
                    err = Component.translatable("bannerbound.diplomacy.error.winner_cooldown", seconds(winnerUntil - now));
                } else if (rel != null && rel.redeclareAfter > now) {
                    err = Component.translatable("bannerbound.diplomacy.error.cooldown",
                        target.factionName(), seconds(rel.redeclareAfter - now));
                } else if (!allowOfflineWar(server) && SettlementManager.countOnlineMembers(server, target) <= 0) {
                    err = Component.translatable("bannerbound.diplomacy.error.target_offline", target.factionName());
                }
            }
            case DiplomacyActionPayload.OFFER_PEACE -> {
                if (target == null || rel == null || (!rel.warActive && !rel.pending())) return false;
                if (rel.capturedFinal()) {
                    err = Component.translatable("bannerbound.diplomacy.error.captured_no_peace", target.factionName());
                }
            }
            case DiplomacyActionPayload.RAZE -> {
                if (target == null || rel == null || !rel.capturedFinal()
                        || !actorSettlement.id().equals(rel.capturedBy)) {
                    return false;
                }
            }
            case DiplomacyActionPayload.TOGGLE_RALLY -> {
                if (!isSettlementAtWar(data, actorSettlement.id())) {
                    err = Component.translatable("bannerbound.diplomacy.error.rally_no_war");
                }
            }
            default -> { return false; }
        }
        if (err != null) {
            actor.displayClientMessage(err.withStyle(ChatFormatting.RED), false);
            return false;
        }
        return true;
    }

    @Nullable
    private static ChatVoteManager.Kind voteKind(int action) {
        return switch (action) {
            case DiplomacyActionPayload.DECLARE_WAR -> ChatVoteManager.Kind.DECLARE_WAR;
            case DiplomacyActionPayload.OFFER_PEACE -> ChatVoteManager.Kind.OFFER_PEACE;
            case DiplomacyActionPayload.TOGGLE_RALLY -> ChatVoteManager.Kind.TOGGLE_RALLY;
            case DiplomacyActionPayload.RAZE -> ChatVoteManager.Kind.RAZE_CAPTURED;
            default -> null;
        };
    }

    public static void performCouncilAction(MinecraftServer server, Settlement settlement,
                                            ChatVoteManager.ChatVote vote) {
        int action = switch (vote.kind) {
            case DECLARE_WAR -> DiplomacyActionPayload.DECLARE_WAR;
            case OFFER_PEACE -> DiplomacyActionPayload.OFFER_PEACE;
            case TOGGLE_RALLY -> DiplomacyActionPayload.TOGGLE_RALLY;
            case RAZE_CAPTURED -> DiplomacyActionPayload.RAZE;
            default -> -1;
        };
        if (action >= 0) performAction(server, settlement, vote.initiator, action, vote.targetCitizen, false);
    }

    public static void performAction(MinecraftServer server, Settlement actorSettlement, UUID actorId,
                                     int action, @Nullable UUID targetId, boolean force) {
        switch (action) {
            case DiplomacyActionPayload.DECLARE_WAR -> declareWar(server, actorSettlement, targetId, force);
            case DiplomacyActionPayload.OFFER_PEACE -> offerPeace(server, actorSettlement, targetId, force);
            case DiplomacyActionPayload.TOGGLE_RALLY -> toggleRally(server, actorSettlement);
            case DiplomacyActionPayload.RAZE -> razeCaptured(server, actorSettlement, targetId, force);
            default -> { }
        }
    }

    public static boolean declareWar(MinecraftServer server, Settlement declarer, @Nullable UUID targetId,
                                     boolean force) {
        if (targetId == null || declarer.id().equals(targetId)) return false;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement target = data.getById(targetId);
        if (target == null) return false;
        SettlementData.DiplomacyRelation relation = data.relation(declarer.id(), target.id());
        if (relation == null) return false;
        relation.discovered = true;
        long now = server.overworld().getGameTime();
        if (!force) {
            if (relation.warActive || relation.pending() || relation.capturedFinal()) {
                messageSettlement(server, declarer, "bannerbound.diplomacy.error.already_war", target.factionName());
                return false;
            }
            Long winnerUntil = data.winnerNoNewWarUntil().get(declarer.id());
            if (winnerUntil != null && winnerUntil > now) {
                messageSettlement(server, declarer, "bannerbound.diplomacy.error.winner_cooldown",
                    seconds(winnerUntil - now));
                return false;
            }
            if (relation.redeclareAfter > now) {
                messageSettlement(server, declarer, "bannerbound.diplomacy.error.cooldown",
                    target.factionName(), seconds(relation.redeclareAfter - now));
                return false;
            }
            if (!allowOfflineWar(server) && SettlementManager.countOnlineMembers(server, target) <= 0) {
                messageSettlement(server, declarer, "bannerbound.diplomacy.error.target_offline", target.factionName());
                return false;
            }
        }
        relation.pendingDeclarer = declarer.id();
        relation.pendingTarget = target.id();
        relation.pendingTicksRemaining = WAR_WARNING_TICKS;
        relation.peaceOfferedByFirst = false;
        relation.peaceOfferedBySecond = false;
        data.setDirty();
        SettlementManager.broadcastToSettlement(server, declarer, Component.translatable(
            "bannerbound.diplomacy.war.warning_attacker", target.factionName()).withStyle(ChatFormatting.RED));
        SettlementManager.broadcastToSettlement(server, target, Component.translatable(
            "bannerbound.diplomacy.war.warning_defender", declarer.factionName()).withStyle(ChatFormatting.RED));
        broadcastDiplomacyState(server, declarer);
        broadcastDiplomacyState(server, target);
        return true;
    }

    private static void tickPendingWars(MinecraftServer server, SettlementData data) {
        boolean dirty = false;
        for (SettlementData.DiplomacyRelation relation : data.diplomacyRelations()) {
            if (!relation.pending()) continue;
            Settlement target = data.getById(relation.pendingTarget);
            Settlement declarer = data.getById(relation.pendingDeclarer);
            if (target == null || declarer == null) {
                relation.pendingDeclarer = null;
                relation.pendingTarget = null;
                relation.pendingTicksRemaining = 0;
                dirty = true;
                continue;
            }
            if (allowOfflineWar(server) || SettlementManager.countOnlineMembers(server, target) > 0) {
                relation.pendingTicksRemaining--;
                dirty = true;
            }
            if (relation.pendingTicksRemaining <= 0) {
                activateWar(server, data, relation, declarer, target);
                dirty = true;
            }
        }
        if (dirty) data.setDirty();
    }

    private static void activateWar(MinecraftServer server, SettlementData data,
                                    SettlementData.DiplomacyRelation relation,
                                    Settlement declarer, Settlement target) {
        relation.pendingDeclarer = null;
        relation.pendingTarget = null;
        relation.pendingTicksRemaining = 0;
        relation.warActive = true;
        relation.warStartedAt = server.overworld().getGameTime();
        relation.peaceOfferedByFirst = false;
        relation.peaceOfferedBySecond = false;
        SettlementManager.broadcastToSettlement(server, declarer, Component.translatable(
            "bannerbound.diplomacy.war.started", target.factionName()).withStyle(ChatFormatting.RED));
        SettlementManager.broadcastToSettlement(server, target, Component.translatable(
            "bannerbound.diplomacy.war.started", declarer.factionName()).withStyle(ChatFormatting.RED));
        PolicyEffects.onWarStarted(server, declarer, target, relation);
        PolicyEffects.onWarStarted(server, target, declarer, relation);
        PolicyEffects.syncWarMoraleNow(server, declarer);
        PolicyEffects.syncWarMoraleNow(server, target);
        broadcastDiplomacyState(server, declarer);
        broadcastDiplomacyState(server, target);
    }

    public static boolean offerPeace(MinecraftServer server, Settlement actorSettlement,
                                     @Nullable UUID targetId, boolean force) {
        if (targetId == null || actorSettlement.id().equals(targetId)) return false;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement target = data.getById(targetId);
        if (target == null) return false;
        SettlementData.DiplomacyRelation relation = data.existingRelation(actorSettlement.id(), target.id());
        if (relation == null || (!relation.warActive && !relation.pending())) return false;
        if (relation.capturedFinal() && !force) {
            messageSettlement(server, actorSettlement, "bannerbound.diplomacy.error.captured_no_peace", target.factionName());
            return false;
        }
        if (force) {
            endWarWithCooldown(server, data, relation, actorSettlement, target, true);
            return true;
        }
        relation.setPeaceOfferedBy(actorSettlement.id(), true);
        data.setDirty();
        if (relation.peaceOfferedBy(actorSettlement.id()) && relation.peaceOfferedBy(target.id())) {
            endWarWithCooldown(server, data, relation, actorSettlement, target, true);
        } else {
            SettlementManager.broadcastToSettlement(server, actorSettlement, Component.translatable(
                "bannerbound.diplomacy.peace.offered_self", target.factionName()).withStyle(ChatFormatting.GREEN));
            SettlementManager.broadcastToSettlement(server, target, Component.translatable(
                "bannerbound.diplomacy.peace.offered_other", actorSettlement.factionName()).withStyle(ChatFormatting.GREEN));
        }
        broadcastDiplomacyState(server, actorSettlement);
        broadcastDiplomacyState(server, target);
        return true;
    }

    private static void endWarWithCooldown(MinecraftServer server, SettlementData data,
                                           SettlementData.DiplomacyRelation relation,
                                           Settlement first, Settlement second, boolean returnStandards) {
        if (returnStandards) {
            returnStandardIfOwned(server, data, first.id());
            returnStandardIfOwned(server, data, second.id());
        }
        relation.warActive = false;
        relation.pendingDeclarer = null;
        relation.pendingTarget = null;
        relation.pendingTicksRemaining = 0;
        relation.peaceOfferedByFirst = false;
        relation.peaceOfferedBySecond = false;
        relation.capturedTarget = null;
        relation.capturedBy = null;
        relation.capturedAt = 0L;
        relation.redeclareAfter = server.overworld().getGameTime() + PAIR_REDECLARE_COOLDOWN_TICKS;
        data.setDirty();
        SettlementManager.broadcastToSettlement(server, first, Component.translatable(
            "bannerbound.diplomacy.peace.made", second.factionName()).withStyle(ChatFormatting.GREEN));
        SettlementManager.broadcastToSettlement(server, second, Component.translatable(
            "bannerbound.diplomacy.peace.made", first.factionName()).withStyle(ChatFormatting.GREEN));
        PolicyEffects.syncWarMoraleNow(server, first);
        PolicyEffects.syncWarMoraleNow(server, second);
        broadcastDiplomacyState(server, first);
        broadcastDiplomacyState(server, second);
    }

    private static void toggleRally(MinecraftServer server, Settlement settlement) {
        SettlementData data = SettlementData.get(server.overworld());
        boolean next = !data.isRallying(settlement.id());
        // Rally only means something while there's something to rally AGAINST — an active war or a
        // barbarian raid (clearFinishedRallies wipes it the moment both end). Refuse to turn it on at
        // peace instead of letting it flicker on→off for a tick.
        if (next && !canRally(data, settlement.id())) {
            SettlementManager.broadcastToSettlement(server, settlement, Component.translatable(
                "bannerbound.diplomacy.error.rally_no_war").withStyle(ChatFormatting.RED));
            return;
        }
        data.setRallying(settlement.id(), next);
        SettlementManager.broadcastToSettlement(server, settlement, Component.translatable(
            next ? "bannerbound.diplomacy.rally.on" : "bannerbound.diplomacy.rally.off")
            .withStyle(next ? ChatFormatting.GOLD : ChatFormatting.GRAY));
        broadcastDiplomacyState(server, settlement);
    }

    private static void clearFinishedRallies(MinecraftServer server, SettlementData data) {
        Set<UUID> clear = new HashSet<>();
        for (UUID id : data.rallyingSettlements()) {
            if (!canRally(data, id)) clear.add(id);
        }
        if (clear.isEmpty()) return;
        for (UUID id : clear) data.rallyingSettlements().remove(id);
        data.setDirty();
    }

    public static boolean isSettlementAtWar(SettlementData data, UUID settlementId) {
        for (SettlementData.DiplomacyRelation relation : data.diplomacyRelations()) {
            if (relation.involves(settlementId) && relation.warActive) return true;
        }
        return false;
    }

    /** A settlement may call a RALLY while it is at war OR while a barbarian raid is besieging it — the
     *  two situations worth dropping all work and arming every citizen, not just the watch. Outside
     *  those, the standing guard handles defence and ordinary citizens keep working. */
    public static boolean canRally(SettlementData data, UUID settlementId) {
        return isSettlementAtWar(data, settlementId)
            || com.bannerbound.core.barbarian.BarbarianCampManager.isSettlementRaided(settlementId);
    }

    public static void recordPotentialSupportBreak(ServerPlayer breaker, Settlement target, BlockPos brokenPos) {
        MinecraftServer server = breaker.getServer();
        if (server == null || target == null || target.bannerPos() == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement breakerSettlement = data.getByPlayer(breaker.getUUID());
        if (breakerSettlement == null || !isActiveWarEnemy(data, breakerSettlement.id(), target.id())) return;
        if (!isSupportFor(server.overworld(), target.bannerPos(), brokenPos)) return;
        RECENT_SUPPORT_BREAKS.put(target.id(),
            new SupportBreak(breakerSettlement.id(), breaker.getUUID(), breaker.getGameProfile().getName(), 80));
    }

    private static boolean isSupportFor(ServerLevel level, BlockPos bannerPos, BlockPos brokenPos) {
        if (!level.isLoaded(bannerPos)) return false;
        BlockState state = level.getBlockState(bannerPos);
        if (state.hasProperty(BannerBlock.ROTATION) && bannerPos.below().equals(brokenPos)) return true;
        if (state.hasProperty(WallBannerBlock.FACING)) {
            Direction facing = state.getValue(WallBannerBlock.FACING);
            return bannerPos.relative(facing.getOpposite()).equals(brokenPos);
        }
        return false;
    }

    private static void tickSupportBreaks() {
        RECENT_SUPPORT_BREAKS.replaceAll((id, support) ->
            new SupportBreak(support.actorSettlementId(), support.actorPlayerId(), support.actorName(), support.ticksLeft() - 1));
        RECENT_SUPPORT_BREAKS.entrySet().removeIf(e -> e.getValue().ticksLeft() <= 0);
    }

    public static boolean consumeSupportLossAsTheft(ServerLevel level, Settlement target, BlockPos bannerPos) {
        SupportBreak support = RECENT_SUPPORT_BREAKS.remove(target.id());
        if (support == null) return false;
        SettlementData data = SettlementData.get(level);
        Settlement actorSettlement = data.getById(support.actorSettlementId());
        if (actorSettlement == null || !isActiveWarEnemy(data, actorSettlement.id(), target.id())) return false;
        ServerPlayer actor = level.getServer().getPlayerList().getPlayer(support.actorPlayerId());
        createStolenStandard(level, target, bannerPos, actor, actorSettlement, support.actorName());
        return true;
    }

    public static void createStolenStandard(ServerLevel level, Settlement target, BlockPos bannerPos,
                                            @Nullable ServerPlayer carrier,
                                            @Nullable Settlement carrierSettlement,
                                            String actorName) {
        SettlementData data = SettlementData.get(level);
        if (hasStolenOrCapturedStandard(data, target.id())) return;
        FactionBanner.lose(level, target, bannerPos, false, actorName == null ? "" : actorName);
        SettlementData.StolenStandard standard = new SettlementData.StolenStandard(target.id());
        ItemStack stack = stolenStandardStack(target, level);
        if (carrier != null && carrierSettlement != null && isActiveWarEnemy(data, carrierSettlement.id(), target.id())
                && carrier.getInventory().add(stack)) {
            standard.carrierPlayerId = carrier.getUUID();
            standard.carrierSettlementId = carrierSettlement.id();
        } else {
            ItemEntity item = new ItemEntity(level, bannerPos.getX() + 0.5, bannerPos.getY() + 0.5,
                bannerPos.getZ() + 0.5, stack);
            prepareStolenStandardItem(item);
            level.addFreshEntity(item);
            standard.droppedPos = bannerPos.immutable();
            standard.droppedAt = level.getGameTime();
            standard.autoReturnAt = level.getGameTime() + DROPPED_STANDARD_RETURN_TICKS;
        }
        data.stolenStandards().put(target.id(), standard);
        data.setDirty();
        SettlementManager.broadcastToSettlement(level.getServer(), target, Component.translatable(
            "bannerbound.diplomacy.standard.stolen", target.factionName()).withStyle(ChatFormatting.RED));
        broadcastWarPairState(level.getServer(), target.id());
    }

    public static ItemStack stolenStandardStack(Settlement target, ServerLevel level) {
        ItemStack stack = FactionBanner.designedItem(target, level.registryAccess(), 1);
        stack.set(BannerboundCore.STOLEN_STANDARD_SETTLEMENT.get(), target.id().toString());
        stack.set(BannerboundCore.STOLEN_STANDARD_NAME.get(), target.factionName());
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable(
            "bannerbound.item.stolen_standard", target.factionName()));
        return stack;
    }

    public static boolean isStolenStandard(ItemStack stack) {
        return !stack.isEmpty() && stack.get(BannerboundCore.STOLEN_STANDARD_SETTLEMENT.get()) != null;
    }

    @Nullable
    public static UUID stolenStandardTarget(ItemStack stack) {
        String raw = stack.get(BannerboundCore.STOLEN_STANDARD_SETTLEMENT.get());
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static boolean prepareStolenStandardItem(ItemEntity item) {
        if (item == null || !isStolenStandard(item.getItem())) return false;
        item.setUnlimitedLifetime();
        item.setInvulnerable(true);
        return true;
    }

    public static boolean canPickupStolenStandard(ServerPlayer player, ItemEntity item) {
        if (!isStolenStandard(item.getItem())) return true;
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        SettlementData data = SettlementData.get(server.overworld());
        UUID targetId = stolenStandardTarget(item.getItem());
        if (targetId != null && com.bannerbound.core.citystate.CityStateData.get(server.overworld())
                .getById(targetId) != null) {
            return com.bannerbound.core.citystate.CityStateWarManager.canPickup(server, player, item, targetId);
        }
        Settlement target = targetId == null ? null : data.getById(targetId);
        if (target == null || !data.stolenStandards().containsKey(targetId)) {
            // No live tracked standard backs this item (target disbanded, or the standard was
            // already auto-returned/recovered leaving a stray "ghost" copy). Destroy it so it
            // can never be picked up, carried, or scored a second time.
            item.discard();
            return false;
        }
        Settlement mine = data.getByPlayer(player.getUUID());
        if (mine != null && mine.id().equals(target.id())) {
            item.discard();
            returnStandard(server, data, target, true);
            return false;
        }
        if (validWarCarrier(data, mine, target)) return true;
        player.displayClientMessage(Component.translatable(
            "bannerbound.diplomacy.standard.pickup_blocked", target.factionName())
            .withStyle(ChatFormatting.RED), true);
        return false;
    }

    public static void onStolenStandardPickedUp(ServerPlayer player, ItemStack originalStack) {
        UUID targetId = stolenStandardTarget(originalStack);
        if (targetId == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (com.bannerbound.core.citystate.CityStateData.get(server.overworld()).getById(targetId) != null) {
            com.bannerbound.core.citystate.CityStateWarManager.onPickedUp(server, player, targetId);
            return;
        }
        SettlementData data = SettlementData.get(server.overworld());
        Settlement target = data.getById(targetId);
        Settlement carrierSettlement = data.getByPlayer(player.getUUID());
        if (target == null || !validWarCarrier(data, carrierSettlement, target)) {
            removeOneStolenStandard(player, targetId);
            if (target != null && data.stolenStandards().containsKey(targetId)) {
                returnStandard(server, data, target, true);
            }
            return;
        }
        SettlementData.StolenStandard standard = data.stolenStandards().get(targetId);
        if (standard != null) {
            assignCarrier(standard, player, carrierSettlement);
            data.setDirty();
            broadcastWarPairState(server, target.id());
        } else {
            // Picked up a ghost copy with no live tracked standard — strip it from the player.
            removeOneStolenStandard(player, targetId);
        }
    }

    public static void onStolenStandardDropped(ServerPlayer player, ItemEntity item) {
        if (!prepareStolenStandardItem(item)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        UUID targetId = stolenStandardTarget(item.getItem());
        if (targetId != null && com.bannerbound.core.citystate.CityStateData.get(server.overworld())
                .getById(targetId) != null) {
            com.bannerbound.core.citystate.CityStateWarManager.onDropped(server, player, item, targetId);
            return;
        }
        Settlement target = targetId == null ? null : data.getById(targetId);
        Settlement carrierSettlement = data.getByPlayer(player.getUUID());
        if (target == null) {
            item.discard();
            return;
        }
        SettlementData.StolenStandard standard = data.stolenStandards().get(target.id());
        if (standard == null) {
            item.discard();
            return;
        }
        if (player.level() != server.overworld()) {
            item.discard();
            returnStandard(server, data, target, true);
            return;
        }
        if (!validWarCarrier(data, carrierSettlement, target)) {
            item.discard();
            if (data.stolenStandards().containsKey(target.id())) {
                returnStandard(server, data, target, true);
            }
            return;
        }
        markDropped(standard, item.blockPosition(), server.overworld().getGameTime());
        data.setDirty();
        broadcastWarPairState(server, target.id());
    }

    public static void removeStolenStandardsFromContainer(ServerPlayer player, AbstractContainerMenu menu) {
        if (menu == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        boolean changed = false;
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            boolean slotChanged = false;
            while (isStolenStandard(stack)) {
                UUID targetId = stolenStandardTarget(stack);
                stack.shrink(1);
                com.bannerbound.core.citystate.CityState cs = targetId == null ? null
                    : com.bannerbound.core.citystate.CityStateData.get(server.overworld()).getById(targetId);
                if (cs != null) {
                    com.bannerbound.core.citystate.CityStateWarManager.returnStandard(server, cs);
                } else {
                    recoverContainerStandard(server, data, player, targetId);
                }
                changed = true;
                slotChanged = true;
            }
            if (slotChanged) {
                slot.set(stack.isEmpty() ? ItemStack.EMPTY : stack);
                slot.setChanged();
            }
        }
        if (changed) {
            data.setDirty();
            menu.broadcastChanges();
        }
    }

    public static boolean tryScoreStandard(ServerPlayer player, BlockPos clickedPos) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        if (com.bannerbound.core.citystate.CityStateWarManager.tryScore(server, player, clickedPos)) {
            return true;
        }
        SettlementData data = SettlementData.get(server.overworld());
        Settlement scorer = data.getByPlayer(player.getUUID());
        if (scorer == null || scorer.townHallPos() == null || !scorer.townHallPos().equals(clickedPos)) return false;
        ItemStack held = findHeldStolenStandard(player);
        UUID targetId = stolenStandardTarget(held);
        if (targetId == null) return false;
        Settlement target = data.getById(targetId);
        if (target == null || !data.stolenStandards().containsKey(targetId)) {
            // The item no longer corresponds to a live tracked standard (target gone, or the
            // standard was already returned/recovered). Consume the dud so it can't capture.
            removeOneStolenStandard(player, targetId);
            return true;
        }
        if (!isActiveWarEnemy(data, scorer.id(), target.id())) {
            player.sendSystemMessage(Component.translatable("bannerbound.diplomacy.standard.not_enemy")
                .withStyle(ChatFormatting.RED));
            return true;
        }
        if (!scorer.hasTownHall() || !FactionBanner.requireRaised(server.overworld(), player, scorer)) {
            player.sendSystemMessage(Component.translatable("bannerbound.diplomacy.standard.score_needs_standard")
                .withStyle(ChatFormatting.RED));
            return true;
        }
        SettlementData.DiplomacyRelation relation = data.existingRelation(scorer.id(), target.id());
        if (relation == null || !relation.warActive) return true;
        removeOneStolenStandard(player, target.id());
        data.stolenStandards().remove(target.id());
        relation.capturedTarget = target.id();
        relation.capturedBy = scorer.id();
        relation.capturedAt = server.overworld().getGameTime();
        relation.peaceOfferedByFirst = false;
        relation.peaceOfferedBySecond = false;
        target.setBannerPos(null);
        data.setDirty();
        PacketDistributor.sendToPlayer(player, CloseSettlementScreensPayload.INSTANCE);
        SettlementManager.broadcastToSettlement(server, scorer, Component.translatable(
            "bannerbound.diplomacy.capture.scored_attacker", target.factionName()).withStyle(ChatFormatting.GOLD));
        SettlementManager.broadcastToSettlement(server, target, Component.translatable(
            "bannerbound.diplomacy.capture.scored_defender", scorer.factionName()).withStyle(ChatFormatting.RED));
        broadcastDiplomacyState(server, scorer);
        broadcastDiplomacyState(server, target);
        broadcastObjectiveState(server, data);
        return true;
    }

    private static ItemStack findHeldStolenStandard(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (isStolenStandard(main)) return main;
        ItemStack off = player.getOffhandItem();
        return isStolenStandard(off) ? off : ItemStack.EMPTY;
    }

    private static boolean validWarCarrier(SettlementData data, @Nullable Settlement carrierSettlement,
                                           Settlement target) {
        return carrierSettlement != null
            && target != null
            && isActiveWarEnemy(data, carrierSettlement.id(), target.id());
    }

    private static void assignCarrier(SettlementData.StolenStandard standard, ServerPlayer player,
                                      Settlement carrierSettlement) {
        standard.carrierPlayerId = player.getUUID();
        standard.carrierSettlementId = carrierSettlement.id();
        standard.droppedPos = null;
        standard.droppedAt = 0L;
        standard.autoReturnAt = 0L;
    }

    private static void markDropped(SettlementData.StolenStandard standard, BlockPos pos, long now) {
        standard.carrierPlayerId = null;
        standard.carrierSettlementId = null;
        standard.droppedPos = pos.immutable();
        standard.droppedAt = now;
        standard.autoReturnAt = now + DROPPED_STANDARD_RETURN_TICKS;
    }

    private static void recoverContainerStandard(MinecraftServer server, SettlementData data,
                                                 ServerPlayer player, @Nullable UUID targetId) {
        if (targetId == null) return;
        Settlement target = data.getById(targetId);
        if (target == null) return;
        SettlementData.StolenStandard standard = data.stolenStandards().get(targetId);
        if (standard == null) return;
        Settlement carrierSettlement = data.getByPlayer(player.getUUID());
        if (!validWarCarrier(data, carrierSettlement, target)) {
            returnStandard(server, data, target, true);
            return;
        }
        ItemStack restored = stolenStandardStack(target, server.overworld());
        if (player.getInventory().add(restored)) {
            assignCarrier(standard, player, carrierSettlement);
        } else {
            ItemEntity item = new ItemEntity(server.overworld(), player.getX(), player.getY(), player.getZ(),
                restored);
            prepareStolenStandardItem(item);
            server.overworld().addFreshEntity(item);
            markDropped(standard, item.blockPosition(), server.overworld().getGameTime());
        }
        data.setDirty();
        broadcastWarPairState(server, target.id());
    }

    private static void removeOneStolenStandard(ServerPlayer player, UUID targetId) {
        removeOneFromInventory(player.getInventory(), targetId);
        ItemStack carried = player.containerMenu.getCarried();
        if (targetId.equals(stolenStandardTarget(carried))) {
            carried.shrink(1);
            player.containerMenu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
        }
        player.containerMenu.broadcastChanges();
    }

    private static boolean removeOneFromInventory(Inventory inv, UUID targetId) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (targetId.equals(stolenStandardTarget(stack))) {
                stack.shrink(1);
                inv.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
                return true;
            }
        }
        return false;
    }

    public static void dropCarriedStandards(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        com.bannerbound.core.citystate.CityStateWarManager.dropCarriedStandards(server, player);
        SettlementData data = SettlementData.get(server.overworld());
        for (SettlementData.StolenStandard standard : new ArrayList<>(data.stolenStandards().values())) {
            if (!player.getUUID().equals(standard.carrierPlayerId)) continue;
            UUID targetId = standard.targetSettlementId();
            if (!playerHasStandard(player, targetId)) continue;
            removeOneStolenStandard(player, targetId);
            Settlement target = data.getById(targetId);
            if (target != null) {
                if (player.level() == server.overworld()) {
                    ItemEntity item = new ItemEntity(server.overworld(), player.getX(), player.getY(), player.getZ(),
                        stolenStandardStack(target, server.overworld()));
                    prepareStolenStandardItem(item);
                    server.overworld().addFreshEntity(item);
                    markDropped(standard, player.blockPosition(), server.overworld().getGameTime());
                } else {
                    returnStandard(server, data, target, true);
                }
                data.setDirty();
            }
        }
    }

    private static void reconcileStolenStandards(MinecraftServer server, SettlementData data) {
        // Nothing in play → skip the world-wide ItemEntity sweep entirely. Without this guard the
        // full ±3e7 getEntitiesOfClass below runs every second over every loaded item entity even
        // when no standard has ever been stolen.
        if (data.stolenStandards().isEmpty()) return;
        ServerLevel level = server.overworld();
        Map<UUID, ItemEntity> dropped = new HashMap<>();
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                new AABB(-3.0e7, level.getMinBuildHeight(), -3.0e7,
                    3.0e7, level.getMaxBuildHeight(), 3.0e7))) {
            UUID target = stolenStandardTarget(item.getItem());
            if (target != null) dropped.put(target, item);
        }

        for (SettlementData.StolenStandard standard : new ArrayList<>(data.stolenStandards().values())) {
            Settlement target = data.getById(standard.targetSettlementId());
            if (target == null) {
                data.stolenStandards().remove(standard.targetSettlementId());
                data.setDirty();
                continue;
            }
            ItemEntity droppedItem = dropped.get(standard.targetSettlementId());
            ServerPlayer carrier = findCarrier(server, data, standard);
            if (carrier != null) {
                carrier.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                    45, 0, true, false, true));
                standard.droppedPos = null;
                standard.autoReturnAt = 0L;
                data.setDirty();
                continue;
            }
            if (droppedItem != null) {
                standard.carrierPlayerId = null;
                standard.carrierSettlementId = null;
                standard.droppedPos = droppedItem.blockPosition();
                if (standard.autoReturnAt <= 0L) {
                    standard.droppedAt = level.getGameTime();
                    standard.autoReturnAt = level.getGameTime() + DROPPED_STANDARD_RETURN_TICKS;
                }
                handleFriendlyReturnTouch(server, data, standard, droppedItem, target);
                if (standard.autoReturnAt > 0L && level.getGameTime() >= standard.autoReturnAt) {
                    returnStandard(server, data, target, true);
                }
                data.setDirty();
            } else {
                purgeNearbyContainerStandards(level, standard);
                returnStandard(server, data, target, true);
            }
        }
    }

    @Nullable
    private static ServerPlayer findCarrier(MinecraftServer server, SettlementData data,
                                            SettlementData.StolenStandard standard) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!playerHasStandard(player, standard.targetSettlementId())) continue;
            Settlement holderSettlement = data.getByPlayer(player.getUUID());
            Settlement target = data.getById(standard.targetSettlementId());
            if (holderSettlement == null || target == null
                    || !isActiveWarEnemy(data, holderSettlement.id(), target.id())) {
                removeOneStolenStandard(player, standard.targetSettlementId());
                return null;
            }
            assignCarrier(standard, player, holderSettlement);
            return player;
        }
        return null;
    }

    private static boolean playerHasStandard(ServerPlayer player, UUID targetId) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (targetId.equals(stolenStandardTarget(inv.getItem(i)))) return true;
        }
        return targetId.equals(stolenStandardTarget(player.containerMenu.getCarried()));
    }

    public static boolean playerCarriesStandardOf(ServerPlayer player, UUID targetId) {
        return playerHasStandard(player, targetId);
    }

    public static boolean isRallyTarget(com.bannerbound.core.entity.CitizenEntity citizen,
                                        net.minecraft.world.entity.LivingEntity target) {
        if (citizen == null || target == null || citizen.isChild()) return false;
        if (!(target instanceof ServerPlayer player)) return false;
        UUID settlementId = citizen.getSettlementId();
        if (settlementId == null) return false;
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        SettlementData data = SettlementData.get(server.overworld());
        if (!data.isRallying(settlementId)) return false;
        Settlement settlement = data.getById(settlementId);
        Settlement playerSettlement = data.getByPlayer(player.getUUID());
        if (settlement == null || playerSettlement == null) return false;
        if (!isActiveWarEnemy(data, playerSettlement.id(), settlement.id())) return false;
        if (!playerHasStandard(player, settlement.id())) return false;
        ChunkPos citizenChunk = new ChunkPos(citizen.blockPosition());
        long citizenPacked = citizenChunk.toLong();
        if (!settlement.claimedChunks().contains(citizenPacked)
                && !settlement.workingClaims().contains(citizenPacked)) {
            return false;
        }
        return isInClaimOrPursuitBand(settlement, player.blockPosition(), 32);
    }

    private static boolean isInClaimOrPursuitBand(Settlement settlement, BlockPos pos, int bandBlocks) {
        ChunkPos targetChunk = new ChunkPos(pos);
        long packed = targetChunk.toLong();
        if (settlement.claimedChunks().contains(packed) || settlement.workingClaims().contains(packed)) {
            return true;
        }
        int bandChunks = Math.max(1, (bandBlocks + 15) / 16);
        for (long claim : settlement.claimedChunks()) {
            ChunkPos cp = new ChunkPos(claim);
            if (Math.abs(cp.x - targetChunk.x) <= bandChunks
                    && Math.abs(cp.z - targetChunk.z) <= bandChunks) {
                return true;
            }
        }
        for (long claim : settlement.workingClaims()) {
            ChunkPos cp = new ChunkPos(claim);
            if (Math.abs(cp.x - targetChunk.x) <= bandChunks
                    && Math.abs(cp.z - targetChunk.z) <= bandChunks) {
                return true;
            }
        }
        return false;
    }

    private static void handleFriendlyReturnTouch(MinecraftServer server, SettlementData data,
                                                  SettlementData.StolenStandard standard,
                                                  ItemEntity item, Settlement target) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != item.level() || player.distanceToSqr(item) > 4.0) continue;
            Settlement mine = data.getByPlayer(player.getUUID());
            if (mine != null && mine.id().equals(target.id())) {
                item.discard();
                returnStandard(server, data, target, true);
                return;
            }
        }
    }

    private static void returnStandardIfOwned(MinecraftServer server, SettlementData data, UUID targetId) {
        Settlement target = data.getById(targetId);
        if (target != null) returnStandard(server, data, target, false);
    }

    private static void returnStandard(MinecraftServer server, SettlementData data,
                                       Settlement target, boolean broadcast) {
        removeAllStandardItems(server, target.id());
        data.stolenStandards().remove(target.id());
        if (!target.hasFactionBanner() && target.townHallPos() != null) {
            FactionBanner.placeFoundingBanner(server.overworld(), target, target.townHallPos());
        }
        data.setDirty();
        if (broadcast) {
            SettlementManager.broadcastToSettlement(server, target, Component.translatable(
                "bannerbound.diplomacy.standard.returned", target.factionName()).withStyle(ChatFormatting.GREEN));
        }
        broadcastWarPairState(server, target.id());
    }

    private static void purgeNearbyContainerStandards(ServerLevel level, SettlementData.StolenStandard standard) {
        BlockPos center = standard.droppedPos;
        if (center == null) return;
        BlockPos min = center.offset(-8, -8, -8);
        BlockPos max = center.offset(8, 8, 8);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.isLoaded(pos)) continue;
            if (!(level.getBlockEntity(pos) instanceof Container container)) continue;
            boolean changed = false;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                while (standard.targetSettlementId().equals(stolenStandardTarget(stack))) {
                    stack.shrink(1);
                    changed = true;
                }
                if (changed) {
                    container.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
                }
            }
            if (changed) container.setChanged();
        }
    }

    private static void removeAllStandardItems(MinecraftServer server, UUID targetId) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            while (removeOneFromInventory(player.getInventory(), targetId)) {
                player.containerMenu.broadcastChanges();
            }
        }
        ServerLevel level = server.overworld();
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                new AABB(-3.0e7, level.getMinBuildHeight(), -3.0e7,
                    3.0e7, level.getMaxBuildHeight(), 3.0e7))) {
            if (targetId.equals(stolenStandardTarget(item.getItem()))) item.discard();
        }
    }

    private static void tickCapturedTimeouts(MinecraftServer server, SettlementData data) {
        long now = server.overworld().getGameTime();
        for (SettlementData.DiplomacyRelation relation : data.diplomacyRelations()) {
            if (!relation.capturedFinal()) continue;
            if (now - relation.capturedAt < CAPTURE_TIMEOUT_TICKS) continue;
            Settlement target = data.getById(relation.capturedTarget);
            Settlement captor = data.getById(relation.capturedBy);
            if (target != null && captor != null) {
                // Winning by letting the capture time out earns the same new-war cooldown as
                // razing — otherwise a captor could just wait out the timer and immediately
                // declare on someone else, which razing (the aggressive finish) is barred from.
                data.winnerNoNewWarUntil().put(captor.id(), now + WINNER_NEW_WAR_COOLDOWN_TICKS);
                returnStandard(server, data, target, false);
                endWarWithCooldown(server, data, relation, target, captor, false);
            } else {
                relation.capturedTarget = null;
                relation.capturedBy = null;
                relation.capturedAt = 0L;
                relation.warActive = false;
            }
            data.setDirty();
        }
    }

    public static boolean razeCaptured(MinecraftServer server, Settlement actorSettlement,
                                       @Nullable UUID targetId, boolean force) {
        if (targetId == null) return false;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement target = data.getById(targetId);
        if (target == null) return false;
        SettlementData.DiplomacyRelation relation = data.existingRelation(actorSettlement.id(), target.id());
        if (relation == null || !relation.capturedFinal()) return false;
        if (!force && !actorSettlement.id().equals(relation.capturedBy)) return false;
        data.winnerNoNewWarUntil().put(actorSettlement.id(),
            server.overworld().getGameTime() + WINNER_NEW_WAR_COOLDOWN_TICKS);
        SettlementManager.broadcastToSettlement(server, actorSettlement, Component.translatable(
            "bannerbound.diplomacy.raze.attacker", target.factionName()).withStyle(ChatFormatting.GOLD));
        SettlementManager.broadcastToSettlement(server, target, Component.translatable(
            "bannerbound.diplomacy.raze.defender", actorSettlement.factionName()).withStyle(ChatFormatting.RED));
        SettlementManager.razeSettlement(server, target, data);
        cleanupSettlement(server, data, target.id());
        data.setDirty();
        broadcastAllDiplomacyState(server, data);
        return true;
    }

    public static void onSettlementDisbanded(MinecraftServer server, Settlement settlement, SettlementData data) {
        long now = server.overworld().getGameTime();
        Set<UUID> winners = new HashSet<>();
        for (SettlementData.DiplomacyRelation relation : data.diplomacyRelations()) {
            if (!relation.involves(settlement.id())) continue;
            if (settlement.id().equals(relation.capturedTarget) && relation.capturedBy != null) {
                winners.add(relation.capturedBy);
            } else if (relation.warActive) {
                UUID other = relation.other(settlement.id());
                if (other != null) winners.add(other);
            }
        }
        for (UUID winner : winners) {
            data.winnerNoNewWarUntil().put(winner, now + WINNER_NEW_WAR_COOLDOWN_TICKS);
        }
        cleanupSettlement(server, data, settlement.id());
    }

    private static void cleanupSettlement(MinecraftServer server, SettlementData data, UUID settlementId) {
        Set<UUID> affected = new HashSet<>();
        data.stolenStandards().remove(settlementId);
        data.winnerNoNewWarUntil().remove(settlementId);
        data.rallyingSettlements().remove(settlementId);
        for (SettlementData.DiplomacyRelation relation : data.diplomacyRelations()) {
            if (!relation.involves(settlementId)) continue;
            UUID other = relation.other(settlementId);
            if (other != null) affected.add(other);
            relation.warActive = false;
            relation.pendingDeclarer = null;
            relation.pendingTarget = null;
            relation.pendingTicksRemaining = 0;
            relation.capturedTarget = null;
            relation.capturedBy = null;
            relation.capturedAt = 0L;
        }
        removeAllStandardItems(server, settlementId);
        for (UUID affectedId : affected) {
            Settlement affectedSettlement = data.getById(affectedId);
            if (affectedSettlement != null) {
                PolicyEffects.syncWarMoraleNow(server, affectedSettlement);
            }
        }
        // The disbanded settlement is gone for good (re-founding mints a fresh UUID), so its
        // relations are dead weight. Drop them outright instead of leaving zombie rows that
        // accumulate in the map for the life of the world.
        data.removeRelationsInvolving(settlementId);
        data.setDirty();
    }

    public static DiplomacyStatePayload buildStatePayload(MinecraftServer server, Settlement viewer) {
        SettlementData data = SettlementData.get(server.overworld());
        long now = server.overworld().getGameTime();
        List<DiplomacyStatePayload.Row> rows = new ArrayList<>();
        for (SettlementData.DiplomacyRelation relation : data.diplomacyRelations()) {
            if (!relation.involves(viewer.id()) || !relation.discovered) continue;
            UUID otherId = relation.other(viewer.id());
            Settlement other = data.getById(otherId);
            if (other == null) continue;
            int stance = DiplomacyStatePayload.STANCE_PEACE;
            if (relation.capturedFinal()) stance = DiplomacyStatePayload.STANCE_CAPTURED;
            else if (relation.warActive) stance = DiplomacyStatePayload.STANCE_WAR;
            else if (relation.pending()) stance = DiplomacyStatePayload.STANCE_PENDING;
            int pending = relation.pending() ? seconds(relation.pendingTicksRemaining) : 0;
            int cooldown = relation.redeclareAfter > now ? seconds(relation.redeclareAfter - now) : 0;
            boolean capturedTarget = other.id().equals(relation.capturedTarget);
            boolean capturedByUs = viewer.id().equals(relation.capturedBy);
            rows.add(new DiplomacyStatePayload.Row(
                other.id().toString(),
                other.factionName(),
                stance,
                townHallDistance(viewer, other),
                directionTo(viewer, other),
                pending,
                cooldown,
                relation.peaceOfferedBy(viewer.id()),
                relation.peaceOfferedBy(other.id()),
                capturedTarget,
                capturedByUs,
                capturedTarget && capturedByUs,
                objectiveFor(data, viewer, other, relation),
                false, "", "",
                com.bannerbound.core.trade.TradeManager.canTrade(server.overworld(), viewer, other),
                com.bannerbound.core.trade.TradeData.get(server.overworld())
                    .unreadCountFor(viewer.id(), other.id())));
        }
        appendCityStateRows(server, viewer, rows);
        rows.sort(Comparator.comparing(DiplomacyStatePayload.Row::name, String.CASE_INSENSITIVE_ORDER));
        List<DiplomacyStatePayload.BarbarianRow> barbarianRows = buildBarbarianRows(server, viewer);
        Long winner = data.winnerNoNewWarUntil().get(viewer.id());
        int winnerCooldown = winner != null && winner > now ? seconds(winner - now) : 0;
        return new DiplomacyStatePayload(data.isRallying(viewer.id()), winnerCooldown, rows, barbarianRows);
    }

    /** Read-only rows for every barbarian camp this settlement has discovered: name (in the camp's
     *  colour), current stance, numeric standing, and distance/direction. No actions — barbarian
     *  diplomacy happens through envoys/parley, not the tab. */
    private static List<DiplomacyStatePayload.BarbarianRow> buildBarbarianRows(MinecraftServer server,
                                                                              Settlement viewer) {
        List<DiplomacyStatePayload.BarbarianRow> out = new ArrayList<>();
        com.bannerbound.core.barbarian.BarbarianData bd =
            com.bannerbound.core.barbarian.BarbarianData.get(server.overworld());
        for (com.bannerbound.core.barbarian.BarbarianCamp camp : bd.all()) {
            if (camp.razed || !camp.discoveredBy.contains(viewer.id())) continue;
            Integer rgb = camp.type.nameColor().getColor();
            out.add(new DiplomacyStatePayload.BarbarianRow(
                camp.name,
                rgb == null ? 0xFFFFFF : rgb,
                camp.relationToward(viewer.id()).ordinal(),
                camp.relScore.getOrDefault(viewer.id(), 0),
                distanceToPos(viewer, camp.center),
                directionToPos(viewer, camp.center)));
        }
        out.sort(Comparator.comparing(DiplomacyStatePayload.BarbarianRow::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static int townHallDistance(Settlement a, Settlement b) {
        if (a.townHallPos() == null || b.townHallPos() == null) return -1;
        double dx = a.townHallPos().getX() - b.townHallPos().getX();
        double dz = a.townHallPos().getZ() - b.townHallPos().getZ();
        return (int)Math.round(Math.sqrt(dx * dx + dz * dz));
    }

    private static String directionTo(Settlement from, Settlement to) {
        if (from.townHallPos() == null || to.townHallPos() == null) return "?";
        int dx = to.townHallPos().getX() - from.townHallPos().getX();
        int dz = to.townHallPos().getZ() - from.townHallPos().getZ();
        if (Math.abs(dx) > Math.abs(dz)) return dx >= 0 ? "E" : "W";
        if (Math.abs(dz) > 0) return dz >= 0 ? "S" : "N";
        return "Here";
    }

    /** Appends one row per city-state this settlement has discovered. Read-only in Phase 1 (no war /
     *  trade actions yet — the {@code cityState} flag suppresses the action button client-side). */
    private static void appendCityStateRows(MinecraftServer server, Settlement viewer,
                                            List<DiplomacyStatePayload.Row> rows) {
        if (!com.bannerbound.core.citystate.CityStateManager.enabled()) return;
        com.bannerbound.core.citystate.CityStateData data =
            com.bannerbound.core.citystate.CityStateData.get(server.overworld());
        long now = server.overworld().getGameTime();
        for (com.bannerbound.core.citystate.CityState cs : data.all()) {
            if (!cs.discoveredBy.contains(viewer.id())) continue;
            com.bannerbound.core.citystate.CityState.CityStateWar w = cs.warWith(viewer.id());
            int stance = DiplomacyStatePayload.STANCE_PEACE;
            int pending = 0;
            boolean captured = false;
            if (w != null) {
                if (w.capturedAt > 0) { stance = DiplomacyStatePayload.STANCE_CAPTURED; captured = true; }
                else if (w.active) stance = DiplomacyStatePayload.STANCE_WAR;
                else if (w.pendingTicks > 0) {
                    stance = DiplomacyStatePayload.STANCE_PENDING;
                    pending = seconds(w.pendingTicks);
                }
            }
            int cooldown = (w != null && w.redeclareAfter > now) ? seconds(w.redeclareAfter - now) : 0;
            rows.add(new DiplomacyStatePayload.Row(
                cs.id.toString(),
                cs.name,
                stance,
                distanceToPos(viewer, cs.center),
                directionToPos(viewer, cs.center),
                pending, cooldown, false, false, captured, captured, captured, "", true,
                String.join(",", com.bannerbound.core.citystate.CityStateEconomy.topGoods(cs, 4)),
                String.join(",", com.bannerbound.core.citystate.CityStateEconomy.demandItems(cs)),
                false, 0));
        }
    }

    private static int distanceToPos(Settlement from, net.minecraft.core.BlockPos to) {
        if (from.townHallPos() == null) return -1;
        double dx = from.townHallPos().getX() - to.getX();
        double dz = from.townHallPos().getZ() - to.getZ();
        return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
    }

    private static String directionToPos(Settlement from, net.minecraft.core.BlockPos to) {
        if (from.townHallPos() == null) return "?";
        int dx = to.getX() - from.townHallPos().getX();
        int dz = to.getZ() - from.townHallPos().getZ();
        if (Math.abs(dx) > Math.abs(dz)) return dx >= 0 ? "E" : "W";
        if (Math.abs(dz) > 0) return dz >= 0 ? "S" : "N";
        return "Here";
    }

    private static String objectiveFor(SettlementData data, Settlement viewer, Settlement other,
                                       SettlementData.DiplomacyRelation relation) {
        if (other.id().equals(relation.capturedTarget)) return "Captured standard";
        if (viewer.id().equals(relation.capturedTarget)) return "Your standard is captured";
        SettlementData.StolenStandard stolenOther = data.stolenStandards().get(other.id());
        if (stolenOther != null) return "Standard in play";
        SettlementData.StolenStandard stolenMine = data.stolenStandards().get(viewer.id());
        if (stolenMine != null && other.id().equals(stolenMine.carrierSettlementId)) return "Recover your standard";
        return "";
    }

    public static void broadcastDiplomacyState(MinecraftServer server, Settlement settlement) {
        for (UUID memberId : settlement.members()) {
            ServerPlayer player = server.getPlayerList().getPlayer(memberId);
            if (player != null) sendDiplomacyState(player);
        }
    }

    public static void sendDiplomacyState(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        if (settlement == null) return;
        PacketDistributor.sendToPlayer(player, buildStatePayload(server, settlement));
    }

    private static void broadcastWarPairState(MinecraftServer server, UUID settlementId) {
        SettlementData data = SettlementData.get(server.overworld());
        Settlement settlement = data.getById(settlementId);
        if (settlement != null) broadcastDiplomacyState(server, settlement);
        for (SettlementData.DiplomacyRelation relation : data.diplomacyRelations()) {
            if (!relation.involves(settlementId)) continue;
            UUID other = relation.other(settlementId);
            Settlement otherSettlement = data.getById(other);
            if (otherSettlement != null) broadcastDiplomacyState(server, otherSettlement);
        }
    }

    public static void broadcastAllDiplomacyState(MinecraftServer server, SettlementData data) {
        for (Settlement settlement : data.all()) {
            broadcastDiplomacyState(server, settlement);
        }
    }

    private static void broadcastObjectiveState(MinecraftServer server, SettlementData data) {
        Map<UUID, DiplomacyObjectivePayload> payloads = new HashMap<>();
        for (SettlementData.StolenStandard standard : data.stolenStandards().values()) {
            Settlement target = data.getById(standard.targetSettlementId());
            if (target == null) continue;
            BlockPos pos = standard.droppedPos;
            ServerPlayer carrier = standard.carrierPlayerId == null ? null
                : server.getPlayerList().getPlayer(standard.carrierPlayerId);
            if (carrier != null) pos = carrier.blockPosition();
            if (pos == null && target.bannerPos() != null) pos = target.bannerPos();
            if (pos == null) continue;
            String title = "Stolen Standard of " + target.factionName();
            String subtitle = "Objective at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
            DiplomacyObjectivePayload payload =
                new DiplomacyObjectivePayload(true, title, subtitle, pos, target.identityRgb());
            for (SettlementData.DiplomacyRelation relation : data.diplomacyRelations()) {
                if (relation.warActive && relation.involves(target.id())) {
                    payloads.put(relation.first(), payload);
                    payloads.put(relation.second(), payload);
                }
            }
        }
        DiplomacyObjectivePayload clear =
            new DiplomacyObjectivePayload(false, "", "", BlockPos.ZERO, 0xFFFFFF);
        for (Settlement settlement : data.all()) {
            DiplomacyObjectivePayload payload = payloads.getOrDefault(settlement.id(), clear);
            for (UUID memberId : settlement.members()) {
                ServerPlayer player = server.getPlayerList().getPlayer(memberId);
                if (player != null) PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    private static boolean allowOfflineWar(MinecraftServer server) {
        return server.overworld().getGameRules().getBoolean(BannerboundGameRules.ALLOW_OFFLINE_WAR);
    }

    private static int seconds(long ticks) {
        return (int)Math.max(0L, (ticks + 19L) / 20L);
    }

    private static void messageSettlement(MinecraftServer server, Settlement settlement,
                                          String key, Object... args) {
        SettlementManager.broadcastToSettlement(server, settlement,
            Component.translatable(key, args).withStyle(ChatFormatting.RED));
    }

    private static String actionName(int action) {
        return switch (action) {
            case DiplomacyActionPayload.DECLARE_WAR -> "declare war";
            case DiplomacyActionPayload.OFFER_PEACE -> "offer peace";
            case DiplomacyActionPayload.TOGGLE_RALLY -> "rally citizens";
            case DiplomacyActionPayload.RAZE -> "raze";
            default -> "act";
        };
    }

    private static void pingChief(MinecraftServer server, Settlement settlement, Component msg) {
        Set<UUID> targets = new HashSet<>();
        if (settlement.chiefPlayerId() != null) targets.add(settlement.chiefPlayerId());
        if (settlement.regentPlayerId() != null) targets.add(settlement.regentPlayerId());
        for (UUID target : targets) {
            ServerPlayer player = server.getPlayerList().getPlayer(target);
            if (player != null) player.sendSystemMessage(msg);
        }
    }

    public static void ensurePublicStandard(ServerLevel level, Settlement settlement) {
        if (!settlement.hasFactionBanner()) return;
        if (!isPublicStandardValid(level, settlement)) {
            BlockPos pos = settlement.bannerPos();
            if (pos != null) {
                FactionBanner.lose(level, settlement, pos, false, "");
            }
        }
    }
}
