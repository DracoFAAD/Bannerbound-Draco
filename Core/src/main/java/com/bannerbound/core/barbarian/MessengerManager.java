package com.bannerbound.core.barbarian;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.territory.ChunkClaimCost;
import com.bannerbound.core.api.settlement.CitizenGender;
import com.bannerbound.core.api.settlement.data.CitizenNameLoader;
import com.bannerbound.core.entity.BarbarianEntity;
import com.bannerbound.core.network.BarbarianParleyActionPayload;
import com.bannerbound.core.network.OpenBarterPayload;
import com.bannerbound.core.territory.InventoryItemHelper;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Drives barbarian diplomats: a camp periodically sends a messenger to a SPECIFIC discovered settlement
 * (not the whole faction). The messenger travels with the trader-style ghost/real pattern (real entity
 * within ~64 blocks of a player, dead-reckoned ghost when far, so you can meet/kill it en route), then
 * waits at the settlement's hall to be right-clicked → {@code BarbarianParleyScreen}.
 *
 * <p>Journeys are TRANSIENT (static, not persisted) — a server restart simply drops in-flight envoys
 * and the camp schedules another later. Relationship outcomes (accept/refuse/trade/kill) persist on the
 * camp record via {@link BarbarianCampManager#applyRelationDelta}.
 */
public final class MessengerManager {
    private static final double REALIZE_SQ = 64.0 * 64.0;
    private static final double GHOST_SQ = 112.0 * 112.0;
    private static final double ARRIVE = 8.0;            // within this of the hall = "arrived"
    private static final double GHOST_SPEED = 0.18;      // blocks/tick while dead-reckoned
    private static final double MOVE_SPEED = 0.7;        // nav speed multiplier while real
    private static final int TICK_INTERVAL = 10;         // journeys advance every N ticks
    private static final int DISPATCH_INTERVAL = 200;    // consider sending new messengers this often
    private static final int MAX_ACTIVE = 4;             // global concurrent messengers
    private static final long FIRST_DELAY = 2400L;       // first envoy ~2 min after discovery
    private static final long SCOUT_INTERVAL = 16000L;   // then roughly every ~13 min
    private static final long JOURNEY_MAX = 12000L;      // give up the trek after this long
    private static final long WAIT_AFTER_ARRIVE = 4800L; // envoy waits ~4 min at the hall, then leaves
    private static final long GRACE_TICKS = 48000L;      // "2 days" to fetch a deferred demand (2× 24000)

    private static final int ACCEPT_DELTA = 25;
    private static final int REFUSE_DELTA = -30;
    private static final int TRADE_DELTA = 10;
    private static final int KILLED_DELTA = -35;

    private static final class Journey {
        final UUID campId;
        final UUID settlementId;
        final BlockPos target;
        double gx, gy, gz;
        boolean ghost = true;
        UUID entityId;
        boolean arrived = false;
        boolean announced = false;
        long arriveWaitUntil = 0;
        final long deadlineTick;

        Journey(UUID campId, UUID settlementId, BlockPos target, BlockPos from, long deadline) {
            this.campId = campId;
            this.settlementId = settlementId;
            this.target = target;
            this.gx = from.getX() + 0.5;
            this.gy = from.getY();
            this.gz = from.getZ() + 0.5;
            this.deadlineTick = deadline;
        }
    }

    private static final Map<UUID, Journey> JOURNEYS = new HashMap<>();

    private MessengerManager() {}

    public static void tickAll(ServerLevel level, long time) {
        if (time % DISPATCH_INTERVAL == 0) maybeDispatch(level, time);
        if (time % TICK_INTERVAL == 0 && !JOURNEYS.isEmpty()) {
            for (UUID key : new ArrayList<>(JOURNEYS.keySet())) {
                Journey j = JOURNEYS.get(key);
                if (j != null) tickJourney(level, key, j, time);
            }
        }
    }

    // ─── Dispatch ──────────────────────────────────────────────────────────────────────────────────
    private static void maybeDispatch(ServerLevel level, long now) {
        if (JOURNEYS.size() >= MAX_ACTIVE) return;
        BarbarianData data = BarbarianData.get(level);
        SettlementData sd = SettlementData.get(level);
        for (BarbarianCamp camp : data.all()) {
            if (JOURNEYS.size() >= MAX_ACTIVE) break;
            if (camp.razed || BarbarianCampManager.hasActiveRaid(camp.id)) continue;
            if (hasJourneyFor(camp.id)) continue;
            if (tryDispatchGrace(level, sd, data, camp, now)) continue; // overdue tribute → send a collector
            if (camp.nextScoutTick == 0L) { // lazy init: first envoy a while after the camp is known
                camp.nextScoutTick = now + FIRST_DELAY;
                data.setDirty();
                continue;
            }
            if (now < camp.nextScoutTick) continue;
            Settlement target = pickTarget(level, sd, camp);
            camp.nextScoutTick = now + SCOUT_INTERVAL;
            data.setDirty();
            if (target == null) continue;
            BlockPos hall = target.hasTownHall() ? target.townHallPos() : target.bannerPos();
            if (hall == null) continue;
            JOURNEYS.put(UUID.randomUUID(),
                new Journey(camp.id, target.id(), hall, camp.center, now + JOURNEY_MAX));
        }
    }

    /** When a settlement's deferred tribute comes due and someone's online, send a collector envoy to
     *  it (the barter it opens shows the now-owed demand). Keeps the grace flag until it can actually be
     *  collected, so an offline settlement isn't silently let off. Returns true if a journey was queued. */
    private static boolean tryDispatchGrace(ServerLevel level, SettlementData sd, BarbarianData data,
                                            BarbarianCamp camp, long now) {
        for (Map.Entry<UUID, Long> e : new ArrayList<>(camp.graceUntil.entrySet())) {
            if (now < e.getValue()) continue;
            UUID sid = e.getKey();
            Settlement s = sd.getById(sid);
            if (s == null) { camp.graceUntil.remove(sid); data.setDirty(); continue; }
            boolean online = false;
            for (ServerPlayer p : level.players()) {
                if (s.members().contains(p.getUUID())) { online = true; break; }
            }
            BlockPos hall = s.hasTownHall() ? s.townHallPos() : s.bannerPos();
            if (!online || hall == null) continue; // retry next pass once they're back
            camp.graceUntil.remove(sid);
            data.setDirty();
            JOURNEYS.put(UUID.randomUUID(), new Journey(camp.id, sid, hall, camp.center, now + JOURNEY_MAX));
            return true;
        }
        return false;
    }

    /** A discovered settlement of this camp that currently has an online member, or null. */
    private static Settlement pickTarget(ServerLevel level, SettlementData sd, BarbarianCamp camp) {
        for (UUID sid : camp.discoveredBy) {
            Settlement s = sd.getById(sid);
            if (s == null) continue;
            for (ServerPlayer p : level.players()) {
                if (s.members().contains(p.getUUID())) return s;
            }
        }
        return null;
    }

    private static boolean hasJourneyFor(UUID campId) {
        for (Journey j : JOURNEYS.values()) {
            if (j.campId.equals(campId)) return true;
        }
        return false;
    }

    /** Cancel any in-flight envoy bound for a settlement that no longer exists, discarding a
     *  realized messenger so it doesn't loiter toward a deleted town hall. Called from the
     *  settlement-removed hook in {@link BarbarianCampManager#onSettlementRemoved}. */
    static void onSettlementRemoved(ServerLevel level, UUID settlementId) {
        JOURNEYS.values().removeIf(j -> {
            if (!settlementId.equals(j.settlementId)) return false;
            if (j.entityId != null) {
                Entity e = level.getEntity(j.entityId);
                if (e != null) e.discard();
            }
            return true;
        });
    }

    /**
     * Op test: immediately dispatch an envoy from {@code camp} to {@code settlement}, bypassing the
     * discovery + scout-timer gates so the whole travel → realize → arrive → parley flow can be watched
     * on demand. Marks the settlement discovered (so parley validation passes) and pushes the scout
     * timer out so the camp doesn't double-send. Returns the ghost-travel distance in blocks, or -1 if
     * the settlement has no hall or an envoy from this camp is already en route.
     */
    public static double forceDispatch(ServerLevel level, BarbarianCamp camp, Settlement settlement) {
        if (camp.razed || hasJourneyFor(camp.id)) return -1;
        BlockPos hall = settlement.hasTownHall() ? settlement.townHallPos() : settlement.bannerPos();
        if (hall == null) return -1;
        long now = level.getGameTime();
        camp.discoveredBy.add(settlement.id()); // parley re-checks the player is a member of this id
        camp.nextScoutTick = now + SCOUT_INTERVAL; // suppress an immediate organic dispatch on top of this
        BarbarianData.get(level).setDirty();
        JOURNEYS.put(UUID.randomUUID(),
            new Journey(camp.id, settlement.id(), hall, camp.center, now + JOURNEY_MAX));
        return Math.sqrt(camp.center.distSqr(hall));
    }

    // ─── Journey tick (realize near players, ghost when far) ────────────────────────────────────────
    private static void tickJourney(ServerLevel level, UUID key, Journey j, long now) {
        BarbarianCamp camp = BarbarianData.get(level).getById(j.campId);
        if (camp == null || camp.razed || now > j.deadlineTick) {
            cancel(level, key, j);
            return;
        }
        BarbarianEntity entity = resolveEntity(level, j);
        double px = entity != null ? entity.getX() : j.gx;
        double pz = entity != null ? entity.getZ() : j.gz;
        double nearestSq = nearestPlayerSq(level, px, pz);

        if (j.ghost && nearestSq <= REALIZE_SQ) {
            entity = realize(level, j, camp);
        } else if (!j.ghost && entity != null && nearestSq >= GHOST_SQ) {
            ghostify(level, j, entity);
            entity = null;
        }

        if (entity == null) {
            tickGhost(j, now);
        } else {
            tickReal(level, j, entity, camp, now);
        }

        // Envoy waited too long unanswered → it leaves (treated as ignored, no penalty).
        if (j.arrived && now > j.arriveWaitUntil) cancel(level, key, j);
    }

    private static BarbarianEntity resolveEntity(ServerLevel level, Journey j) {
        if (j.entityId == null) return null;
        Entity e = level.getEntity(j.entityId);
        if (e instanceof BarbarianEntity b && !b.isRemoved()) {
            j.gx = b.getX();
            j.gy = b.getY();
            j.gz = b.getZ();
            return b;
        }
        j.entityId = null; // entity vanished — fall back to ghost from last position
        j.ghost = true;
        return null;
    }

    private static BarbarianEntity realize(ServerLevel level, Journey j, BarbarianCamp camp) {
        int bx = (int) Math.floor(j.gx);
        int bz = (int) Math.floor(j.gz);
        if (!level.hasChunk(bx >> 4, bz >> 4)) return null; // not loaded yet — stay ghost
        int by = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        BarbarianEntity m = BannerboundCore.BARBARIAN.get().create(level);
        if (m == null) return null;
        Era era = BarbarianTech.techEra(BarbarianTech.campKnownTech(SettlementData.get(level)));
        CitizenGender gender = level.random.nextBoolean() ? CitizenGender.MALE : CitizenGender.FEMALE;
        m.initializeCitizen(null, CitizenNameLoader.randomName(level.random, era, gender), gender, era,
            camp.type.nameColor());
        m.setCustomName(Component.literal(camp.name).withStyle(camp.type.nameColor())
            .append(Component.literal(" — Envoy").withStyle(ChatFormatting.GRAY)));
        m.markSimulated();
        m.moveTo(bx + 0.5, by, bz + 0.5, level.random.nextFloat() * 360.0F, 0.0F);
        if (!level.addFreshEntity(m)) return null;
        m.markMessenger(camp.center, camp.id, j.settlementId); // strips AI; manager drives nav
        j.entityId = m.getUUID();
        j.ghost = false;
        return m;
    }

    private static void ghostify(ServerLevel level, Journey j, BarbarianEntity entity) {
        j.gx = entity.getX();
        j.gy = entity.getY();
        j.gz = entity.getZ();
        j.ghost = true;
        j.entityId = null;
        entity.discard();
    }

    private static void tickGhost(Journey j, long now) {
        double dx = (j.target.getX() + 0.5) - j.gx;
        double dz = (j.target.getZ() + 0.5) - j.gz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > ARRIVE) {
            double step = Math.min(GHOST_SPEED * TICK_INTERVAL, dist);
            j.gx += dx / dist * step;
            j.gz += dz / dist * step;
        } else if (!j.arrived) {
            markArrived(j, now);
        }
    }

    private static void tickReal(ServerLevel level, Journey j, BarbarianEntity entity, BarbarianCamp camp,
                                 long now) {
        double dx = (j.target.getX() + 0.5) - entity.getX();
        double dz = (j.target.getZ() + 0.5) - entity.getZ();
        if (dx * dx + dz * dz <= ARRIVE * ARRIVE) {
            entity.getNavigation().stop();
            if (!j.arrived) markArrived(j, now);
            if (!j.announced) { // announce once, when it's at the hall and a player is present
                announceArrival(level, camp, j.settlementId);
                j.announced = true;
            }
        } else if (entity.getNavigation().isDone()) {
            int ty = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                j.target.getX(), j.target.getZ());
            entity.getNavigation().moveTo(j.target.getX() + 0.5, ty, j.target.getZ() + 0.5, MOVE_SPEED);
        }
    }

    private static void markArrived(Journey j, long now) {
        j.arrived = true;
        j.arriveWaitUntil = now + WAIT_AFTER_ARRIVE;
    }

    private static void cancel(ServerLevel level, UUID key, Journey j) {
        if (j.entityId != null) {
            Entity e = level.getEntity(j.entityId);
            if (e != null) e.discard();
        }
        JOURNEYS.remove(key);
    }

    private static double nearestPlayerSq(ServerLevel level, double x, double z) {
        double best = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            double dx = x - p.getX(), dz = z - p.getZ();
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best;
    }

    private static Journey journeyForEntity(UUID entityUuid) {
        for (Journey j : JOURNEYS.values()) {
            if (entityUuid.equals(j.entityId)) return j;
        }
        return null;
    }

    private static UUID keyForEntity(UUID entityUuid) {
        for (Map.Entry<UUID, Journey> e : JOURNEYS.entrySet()) {
            if (entityUuid.equals(e.getValue().entityId)) return e.getKey();
        }
        return null;
    }

    private static void announceArrival(ServerLevel level, BarbarianCamp camp, UUID settlementId) {
        Settlement s = SettlementData.get(level).getById(settlementId);
        if (s == null) return;
        Component msg = Component.translatable("bannerbound.barbarian.envoy_arrived",
            Component.literal(camp.name).withStyle(camp.type.nameColor()),
            Component.literal(s.name()).withStyle(s.identityFormatting()))
            .withStyle(ChatFormatting.YELLOW);
        for (ServerPlayer p : level.players()) {
            if (s.members().contains(p.getUUID())) p.displayClientMessage(msg, false);
        }
    }

    /** Tell every online member of {@code sid} their standing with the camp shifted — camp name in its
     *  colour, the line green (improve) or red (worsen). Fired on the player's parley choice / a kill. */
    private static void broadcastRelationShift(ServerLevel level, BarbarianCamp camp, UUID sid,
                                               boolean improved) {
        Settlement s = SettlementData.get(level).getById(sid);
        if (s == null) return;
        Component msg = Component.translatable(improved
                ? "bannerbound.barbarian.relation.improved"
                : "bannerbound.barbarian.relation.worsened",
                Component.literal(camp.name).withStyle(camp.type.nameColor()))
            .withStyle(improved ? ChatFormatting.GREEN : ChatFormatting.RED);
        for (ServerPlayer p : level.players()) {
            if (s.members().contains(p.getUUID())) p.displayClientMessage(msg, false);
        }
        // Refresh the Diplomacy tab so the camp's new stance/score shows live if it's open.
        if (level.getServer() != null) {
            com.bannerbound.core.api.settlement.DiplomacyManager.broadcastDiplomacyState(level.getServer(), s);
        }
    }

    // ─── Contextual offers: scale tribute + trades to the target's wealth/strength and our standing ──
    // Computed deterministically from slow-moving settlement state so the offer shown at open matches the
    // cost charged on accept (no snapshot needed). Used by openParley, doAccept and doTrade alike.

    /** Bigger, more-landed settlements read as richer → camps press for more. Bounded 1.0×–2.0×. */
    private static double prosperityScale(Settlement s) {
        double pop = s.population();
        double land = s.claimedChunks().size();
        return Math.min(2.0, 1.0 + pop / 24.0 + land / 40.0);
    }

    /** Demand multiplier from standing: a camp that dislikes you presses harder; a friendly one eases. */
    private static double demandRelationMult(CampRelationState st) {
        return switch (st) {
            case HOSTILE -> 1.3;
            case FRIENDLY -> 0.75;
            default -> 1.0;
        };
    }

    private static List<ParleyLoader.Demand> effectiveDemands(Settlement s, BarbarianCamp camp,
                                                              ParleyLoader.Def def) {
        double scale = prosperityScale(s) * demandRelationMult(camp.relationToward(s.id()));
        List<ParleyLoader.Demand> out = new ArrayList<>(def.demands().size());
        for (ParleyLoader.Demand d : def.demands()) {
            out.add(new ParleyLoader.Demand(d.item(), Math.max(1, (int) Math.round(d.count() * scale))));
        }
        return out;
    }

    private static List<ParleyLoader.Trade> effectiveTrades(Settlement s, BarbarianCamp camp,
                                                            ParleyLoader.Def def) {
        // Standing changes the rate: friends want less for the same goods; a sour camp gouges you.
        double giveMult = switch (camp.relationToward(s.id())) {
            case FRIENDLY -> 0.7;
            case HOSTILE -> 1.25;
            default -> 1.0;
        };
        List<ParleyLoader.Trade> out = new ArrayList<>(def.trades().size());
        for (ParleyLoader.Trade t : def.trades()) {
            out.add(new ParleyLoader.Trade(t.giveItem(),
                Math.max(1, (int) Math.round(t.giveCount() * giveMult)), t.getItem(), t.getCount()));
        }
        return out;
    }

    // ─── Parley open + action (called from BarbarianEntity.mobInteract / server handler) ─────────────

    /** Builds + sends the barter screen for the messenger the player right-clicked, if it's here for them. */
    public static void openBarter(ServerPlayer sp, BarbarianEntity messenger) {
        BarbarianCamp camp = validateFor(sp, messenger);
        if (camp == null) return;
        ServerLevel level = sp.serverLevel();
        Settlement mine = SettlementData.get(level).getByPlayer(sp.getUUID());
        ParleyLoader.Def def = ParleyLoader.forType(camp.type);
        String flavor = def.demands().isEmpty() ? "minecraft:wheat" : def.demands().get(0).item();
        OpenBarterPayload payload = BarbarianBarter.open(level, camp, mine, messenger.getId(),
            def.greetingKey(), flavor);
        PacketDistributor.sendToPlayer(sp, payload);
    }

    /** Shared guard: the messenger is a live envoy and this player is a member of its target settlement. */
    private static BarbarianCamp validateFor(ServerPlayer sp, BarbarianEntity messenger) {
        if (messenger == null || !messenger.isMessenger()) return null;
        ServerLevel level = sp.serverLevel();
        BarbarianCamp camp = messenger.campId() == null ? null
            : BarbarianData.get(level).getById(messenger.campId());
        if (camp == null) return null;
        Settlement mine = SettlementData.get(level).getByPlayer(sp.getUUID());
        if (mine == null || !mine.id().equals(messenger.messengerSettlementId())) {
            sp.displayClientMessage(Component.translatable("bannerbound.barbarian.parley.not_for_you")
                .withStyle(ChatFormatting.GRAY), true);
            return null;
        }
        return camp;
    }

    /** C→S: the open screen polls for a fresh storage/goods snapshot so the offer greys out live. */
    public static void handleStorageRequest(ServerPlayer sp, int messengerEntityId) {
        ServerLevel level = sp.serverLevel();
        if (!(level.getEntity(messengerEntityId) instanceof BarbarianEntity m)) return;
        BarbarianCamp camp = validateFor(sp, m);
        if (camp == null) return;
        Settlement mine = SettlementData.get(level).getByPlayer(sp.getUUID());
        PacketDistributor.sendToPlayer(sp, new com.bannerbound.core.network.BarterStoragePayload(
            messengerEntityId, BarbarianBarter.liveStorage(level, mine),
            BarbarianBarter.liveGoods(camp, mine)));
    }

    /** C→S: the player's barter move (propose / decline / defer). */
    public static void handleBarter(ServerPlayer sp, com.bannerbound.core.network.BarterActionPayload payload) {
        ServerLevel level = sp.serverLevel();
        if (!(level.getEntity(payload.messengerEntityId()) instanceof BarbarianEntity m)) return;
        BarbarianCamp camp = validateFor(sp, m);
        if (camp == null) return;
        BarbarianData data = BarbarianData.get(level);
        Settlement mine = SettlementData.get(level).getByPlayer(sp.getUUID());
        switch (payload.action()) {
            case com.bannerbound.core.network.BarterActionPayload.PROPOSE ->
                doPropose(level, sp, data, camp, mine, m, payload);
            case com.bannerbound.core.network.BarterActionPayload.DECLINE ->
                doDecline(level, sp, data, camp, mine, m);
            case com.bannerbound.core.network.BarterActionPayload.DEFER ->
                doDefer(level, sp, data, camp, mine, m);
            default -> { }
        }
    }

    private static void doPropose(ServerLevel level, ServerPlayer sp, BarbarianData data, BarbarianCamp camp,
                                  Settlement mine, BarbarianEntity m,
                                  com.bannerbound.core.network.BarterActionPayload payload) {
        BarbarianBarter.Result r = BarbarianBarter.propose(level, sp, camp, mine,
            payload.youGive(), payload.youGet());
        switch (r.outcome) {
            case ACCEPTED -> {
                if (camp.type.relationCeiling() == CampRelationState.HOSTILE) {
                    BarbarianCampManager.buyRaidCooldown(level, camp);
                    data.setDirty();
                } else {
                    BarbarianCampManager.applyRelationDelta(data, camp, mine.id(), r.relationDelta);
                    broadcastRelationShift(level, camp, mine.id(), true);
                }
                camp.clearGrace(mine.id());
                sp.displayClientMessage(Component.translatable("bannerbound.barbarian.barter.deal")
                    .withStyle(ChatFormatting.GREEN), true);
                leave(level, m);
            }
            case REJECTED -> {
                CampRelationState st = BarbarianCampManager.applyRelationDelta(data, camp, mine.id(),
                    r.relationDelta);
                broadcastRelationShift(level, camp, mine.id(), false);
                if (st == CampRelationState.HOSTILE) BarbarianCampManager.scheduleRaidSoon(level, camp);
                sp.displayClientMessage(Component.translatable("bannerbound.barbarian.barter.rejected")
                    .withStyle(ChatFormatting.RED), true);
                leave(level, m);
            }
            case CANT_PAY -> sp.displayClientMessage(Component.translatable(
                "bannerbound.barbarian.parley.cant_pay").withStyle(ChatFormatting.RED), true);
            case INVALID -> { } // client/world desync — ignore
        }
    }

    private static void doDecline(ServerLevel level, ServerPlayer sp, BarbarianData data, BarbarianCamp camp,
                                  Settlement mine, BarbarianEntity m) {
        CampRelationState st = BarbarianCampManager.applyRelationDelta(data, camp, mine.id(),
            BarbarianBarter.declineDelta(camp));
        broadcastRelationShift(level, camp, mine.id(), false);
        if (st == CampRelationState.HOSTILE) BarbarianCampManager.scheduleRaidSoon(level, camp);
        leave(level, m);
    }

    private static void doDefer(ServerLevel level, ServerPlayer sp, BarbarianData data, BarbarianCamp camp,
                                Settlement mine, BarbarianEntity m) {
        if (!BarbarianBarter.canDefer(camp, mine)) { // hostile camps want it now
            sp.displayClientMessage(Component.translatable("bannerbound.barbarian.barter.grace_refused")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        camp.setGrace(mine.id(), level.getGameTime() + GRACE_TICKS);
        data.setDirty();
        sp.displayClientMessage(Component.translatable("bannerbound.barbarian.barter.grace_granted",
            Component.literal(camp.name).withStyle(camp.type.nameColor())).withStyle(ChatFormatting.YELLOW),
            false);
        leave(level, m);
    }

    /** Resolves the player's parley choice server-side (validated). */
    public static void handleAction(ServerPlayer sp, BarbarianParleyActionPayload payload) {
        ServerLevel level = sp.serverLevel();
        Entity e = level.getEntity(payload.messengerEntityId());
        if (!(e instanceof BarbarianEntity m) || !m.isMessenger()) return;
        BarbarianData data = BarbarianData.get(level);
        BarbarianCamp camp = m.campId() == null ? null : data.getById(m.campId());
        if (camp == null) return;
        UUID sid = m.messengerSettlementId();
        Settlement mine = SettlementData.get(level).getByPlayer(sp.getUUID());
        if (mine == null || !mine.id().equals(sid)) return;
        ParleyLoader.Def def = ParleyLoader.forType(camp.type);

        switch (payload.action()) {
            case BarbarianParleyActionPayload.ACCEPT -> doAccept(level, sp, data, camp, sid, def, m);
            case BarbarianParleyActionPayload.REFUSE -> doRefuse(level, sp, data, camp, sid, m);
            case BarbarianParleyActionPayload.TRADE -> doTrade(level, sp, data, camp, sid, def,
                payload.tradeIndex());
            default -> { }
        }
    }

    private static void doAccept(ServerLevel level, ServerPlayer sp, BarbarianData data, BarbarianCamp camp,
                                 UUID sid, ParleyLoader.Def def, BarbarianEntity m) {
        Settlement s = SettlementData.get(level).getById(sid);
        List<ChunkClaimCost.ItemCost> cost =
            costs(s == null ? def.demands() : effectiveDemands(s, camp, def));
        if (!cost.isEmpty()) {
            if (!InventoryItemHelper.hasAll(sp, cost)) {
                sp.displayClientMessage(Component.translatable("bannerbound.barbarian.parley.cant_pay")
                    .withStyle(ChatFormatting.RED), true);
                return;
            }
            InventoryItemHelper.consume(sp, cost);
        }
        if (camp.type.relationCeiling() == CampRelationState.HOSTILE) {
            // Marauders pocket the tribute and merely delay the next raid — no real peace.
            BarbarianCampManager.buyRaidCooldown(level, camp);
            data.setDirty();
            sp.displayClientMessage(Component.translatable("bannerbound.barbarian.parley.tribute_marauder")
                .withStyle(ChatFormatting.GRAY), false);
        } else {
            CampRelationState st = BarbarianCampManager.applyRelationDelta(data, camp, sid, ACCEPT_DELTA);
            BarbarianCampManager.buyRaidCooldown(level, camp);
            sp.displayClientMessage(Component.translatable("bannerbound.barbarian.parley.tribute_ok",
                stateName(st)).withStyle(ChatFormatting.GREEN), true); // actionbar transaction note
            broadcastRelationShift(level, camp, sid, true); // chat: "Relations with X improve."
        }
        leave(level, m);
    }

    private static void doRefuse(ServerLevel level, ServerPlayer sp, BarbarianData data, BarbarianCamp camp,
                                 UUID sid, BarbarianEntity m) {
        CampRelationState st = BarbarianCampManager.applyRelationDelta(data, camp, sid, REFUSE_DELTA);
        if (st == CampRelationState.HOSTILE) BarbarianCampManager.scheduleRaidSoon(level, camp);
        sp.displayClientMessage(Component.translatable("bannerbound.barbarian.parley.refused")
            .withStyle(ChatFormatting.RED), true); // actionbar transaction note
        broadcastRelationShift(level, camp, sid, false); // chat: "Relations with X worsen."
        leave(level, m);
    }

    private static void doTrade(ServerLevel level, ServerPlayer sp, BarbarianData data, BarbarianCamp camp,
                                UUID sid, ParleyLoader.Def def, int index) {
        Settlement s = SettlementData.get(level).getById(sid);
        List<ParleyLoader.Trade> offers = s == null ? def.trades() : effectiveTrades(s, camp, def);
        if (index < 0 || index >= offers.size()) return;
        ParleyLoader.Trade t = offers.get(index);
        Item give = itemOf(t.giveItem());
        Item get = itemOf(t.getItem());
        if (give == Items.AIR || get == Items.AIR) return;
        List<ChunkClaimCost.ItemCost> cost = List.of(new ChunkClaimCost.ItemCost(give, t.giveCount()));
        if (!InventoryItemHelper.hasAll(sp, cost)) {
            sp.displayClientMessage(Component.translatable("bannerbound.barbarian.parley.cant_pay")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        InventoryItemHelper.consume(sp, cost);
        ItemStack reward = new ItemStack(get, t.getCount());
        if (!sp.addItem(reward)) sp.drop(reward, false);
        CampRelationState before = camp.relationToward(sid);
        CampRelationState after = BarbarianCampManager.applyRelationDelta(data, camp, sid, TRADE_DELTA);
        sp.displayClientMessage(Component.translatable("bannerbound.barbarian.parley.traded")
            .withStyle(ChatFormatting.GREEN), true);
        // Broadcast the relation gain only when it actually crosses a tier — repeat trades shouldn't spam.
        if (after != before) broadcastRelationShift(level, camp, sid, true);
        // Messenger stays so the player can keep trading; it leaves on its own timer.
    }

    /** A messenger killed mid-journey or at the hall — a hard refusal (relations drop, raid sooner). */
    public static void onMessengerKilled(ServerLevel level, BarbarianEntity m) {
        UUID key = keyForEntity(m.getUUID());
        if (key == null) return;
        Journey j = JOURNEYS.remove(key);
        if (j == null) return;
        BarbarianCamp camp = BarbarianData.get(level).getById(j.campId);
        if (camp == null) return;
        BarbarianData data = BarbarianData.get(level);
        CampRelationState st = BarbarianCampManager.applyRelationDelta(data, camp, j.settlementId, KILLED_DELTA);
        broadcastRelationShift(level, camp, j.settlementId, false); // chat: "Relations with X worsen."
        if (st == CampRelationState.HOSTILE) BarbarianCampManager.scheduleRaidSoon(level, camp);
    }

    private static void leave(ServerLevel level, BarbarianEntity m) {
        UUID key = keyForEntity(m.getUUID());
        if (key != null) {
            Journey j = JOURNEYS.remove(key);
            if (j != null && j.entityId != null) {
                Entity e = level.getEntity(j.entityId);
                if (e != null) e.discard();
                return;
            }
        }
        m.discard();
    }

    private static List<ChunkClaimCost.ItemCost> costs(List<ParleyLoader.Demand> demands) {
        List<ChunkClaimCost.ItemCost> out = new ArrayList<>();
        for (ParleyLoader.Demand d : demands) {
            Item it = itemOf(d.item());
            if (it != Items.AIR) out.add(new ChunkClaimCost.ItemCost(it, d.count()));
        }
        return out;
    }

    private static Item itemOf(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
    }

    private static Component stateName(CampRelationState st) {
        return Component.translatable("bannerbound.barbarian.relation." + st.name().toLowerCase(java.util.Locale.ROOT));
    }
}
