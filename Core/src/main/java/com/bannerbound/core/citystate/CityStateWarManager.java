package com.bannerbound.core.citystate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.CitizenGender;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.DiplomacyManager;
import com.bannerbound.core.api.settlement.FactionBanner;
import com.bannerbound.core.api.settlement.data.CitizenNameLoader;
import com.bannerbound.core.barbarian.BarbarianCapability;
import com.bannerbound.core.barbarian.BarbarianTech;
import com.bannerbound.core.citystate.CityState.CityStateWar;
import com.bannerbound.core.entity.MercenaryEntity;
import com.bannerbound.core.network.DiplomacyActionPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * War with AI city-states (CITY_STATES plan §2). Players declare; city-states only DEFEND (no raids).
 * A city-state at war fields {@link MercenaryEntity} defenders — much slower to respawn than barbarians,
 * so sustained pressure drains them and lets you break the banner. Breaking the banner captures the
 * city-state; the victor then chooses its fate (raze / vassal / annex — annex locked until a free
 * settlement slot exists, far-future Feudalism).
 *
 * <p>Mirrors the static-manager shape of {@code BarbarianCampManager}; driven from
 * {@code ResearchEvents.onServerTick}.
 */
@ApiStatus.Internal
public final class CityStateWarManager {
    private static final int WAR_TICK_INTERVAL = 20;          // pending/garrison/timeout cadence
    private static final int WAR_WARNING_TICKS = 20 * 60;     // 60s declaration countdown
    private static final long REDECLARE_COOLDOWN = 20L * 60 * 30;  // 30 min before re-declaring
    private static final long CAPTURE_TIMEOUT = 20L * 60 * 30;     // captured → auto-resolved (raze) if ignored
    private static final long MERC_RESPAWN_TICKS = 20L * 120;      // one defender recruited every 2 min
    private static final double GARRISON_DIST_SQ = 64.0 * 64.0;    // spawn defenders when a player is this near
    private static final long DROPPED_RETURN_TICKS = 20L * 60 * 5; // a dropped standard returns after 5 min

    /** Transient live-mercenary tracking per city-state (never saved; respawns on approach). */
    private static final Map<UUID, Garrison> GARRISONS = new HashMap<>();

    private static final class Garrison {
        final Set<UUID> ids = new HashSet<>();
        int killed;            // attrition — defenders lost; recruited back one per MERC_RESPAWN_TICKS
        long lastRecruitTick;
    }

    private CityStateWarManager() {
    }

    // ─── Government-gated entry (parallels DiplomacyManager.routeAction for city-state targets) ─────

    public static void routeAction(ServerPlayer actor, int action, CityState cs) {
        if (!CityStateManager.enabled()) return;
        MinecraftServer server = actor.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(actor.getUUID());
        if (s == null || !s.members().contains(actor.getUUID())) return;

        switch (s.governmentType()) {
            case NONE -> performAction(server, s, action, cs, false);
            case CHIEFDOM -> {
                if (s.canActAsChief(actor.getUUID())) {
                    performAction(server, s, action, cs, false);
                } else {
                    actor.displayClientMessage(Component.translatable("bannerbound.suggest.sent")
                        .withStyle(ChatFormatting.GRAY), true);
                }
            }
            case COUNCIL -> {
                // Declare/peace go to a council vote; capture resolution acts directly (war's already won).
                com.bannerbound.core.api.settlement.ChatVoteManager.Kind kind = voteKind(action);
                if (kind != null) {
                    com.bannerbound.core.api.settlement.ChatVoteManager.start(
                        server, s, kind, actor, cs.id, cs.name, true);
                } else {
                    performAction(server, s, action, cs, false);
                }
            }
        }
    }

    private static com.bannerbound.core.api.settlement.ChatVoteManager.Kind voteKind(int action) {
        return switch (action) {
            case DiplomacyActionPayload.DECLARE_WAR ->
                com.bannerbound.core.api.settlement.ChatVoteManager.Kind.DECLARE_WAR;
            case DiplomacyActionPayload.OFFER_PEACE ->
                com.bannerbound.core.api.settlement.ChatVoteManager.Kind.OFFER_PEACE;
            default -> null; // RAZE/VASSAL/ANNEX resolve directly
        };
    }

    /** Council vote resolution: {@code vote.targetCitizen} is the city-state id. */
    public static void performCouncilAction(MinecraftServer server, Settlement s,
            com.bannerbound.core.api.settlement.ChatVoteManager.ChatVote vote) {
        CityState cs = CityStateData.get(server.overworld()).getById(vote.targetCitizen);
        if (cs == null) return;
        int action = switch (vote.kind) {
            case DECLARE_WAR -> DiplomacyActionPayload.DECLARE_WAR;
            case OFFER_PEACE -> DiplomacyActionPayload.OFFER_PEACE;
            default -> -1;
        };
        if (action >= 0) performAction(server, s, action, cs, true);
    }

    private static void performAction(MinecraftServer server, Settlement s, int action, CityState cs,
                                      boolean force) {
        switch (action) {
            case DiplomacyActionPayload.DECLARE_WAR -> declareWar(server, s, cs);
            case DiplomacyActionPayload.OFFER_PEACE -> offerPeace(server, s, cs);
            case DiplomacyActionPayload.RAZE -> resolveCapture(server, s, cs, DiplomacyActionPayload.RAZE);
            case DiplomacyActionPayload.VASSAL -> resolveCapture(server, s, cs, DiplomacyActionPayload.VASSAL);
            case DiplomacyActionPayload.ANNEX -> resolveCapture(server, s, cs, DiplomacyActionPayload.ANNEX);
            default -> { }
        }
    }

    // ─── Declare / peace ───────────────────────────────────────────────────────────────────────

    public static boolean declareWar(MinecraftServer server, Settlement s, CityState cs) {
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        if (!cs.discoveredBy.contains(s.id())) return false;
        CityStateWar w = cs.warWith(s.id());
        if (w != null && (w.active || w.pendingTicks > 0 || w.capturedAt > 0)) return false;
        if (w != null && w.redeclareAfter > now) return false;
        w = cs.getOrCreateWar(s.id());
        w.pendingTicks = WAR_WARNING_TICKS;
        w.active = false;
        w.capturedAt = 0;
        CityStateData.get(level).setDirty();
        announce(server, s, Component.translatable("bannerbound.citystate.war.declared",
            cityName(cs), s.factionName()).withStyle(ChatFormatting.RED));
        DiplomacyManager.broadcastDiplomacyState(server, s);
        return true;
    }

    public static boolean offerPeace(MinecraftServer server, Settlement s, CityState cs) {
        CityStateWar w = cs.warWith(s.id());
        if (w == null || (!w.active && w.pendingTicks <= 0) || w.capturedAt > 0) return false;
        // City-states are defenders — they accept peace immediately.
        endWar(server, s, cs, w);
        announce(server, s, Component.translatable("bannerbound.citystate.war.peace",
            cityName(cs)).withStyle(ChatFormatting.GREEN));
        return true;
    }

    private static void endWar(MinecraftServer server, Settlement s, CityState cs, CityStateWar w) {
        long now = server.overworld().getGameTime();
        w.active = false;
        w.pendingTicks = 0;
        w.peaceOffered = false;
        w.capturedAt = 0;
        w.redeclareAfter = now + REDECLARE_COOLDOWN;
        if (cs.standardInPlay && !cs.isFrozen()) returnStandard(server, cs); // peace recovers a carried standard
        disbandGarrisonIfNoWars(cs);
        CityStateData.get(server.overworld()).setDirty();
        DiplomacyManager.broadcastDiplomacyState(server, s);
    }

    /** True if this settlement is in a live war (pending declaration, active, or holding a
     *  capture) with ANY city-state. Used to block disband while a settlement is at war. */
    public static boolean isSettlementAtWar(ServerLevel level, UUID settlementId) {
        for (CityState cs : CityStateData.get(level).all()) {
            CityStateWar w = cs.warWith(settlementId);
            if (w != null && (w.active || w.pendingTicks > 0 || w.capturedAt > 0)) return true;
        }
        return false;
    }

    /** Called when a player settlement is removed (disband / collapse / conquest). Drops the
     *  settlement's war + relationship + discovery records from every city-state so they don't
     *  linger as orphans referencing a deleted settlement, and stands down any garrison that was
     *  only fighting this settlement. */
    public static void onSettlementRemoved(ServerLevel level, UUID settlementId) {
        boolean changed = false;
        for (CityState cs : CityStateData.get(level).all()) {
            if (cs.wars.remove(settlementId) != null) changed = true;
            if (cs.relScore.remove(settlementId) != null) changed = true;
            if (cs.discoveredBy.remove(settlementId)) changed = true;
            disbandGarrisonIfNoWars(cs);
        }
        if (changed) CityStateData.get(level).setDirty();
    }

    // ─── Capture resolution (raze / vassal / annex) ───────────────────────────────────────────────

    public static boolean resolveCapture(MinecraftServer server, Settlement s, CityState cs, int action) {
        CityStateWar w = cs.warWith(s.id());
        if (w == null || w.capturedAt <= 0) return false;
        ServerLevel level = server.overworld();
        CityStateData data = CityStateData.get(level);
        switch (action) {
            case DiplomacyActionPayload.RAZE -> {
                discardGarrison(level, cs);
                removeStandardItems(level, cs.id);
                java.util.Set<Long> area = data.razeVillage(cs); // permanent ruin memory + remove record
                com.bannerbound.core.ruin.RuinManager.queue(level, area); // buildings crumble + villagers go
                announce(server, s, Component.translatable("bannerbound.citystate.razed",
                    cityName(cs)).withStyle(ChatFormatting.RED));
            }
            case DiplomacyActionPayload.VASSAL -> {
                cs.vassalOf = s.id();
                cs.wars.clear();              // peace with everyone; it's now your protectorate
                cs.bannerStamped = false;     // its banner is re-raised next time you're near
                cs.relScore.put(s.id(), 100);
                disbandGarrisonIfNoWars(cs);
                data.setDirty();
                announce(server, s, Component.translatable("bannerbound.citystate.vassal",
                    cityName(cs), s.factionName()).withStyle(ChatFormatting.GOLD));
            }
            case DiplomacyActionPayload.ANNEX -> {
                // Annex needs a free settlement slot (Feudalism, far-future). Locked for now.
                return false;
            }
            default -> { return false; }
        }
        DiplomacyManager.broadcastDiplomacyState(server, s);
        return true;
    }

    // ─── Banner capture (break the standard during an active war) ──────────────────────────────────

    /** A block at a city-state's banner position was broken during an active war: the standard becomes
     *  a carryable item — carry it to YOUR town hall to capture (it is NOT captured by the break). */
    public static boolean onBannerBroken(ServerLevel level, ServerPlayer breaker, BlockPos pos) {
        CityStateData data = CityStateData.get(level);
        CityState cs = data.bannerAt(pos);
        if (cs == null || cs.standardInPlay || cs.capturedBySettlement() != null) return false;
        Settlement s = SettlementData.get(level).getByPlayer(breaker.getUUID());
        if (s == null || !cs.isActiveEnemy(s.id())) return false;
        cs.bannerRazed = true;
        cs.bannerStamped = false;
        cs.standardInPlay = true;
        ItemStack stack = standardStack(cs);
        if (breaker.getInventory().add(stack)) {
            cs.standardCarrier = breaker.getUUID();
            cs.standardDroppedPos = null;
            cs.standardAutoReturnAt = 0;
        } else {
            spawnStandardItem(level, cs, pos, stack);
            cs.standardCarrier = null;
            cs.standardDroppedPos = pos.immutable();
            cs.standardDroppedAt = level.getGameTime();
            cs.standardAutoReturnAt = level.getGameTime() + DROPPED_RETURN_TICKS;
        }
        data.setDirty();
        announce(level.getServer(), s, Component.translatable("bannerbound.citystate.standard_taken",
            cityName(cs)).withStyle(ChatFormatting.GOLD));
        DiplomacyManager.broadcastDiplomacyState(level.getServer(), s);
        return true;
    }

    // ─── Carryable standard (reuses the settlement stolen-standard item plumbing) ──────────────────

    private static ItemStack standardStack(CityState cs) {
        DyeColor dye = DyeColor.byId((int) Math.floorMod(cs.languageSeed, 16));
        ItemStack stack = new ItemStack(BannerBlock.byColor(dye).asItem());
        stack.set(BannerboundCore.STOLEN_STANDARD_SETTLEMENT.get(), cs.id.toString());
        stack.set(BannerboundCore.STOLEN_STANDARD_NAME.get(), cs.name);
        stack.set(DataComponents.CUSTOM_NAME,
            Component.translatable("bannerbound.item.stolen_standard", cs.name));
        return stack;
    }

    private static void spawnStandardItem(ServerLevel level, CityState cs, BlockPos pos, ItemStack stack) {
        ItemEntity item = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        DiplomacyManager.prepareStolenStandardItem(item);
        level.addFreshEntity(item);
    }

    /** Delegated from {@code DiplomacyManager.canPickupStolenStandard} for a city-state standard. */
    public static boolean canPickup(MinecraftServer server, ServerPlayer player, ItemEntity item, java.util.UUID csId) {
        ServerLevel level = server.overworld();
        CityState cs = CityStateData.get(level).getById(csId);
        if (cs == null || !cs.standardInPlay) { item.discard(); return false; }
        Settlement mine = SettlementData.get(level).getByPlayer(player.getUUID());
        if (mine != null && cs.isActiveEnemy(mine.id())) return true;
        player.displayClientMessage(Component.translatable(
            "bannerbound.diplomacy.standard.pickup_blocked", cs.name).withStyle(ChatFormatting.RED), true);
        return false;
    }

    /** Delegated from {@code DiplomacyManager.onStolenStandardPickedUp}. */
    public static void onPickedUp(MinecraftServer server, ServerPlayer player, java.util.UUID csId) {
        ServerLevel level = server.overworld();
        CityStateData data = CityStateData.get(level);
        CityState cs = data.getById(csId);
        if (cs == null) return;
        Settlement mine = SettlementData.get(level).getByPlayer(player.getUUID());
        if (mine == null || !cs.isActiveEnemy(mine.id())) {
            removeStandardFromInventory(player, csId);
            returnStandard(server, cs);
            return;
        }
        cs.standardCarrier = player.getUUID();
        cs.standardDroppedPos = null;
        cs.standardAutoReturnAt = 0;
        data.setDirty();
    }

    /** Delegated from {@code DiplomacyManager.onStolenStandardDropped}. */
    public static void onDropped(MinecraftServer server, ServerPlayer player, ItemEntity item, java.util.UUID csId) {
        ServerLevel level = server.overworld();
        CityState cs = CityStateData.get(level).getById(csId);
        if (cs == null || !cs.standardInPlay) { item.discard(); return; }
        if (player.level() != level) { item.discard(); returnStandard(server, cs); return; }
        cs.standardCarrier = null;
        cs.standardDroppedPos = item.blockPosition();
        cs.standardDroppedAt = level.getGameTime();
        cs.standardAutoReturnAt = level.getGameTime() + DROPPED_RETURN_TICKS;
        CityStateData.get(level).setDirty();
    }

    /** Right-clicked own town hall holding a city-state standard → capture. Returns true if handled. */
    public static boolean tryScore(MinecraftServer server, ServerPlayer player, BlockPos clickedPos) {
        ServerLevel level = server.overworld();
        Settlement scorer = SettlementData.get(level).getByPlayer(player.getUUID());
        if (scorer == null || scorer.townHallPos() == null || !scorer.townHallPos().equals(clickedPos)) {
            return false;
        }
        ItemStack held = heldStandard(player);
        if (held.isEmpty()) return false;
        java.util.UUID csId = DiplomacyManager.stolenStandardTarget(held);
        CityStateData data = CityStateData.get(level);
        CityState cs = csId == null ? null : data.getById(csId);
        if (cs == null) return false; // not a city-state standard — let settlement handler try
        CityStateWar w = cs.warWith(scorer.id());
        if (w == null || !w.active) {
            player.sendSystemMessage(Component.translatable("bannerbound.diplomacy.standard.not_enemy")
                .withStyle(ChatFormatting.RED));
            return true;
        }
        if (!FactionBanner.requireRaised(level, player, scorer)) return true;
        removeStandardFromInventory(player, csId);
        removeStandardItems(level, csId);
        w.active = false;
        w.capturedAt = level.getGameTime();
        cs.standardInPlay = false;
        cs.standardCarrier = null;
        discardGarrison(level, cs);
        data.setDirty();
        announce(server, scorer, Component.translatable("bannerbound.citystate.captured",
            cityName(cs)).withStyle(ChatFormatting.GOLD));
        DiplomacyManager.broadcastDiplomacyState(server, scorer);
        return true;
    }

    /** Delegated from {@code DiplomacyManager.dropCarriedStandards} (logout/death/dimension). */
    public static void dropCarriedStandards(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = server.overworld();
        CityStateData data = CityStateData.get(level);
        for (CityState cs : data.all()) {
            if (!player.getUUID().equals(cs.standardCarrier)) continue;
            if (!playerHasStandard(player, cs.id)) continue;
            removeStandardFromInventory(player, cs.id);
            if (player.level() == level) {
                spawnStandardItem(level, cs, player.blockPosition(), standardStack(cs));
                cs.standardCarrier = null;
                cs.standardDroppedPos = player.blockPosition();
                cs.standardDroppedAt = level.getGameTime();
                cs.standardAutoReturnAt = level.getGameTime() + DROPPED_RETURN_TICKS;
                data.setDirty();
            } else {
                returnStandard(server, cs);
            }
        }
    }

    /** Per-tick sweep: keep the carrier slowed, auto-return a long-dropped or lost standard. */
    private static void reconcileStandards(ServerLevel level) {
        CityStateData data = CityStateData.get(level);
        MinecraftServer server = level.getServer();
        for (CityState cs : data.all()) {
            if (!cs.standardInPlay) continue;
            ServerPlayer carrier = cs.standardCarrier == null ? null
                : server.getPlayerList().getPlayer(cs.standardCarrier);
            if (carrier != null && playerHasStandard(carrier, cs.id)) {
                carrier.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 45, 0, true, false, true));
                continue;
            }
            // Not on its recorded carrier — is it lying in the world? The scan only sees loaded
            // entities, so a standard resting in an unloaded chunk would read as "lost", the
            // banner would re-raise, and breaking it again would mint a second standard. Defer
            // the whole check until the recorded drop chunk is actually loaded.
            if (cs.standardDroppedPos != null) {
                net.minecraft.world.level.ChunkPos dropChunk =
                    new net.minecraft.world.level.ChunkPos(cs.standardDroppedPos);
                if (!level.hasChunk(dropChunk.x, dropChunk.z)) continue;
            }
            boolean onGround = false;
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(-3.0e7, level.getMinBuildHeight(), -3.0e7,
                        3.0e7, level.getMaxBuildHeight(), 3.0e7))) {
                if (cs.id.equals(DiplomacyManager.stolenStandardTarget(item.getItem()))) { onGround = true; break; }
            }
            if (!onGround) { returnStandard(server, cs); continue; }
            if (cs.standardAutoReturnAt > 0 && level.getGameTime() >= cs.standardAutoReturnAt) {
                returnStandard(server, cs);
            }
        }
    }

    /** The standard is lost/recovered: clear it, wipe stray items, and let the banner re-raise. */
    public static void returnStandard(MinecraftServer server, CityState cs) {
        if (cs == null) return;
        ServerLevel level = server.overworld();
        removeStandardItems(level, cs.id);
        cs.standardInPlay = false;
        cs.standardCarrier = null;
        cs.standardDroppedPos = null;
        cs.standardAutoReturnAt = 0;
        cs.bannerStamped = false; // re-raised on the next realize pass
        cs.bannerRazed = false;
        CityStateData.get(level).setDirty();
        net.minecraft.network.chat.Component msg = Component.translatable(
            "bannerbound.citystate.standard_returned", cityName(cs)).withStyle(ChatFormatting.GREEN);
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().closerThan(cs.center, 200.0)) p.displayClientMessage(msg, false);
        }
    }

    private static ItemStack heldStandard(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (DiplomacyManager.isStolenStandard(main)) return main;
        ItemStack off = player.getOffhandItem();
        return DiplomacyManager.isStolenStandard(off) ? off : ItemStack.EMPTY;
    }

    private static boolean playerHasStandard(ServerPlayer player, java.util.UUID csId) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (csId.equals(DiplomacyManager.stolenStandardTarget(inv.getItem(i)))) return true;
        }
        return csId.equals(DiplomacyManager.stolenStandardTarget(player.containerMenu.getCarried()));
    }

    private static void removeStandardFromInventory(ServerPlayer player, java.util.UUID csId) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (csId.equals(DiplomacyManager.stolenStandardTarget(stack))) {
                stack.shrink(1);
                inv.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
            }
        }
        ItemStack carried = player.containerMenu.getCarried();
        if (csId.equals(DiplomacyManager.stolenStandardTarget(carried))) {
            carried.shrink(1);
            player.containerMenu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
        }
        player.containerMenu.broadcastChanges();
    }

    private static void removeStandardItems(ServerLevel level, java.util.UUID csId) {
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                new AABB(-3.0e7, level.getMinBuildHeight(), -3.0e7,
                    3.0e7, level.getMaxBuildHeight(), 3.0e7))) {
            if (csId.equals(DiplomacyManager.stolenStandardTarget(item.getItem()))) item.discard();
        }
    }

    // ─── Tick: countdown, garrisons, capture timeout ──────────────────────────────────────────────

    public static void tickAll(MinecraftServer server) {
        if (!CityStateManager.enabled()) return;
        ServerLevel level = server.overworld();
        if (level.getGameTime() % WAR_TICK_INTERVAL != 0) return;
        CityStateData data = CityStateData.get(level);
        if (data.all().isEmpty()) return;
        long now = level.getGameTime();
        for (CityState cs : data.all()) {
            boolean anyActive = false;
            for (Map.Entry<UUID, CityStateWar> e : new HashMap<>(cs.wars).entrySet()) {
                CityStateWar w = e.getValue();
                Settlement s = SettlementData.get(level).getById(e.getKey());
                if (w.pendingTicks > 0) {
                    w.pendingTicks -= WAR_TICK_INTERVAL;
                    if (w.pendingTicks <= 0) {
                        w.pendingTicks = 0;
                        w.active = true;
                        w.startedAt = now;
                        if (s != null) {
                            announce(server, s, Component.translatable("bannerbound.citystate.war.started",
                                cityName(cs)).withStyle(ChatFormatting.RED));
                            DiplomacyManager.broadcastDiplomacyState(server, s);
                        }
                    }
                    data.setDirty();
                }
                if (w.active) anyActive = true;
                if (w.capturedAt > 0 && now - w.capturedAt >= CAPTURE_TIMEOUT && s != null) {
                    resolveCapture(server, s, cs, DiplomacyActionPayload.RAZE); // ignored capture → razed
                }
            }
            if (anyActive) tickGarrison(level, cs, now);
            else discardGarrison(level, cs);
        }
        reconcileStandards(level);
    }

    // ─── Mercenary garrison (defend-only; slow attrition-based respawn) ────────────────────────────

    private static void tickGarrison(ServerLevel level, CityState cs, long now) {
        if (nearestPlayerHorizSq(level, cs.center) > GARRISON_DIST_SQ) {
            discardGarrison(level, cs); // off-screen — drop the live entities (not counted as killed)
            return;
        }
        Garrison g = GARRISONS.computeIfAbsent(cs.id, k -> new Garrison());
        // Prune dead — each death is attrition the city-state must recruit back slowly.
        g.ids.removeIf(u -> {
            Entity e = level.getEntity(u);
            if (e == null || !e.isAlive()) { g.killed++; return true; }
            return false;
        });
        int cap = mercCap(cs);
        // Slow recruitment: one lost defender comes back every MERC_RESPAWN_TICKS.
        if (g.killed > 0 && now - g.lastRecruitTick >= MERC_RESPAWN_TICKS) {
            g.killed--;
            g.lastRecruitTick = now;
        }
        int target = Math.max(0, cap - g.killed);
        while (g.ids.size() < target) {
            MercenaryEntity m = spawnMercenary(level, cs);
            if (m == null) break;
            g.ids.add(m.getUUID());
        }
    }

    private static void discardGarrison(ServerLevel level, CityState cs) {
        Garrison g = GARRISONS.get(cs.id);
        if (g == null) return;
        for (UUID u : g.ids) {
            Entity e = level.getEntity(u);
            if (e != null) e.discard();
        }
        g.ids.clear();
    }

    private static void disbandGarrisonIfNoWars(CityState cs) {
        boolean anyWar = false;
        for (CityStateWar w : cs.wars.values()) {
            if (w.active || w.pendingTicks > 0 || w.capturedAt > 0) { anyWar = true; break; }
        }
        if (!anyWar) GARRISONS.remove(cs.id);
    }

    private static int mercCap(CityState cs) {
        // Prosperity-scaled: a thriving trade partner fields a bigger garrison than a starving hamlet
        // of the same size — sacking your best customer should cost you (plan Phase 3).
        int cap = 3 + (int) (cs.believedPop * (0.15 + 0.10 * cs.prosperity) * cs.difficulty.factor());
        return Math.max(3, Math.min(16, cap));
    }

    private static MercenaryEntity spawnMercenary(ServerLevel level, CityState cs) {
        MercenaryEntity m = BannerboundCore.MERCENARY.get().create(level);
        if (m == null) return null;
        RandomSource rng = level.getRandom();
        Era era = BarbarianTech.techEra(cs.knownTech);
        BarbarianCapability cap = BarbarianTech.memberCapability(cs.knownTech, rng);
        CitizenGender g = rng.nextBoolean() ? CitizenGender.MALE : CitizenGender.FEMALE;
        m.initializeCitizen(null, CitizenNameLoader.randomName(rng, era, g), g, era, ChatFormatting.DARK_RED);
        m.markSimulated();
        double ang = rng.nextDouble() * Math.PI * 2.0;
        double r = 3.0 + rng.nextDouble() * 5.0;
        int px = cs.center.getX() + (int) Math.round(Math.cos(ang) * r);
        int pz = cs.center.getZ() + (int) Math.round(Math.sin(ang) * r);
        int py = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
        m.moveTo(px + 0.5, py, pz + 0.5, rng.nextFloat() * 360.0F, 0.0F);
        if (!level.addFreshEntity(m)) return null;
        // Melee-only loadout from the city-state's tech (prefer the close-combat weapon).
        String meleeId = cap.meleeWeaponItem().isEmpty() ? cap.weaponItem() : cap.meleeWeaponItem();
        Item melee = meleeId.isEmpty() ? Items.AIR
            : BuiltInRegistries.ITEM.get(ResourceLocation.parse(meleeId));
        m.markMercenary(cs.center, cs.id, cap.damage(), cap.attackSpeed(), melee);
        return m;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────────────────

    private static double nearestPlayerHorizSq(ServerLevel level, BlockPos center) {
        double best = Double.MAX_VALUE;
        double cx = center.getX() + 0.5, cz = center.getZ() + 0.5;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            double dx = cx - p.getX(), dz = cz - p.getZ();
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best;
    }

    private static Component cityName(CityState cs) {
        return Component.literal(cs.name).withStyle(ChatFormatting.AQUA);
    }

    private static void announce(MinecraftServer server, Settlement s, Component msg) {
        ServerLevel level = server.overworld();
        for (ServerPlayer p : level.players()) {
            if (s.members().contains(p.getUUID())) p.displayClientMessage(msg, false);
        }
    }

    /** Op test: forces an immediate active war (no countdown) between the player's settlement and the
     *  nearest discovered city-state. */
    public static boolean forceWarNearest(ServerLevel level, ServerPlayer player) {
        Settlement s = SettlementData.get(level).getByPlayer(player.getUUID());
        if (s == null) return false;
        CityStateData data = CityStateData.get(level);
        CityState nearest = null;
        double best = Double.MAX_VALUE;
        for (CityState cs : data.all()) {
            if (!cs.discoveredBy.contains(s.id())) continue;
            double d = cs.center.distSqr(player.blockPosition());
            if (d < best) { best = d; nearest = cs; }
        }
        if (nearest == null) return false;
        CityStateWar w = nearest.getOrCreateWar(s.id());
        w.pendingTicks = 0;
        w.active = true;
        w.capturedAt = 0;
        w.startedAt = level.getGameTime();
        data.setDirty();
        DiplomacyManager.broadcastDiplomacyState(level.getServer(), s);
        return true;
    }
}
