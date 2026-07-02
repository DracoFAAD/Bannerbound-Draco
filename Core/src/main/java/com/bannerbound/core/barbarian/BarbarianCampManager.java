package com.bannerbound.core.barbarian;

import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.CitizenGender;
import com.bannerbound.core.api.settlement.data.CitizenNameLoader;
import com.bannerbound.core.entity.BarbarianEntity;
import com.bannerbound.core.language.SettlementLanguage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Server-side driver for barbarian camps. Phase 2 scope: SEEDING ONLY — it records camps (no
 * entities, no blocks) as players explore qualifying biomes, far from settlements and other camps,
 * with razed sites kept permanently camp-free. Realize-on-observe, off-screen clocks, and combat
 * arrive in later phases.
 *
 * <p>Mirrors the static-manager shape of {@code TraderSimManager}; driven from
 * {@code ResearchEvents.onServerTick} after the trader sim, gated to once every
 * {@link #SCAN_INTERVAL_TICKS}.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class BarbarianCampManager {
    private static final int SCAN_INTERVAL_TICKS = 200;     // ~10s between seed scans
    private static final int REALIZE_INTERVAL_TICKS = 10;   // realize/ghostify pass cadence
    private static final int SEED_OFFSET_CHUNKS = 6;        // place the camp ahead of the explorer
    private static final int MIN_SETTLEMENT_CHUNKS = 10;    // keep camps clear of player territory
    private static final int MIN_CAMP_CHUNKS = 12;          // and spread out from each other / razed sites
    private static final float SEED_CHANCE = 0.4f;          // per qualifying scan
    private static final int CHECK_RADIUS = 9;              // footprint half-extent sampled for flatness
    private static final int CHECK_STEP = 2;                // dense enough to catch tree trunks, not just slopes
    private static final int MAX_SITE_RELIEF = 3;           // reject sites whose footprint height varies more
    private static final int MAX_CAMPS = 64;                // soft global cap (records are cheap, but bound it)
    // Realize-on-observe hysteresis (mirrors the trader sim's REALIZE_FLOOR / +GHOST_MARGIN), kept as
    // squared horizontal distances so the per-camp player check is cheap.
    private static final double REALIZE_DIST_SQ = 64.0 * 64.0;
    private static final double GHOST_DIST_SQ = 112.0 * 112.0;
    private static final int CAMP_CHUNK_RADIUS = 1; // camp pieces stay within center chunk ±1
    private static final double DISCOVER_DIST_SQ = 48.0 * 48.0; // a settlement "discovers" a camp this near
    private static final double REACH_DIST_SQ = 18.0 * 18.0;    // close enough to count as "reached the camp"
    private static final int RAID_TICK_INTERVAL = 40;       // resolve active raids this often
    private static final int RAID_SCHEDULE_INTERVAL = 200;  // check whether to launch a raid this often
    private static final long RAID_DURATION = 3600L;        // squad withdraws after ~3 min if not all slain

    /** One in-flight raid, keyed by campId (a camp raids one settlement at a time). Transient — the
     *  squad is {@code markSimulated} (never saved); a server stop just disperses them. */
    private record Raid(java.util.UUID campId, java.util.UUID settlementId, BlockPos target,
                        java.util.Set<java.util.UUID> squad, long deadlineTick) {
    }

    private static final java.util.Map<java.util.UUID, Raid> ACTIVE_RAIDS = new java.util.HashMap<>();

    private BarbarianCampManager() {
    }

    public static void tickAll(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        long time = overworld.getGameTime();
        if (time % REALIZE_INTERVAL_TICKS == 0) realizePass(overworld);
        if (time % SCAN_INTERVAL_TICKS == 0) seedScan(overworld);
        if (time % RAID_TICK_INTERVAL == 0) tickRaids(overworld);
        if (time % RAID_SCHEDULE_INTERVAL == 0) scheduleRaids(overworld);
        MessengerManager.tickAll(overworld, time); // diplomats travel + arrive (own internal gating)
    }

    /** True if a raid from this camp is currently in progress (messengers hold off while raiding). */
    static boolean hasActiveRaid(java.util.UUID campId) {
        return ACTIVE_RAIDS.containsKey(campId);
    }

    /** True if a barbarian raid is currently besieging this settlement. Used to block disband
     *  while a settlement is under attack. */
    public static boolean isSettlementRaided(java.util.UUID settlementId) {
        for (Raid raid : ACTIVE_RAIDS.values()) {
            if (raid.settlementId().equals(settlementId)) return true;
        }
        return false;
    }

    /** The hub an active raid on this settlement is marching on (town hall / banner at raid start),
     *  or {@code null} when the settlement isn't raided. GUARD_PLAN.md raid muster: guards with no
     *  target in detection range converge on this to intercept the squad. */
    @org.jetbrains.annotations.Nullable
    public static BlockPos activeRaidTarget(java.util.UUID settlementId) {
        for (Raid raid : ACTIVE_RAIDS.values()) {
            if (raid.settlementId().equals(settlementId)) return raid.target();
        }
        return null;
    }

    /** Called when a player settlement is removed (disband / collapse / conquest). Ends any raid
     *  targeting it — discarding the besieging squad so barbarians don't ring an empty patch —
     *  and scrubs the settlement's id out of every camp's discovery/relationship memory so dead
     *  ids don't accumulate forever. In-flight messenger envoys to the settlement are cancelled
     *  too. */
    public static void onSettlementRemoved(ServerLevel level, java.util.UUID settlementId) {
        ACTIVE_RAIDS.values().removeIf(raid -> {
            if (!raid.settlementId().equals(settlementId)) return false;
            for (UUID u : raid.squad()) {
                Entity e = level.getEntity(u);
                if (e != null) e.discard();
            }
            return true;
        });
        BarbarianData data = BarbarianData.get(level);
        boolean changed = false;
        for (BarbarianCamp camp : data.all()) {
            changed |= camp.discoveredBy.remove(settlementId);
            changed |= camp.reachedBy.remove(settlementId);
            changed |= camp.relState.remove(settlementId) != null;
            changed |= camp.relScore.remove(settlementId) != null;
        }
        if (changed) data.setDirty();
        MessengerManager.onSettlementRemoved(level, settlementId);
    }

    /** Make this camp's next raid due immediately (parley refused / messenger killed). */
    static void scheduleRaidSoon(ServerLevel level, BarbarianCamp camp) {
        camp.lastRaidTick = level.getGameTime() - raidPeriod(camp.type, camp.raidDifficulty);
    }

    /** Push this camp's next raid a full period out (tribute accepted buys peace for a while). */
    static void buyRaidCooldown(ServerLevel level, BarbarianCamp camp) {
        camp.lastRaidTick = level.getGameTime();
    }

    // ─── Seeding (records only) ───────────────────────────────────────────────────────────────────

    private static void seedScan(ServerLevel overworld) {
        BarbarianData data = BarbarianData.get(overworld);
        if (data.all().size() >= MAX_CAMPS) return;
        SettlementData settlements = SettlementData.get(overworld);
        for (ServerPlayer player : overworld.players()) {
            if (player.isSpectator()) continue;
            if (overworld.getRandom().nextFloat() >= SEED_CHANCE) continue;
            attemptSeed(overworld, player, data, settlements);
        }
    }

    // ─── Realize-on-observe (spawn the roster near players; ghostify — blocks stay — when far) ──────

    private static void realizePass(ServerLevel level) {
        BarbarianData data = BarbarianData.get(level);
        for (BarbarianCamp camp : data.all()) {
            if (camp.razed) continue;
            double d = nearestPlayerHorizSq(level, camp.center);
            if (d <= REALIZE_DIST_SQ) {
                // Spawn if not realized, or re-spawn if the roster unloaded under us (low view distance).
                if (!camp.realized || !rosterPresent(level, camp)) realizeCamp(level, data, camp);
                discoverNearbyPlayers(level, data, camp);
            } else if (d >= GHOST_DIST_SQ && camp.realized) {
                ghostify(level, camp);
            }
        }
    }

    private static void realizeCamp(ServerLevel level, BarbarianData data, BarbarianCamp camp) {
        // Blocks are stamped persistently at seed/chunk-load (tryStamp), so DH and revisits see a real
        // structure with no pop-in. A backstop here covers the rare case the stamp was deferred (camp
        // recorded while its chunk was briefly unloaded) — the player is within 64 now, so it's loaded.
        tryStamp(level, data, camp);
        discardRoster(level, camp); // idempotent — clear any stale handles before respawning
        java.util.Set<String> known = BarbarianTech.campKnownTech(SettlementData.get(level));
        Era techEra = BarbarianTech.techEra(known);
        // Deterministic name RNG seeded by the camp's tongue, so a camp's roster names are stable
        // across realizes and share the camp's phonology.
        RandomSource nameRng = RandomSource.create(camp.languageSeed);
        // Killed commanders never come back (defeat progress persists). Ordinary members respawn UNLESS
        // the standard is razed — razing stops respawns; you then mop up whoever's left (the commanders).
        int liveCommanders = camp.liveCommanderCount();
        int roster = camp.bannerRazed ? 0 : Math.max(0, camp.memberTarget - camp.commanderCount);
        int total = liveCommanders + roster;
        for (int i = 0; i < total; i++) {
            boolean commander = i < liveCommanders;
            BarbarianEntity npc = spawnMember(level, camp, techEra, known, nameRng, i, commander);
            if (npc == null) continue;
            (commander ? camp.commanderIds : camp.rosterIds).add(npc.getUUID());
        }
        camp.realized = true;
        refreshFindCampEntry(level, camp); // show the find-camp objective while you're near a hostile camp
    }

    private static void ghostify(ServerLevel level, BarbarianCamp camp) {
        discardRoster(level, camp);
        camp.realized = false;
        removeFindCampEntry(level, camp); // the find-camp objective fades when you leave (progress kept)
        // Camp blocks remain placed; relationship/raid state lives on the record.
    }

    // ─── Find-the-camp journal objective (appears near a hostile camp, fades when far) ─────────────

    /** Puts/refreshes the find-camp objective for every discovered settlement that's HOSTILE toward
     *  this camp (live progress read from the record, so it survives the entry fading + reappearing). */
    private static void refreshFindCampEntry(ServerLevel level, BarbarianCamp camp) {
        MinecraftServer server = level.getServer();
        SettlementData sd = SettlementData.get(level);
        for (UUID sid : camp.discoveredBy) {
            Settlement s = sd.getById(sid);
            if (s == null) continue;
            String src = camp.id.toString();
            com.bannerbound.core.journal.JournalManager
                .removeForSettlementBySource(server, s, "barbarian_camp", src);
            boolean hostile = camp.type.isAlwaysHostile()
                || camp.relationToward(sid) == CampRelationState.HOSTILE;
            if (hostile) {
                com.bannerbound.core.journal.JournalManager
                    .putForSettlement(server, s, buildFindCampEntry(level, camp, s));
            }
        }
    }

    private static void removeFindCampEntry(ServerLevel level, BarbarianCamp camp) {
        MinecraftServer server = level.getServer();
        SettlementData sd = SettlementData.get(level);
        for (UUID sid : camp.discoveredBy) {
            Settlement s = sd.getById(sid);
            if (s != null) {
                com.bannerbound.core.journal.JournalManager
                    .removeForSettlementBySource(server, s, "barbarian_camp", camp.id.toString());
            }
        }
    }

    private static com.bannerbound.core.journal.JournalEntry buildFindCampEntry(ServerLevel level,
                                                                                BarbarianCamp camp,
                                                                                Settlement s) {
        // Direction toward the camp from the settlement's hub — NOT raw coordinates (those don't read
        // well and are being phased out of the game entirely). Falls back to no detail if no hub yet.
        boolean reached = camp.reachedBy.contains(s.id());
        // Direction is recomputed CLIENT-side from the live player position (see JournalHudLayer), so the
        // server detail is just a fallback for any non-HUD readout; once reached it reads "found".
        BlockPos ref = s.hasTownHall() ? s.townHallPos() : s.bannerPos();
        String dir = reached ? "" // reached → checkmark only, no trailing detail
            : ref == null ? "" : "to the " + cardinalDirection(ref, camp.center);
        java.util.List<com.bannerbound.core.journal.JournalObjective> objs = java.util.List.of(
            new com.bannerbound.core.journal.JournalObjective("find_camp", "Reach " + camp.name,
                dir, reached),
            new com.bannerbound.core.journal.JournalObjective("commanders", "Slay the commanders",
                camp.commandersKilled + "/" + camp.commanderCount, camp.commandersDefeated()),
            new com.bannerbound.core.journal.JournalObjective("banner", "Raze the standard",
                camp.bannerRazed ? "done" : "", camp.bannerRazed));
        com.bannerbound.core.journal.JournalEntry entry = new com.bannerbound.core.journal.JournalEntry(
            UUID.nameUUIDFromBytes(("barb:" + camp.id).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "barbarian_camp", com.bannerbound.core.journal.JournalEntryType.QUEST,
            camp.name, "Drive out the " + camp.type.englishName(), 5,
            level.getGameTime(), 0L, "barbarian_camp", camp.id.toString(), "", objs);
        entry.setTargetPos(camp.center.asLong()); // client computes direction + draws a waypoint here
        return entry;
    }

    /** 8-point compass bearing from {@code from} to {@code to} as a word ("north", "north-east", …).
     *  Minecraft axes: -Z = north, +Z = south, +X = east, -X = west. A direction reads better than
     *  coordinates and survives the planned removal of coordinates from the game. */
    private static String cardinalDirection(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double adx = Math.abs(dx), adz = Math.abs(dz);
        String ns = dz < 0 ? "north" : "south";
        String ew = dx > 0 ? "east" : "west";
        // tan(67.5°) ≈ 2.414 is the diagonal/cardinal sector boundary for an even 8-way split.
        if (adx > adz * 2.414) return ew;
        if (adz > adx * 2.414) return ns;
        return ns + "-" + ew; // e.g. "north-east"
    }

    /** Records that any settlement whose player is near the camp has "discovered" it (per SETTLEMENT,
     *  not faction — phase-7 messengers will travel to the specific discovered settlement), seeding the
     *  relationship + announcing the discovery; and keeps the "Reach the camp" objective in sync with
     *  whether a member is actually AT the camp right now (ticks when you arrive, UNTICKS when you leave). */
    private static void discoverNearbyPlayers(ServerLevel level, BarbarianData data, BarbarianCamp camp) {
        SettlementData sd = SettlementData.get(level);
        double cx = camp.center.getX() + 0.5, cz = camp.center.getZ() + 0.5;
        java.util.Set<UUID> atCampNow = new java.util.HashSet<>(); // settlements with a member at the camp
        boolean dirty = false;
        boolean reachChanged = false;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            double dx = cx - p.getX(), dz = cz - p.getZ();
            double distSq = dx * dx + dz * dz;
            Settlement s = sd.getByPlayer(p.getUUID());
            if (s == null) continue;
            if (distSq <= DISCOVER_DIST_SQ && camp.discoveredBy.add(s.id())) {
                camp.relState.putIfAbsent(s.id(), camp.type.defaultRelation());
                camp.relScore.putIfAbsent(s.id(), defaultScore(camp.type));
                dirty = true;
                announceDiscovery(level, camp, s); // "<camp> has discovered <settlement>"
            }
            if (distSq <= REACH_DIST_SQ) atCampNow.add(s.id());
        }
        // Reconcile "reached": tick for settlements now at the camp, untick for those that walked off.
        for (UUID sid : atCampNow) {
            if (camp.discoveredBy.contains(sid) && camp.reachedBy.add(sid)) reachChanged = true;
        }
        for (UUID sid : new java.util.ArrayList<>(camp.reachedBy)) {
            if (!atCampNow.contains(sid) && camp.reachedBy.remove(sid)) reachChanged = true;
        }
        if (reachChanged) {
            dirty = true;
            refreshFindCampEntry(level, camp);
        }
        if (dirty) data.setDirty();
    }

    /** Broadcasts "&lt;camp&gt; has discovered &lt;settlement&gt;" to that settlement's members — the camp
     *  name in the camp's colour, the settlement name in its faction colour, the rest red. */
    private static void announceDiscovery(ServerLevel level, BarbarianCamp camp, Settlement s) {
        net.minecraft.network.chat.Component campName = net.minecraft.network.chat.Component
            .literal(camp.name).withStyle(camp.type.nameColor());
        net.minecraft.network.chat.Component settName = net.minecraft.network.chat.Component
            .literal(s.name()).withStyle(s.identityFormatting());
        net.minecraft.network.chat.Component msg = net.minecraft.network.chat.Component.translatable(
            "bannerbound.barbarian.discovered", campName, settName)
            .withStyle(net.minecraft.ChatFormatting.RED);
        for (ServerPlayer p : level.players()) {
            if (s.members().contains(p.getUUID())) p.displayClientMessage(msg, false);
        }
        // Surface the new camp in the Diplomacy tab immediately (it appears in the read-only list).
        if (level.getServer() != null) {
            com.bannerbound.core.api.settlement.DiplomacyManager.broadcastDiplomacyState(level.getServer(), s);
        }
    }

    private static int defaultScore(CampType type) {
        return switch (type.defaultRelation()) {
            case HOSTILE -> -50;
            case NEUTRAL -> 0;
            case FRIENDLY -> 50;
        };
    }

    // ─── Relationship score → state (parley deltas drive this) ─────────────────────────────────────
    private static final int HOSTILE_AT = -25;   // score ≤ this → HOSTILE
    private static final int FRIENDLY_AT = 40;    // score ≥ this → FRIENDLY (if the type allows it)

    /** Recompute a camp's stance toward a settlement from its score, clamped to the type's ceiling
     *  (MARAUDER never leaves HOSTILE; TRIBE tops out at NEUTRAL). */
    static CampRelationState recomputeRelState(BarbarianCamp camp, UUID sid) {
        int score = camp.relScore.getOrDefault(sid, defaultScore(camp.type));
        CampRelationState st = score <= HOSTILE_AT ? CampRelationState.HOSTILE
            : score >= FRIENDLY_AT ? CampRelationState.FRIENDLY
            : CampRelationState.NEUTRAL;
        CampRelationState ceiling = camp.type.relationCeiling();
        if (st.ordinal() > ceiling.ordinal()) st = ceiling; // enum order HOSTILE<NEUTRAL<FRIENDLY
        camp.relState.put(sid, st);
        return st;
    }

    /** Nudge a settlement's relationship score by {@code delta} (clamped ±100) and recompute the stance. */
    static CampRelationState applyRelationDelta(BarbarianData data, BarbarianCamp camp, UUID sid, int delta) {
        int score = camp.relScore.getOrDefault(sid, defaultScore(camp.type));
        camp.relScore.put(sid, Math.max(-100, Math.min(100, score + delta)));
        CampRelationState st = recomputeRelState(camp, sid);
        data.setDirty();
        return st;
    }

    // ─── Defeat / permanent clear (kill all commanders + raze the standard) ────────────────────────

    /** A barbarian died — if it was a commander, persist the defeat progress and clear the camp if
     *  that was the last commander and the standard is already razed. */
    public static void onBarbarianDeath(ServerLevel level, BarbarianEntity dead) {
        UUID campId = dead.campId();
        if (campId == null) return;
        BarbarianData data = BarbarianData.get(level);
        BarbarianCamp camp = data.getById(campId);
        if (camp == null) return;
        boolean wasCommander = camp.commanderIds.remove(dead.getUUID());
        camp.rosterIds.remove(dead.getUUID());
        if (wasCommander) {
            camp.commandersKilled++;
            data.setDirty();
            refreshFindCampEntry(level, camp); // bump the "x/N commanders" objective
            checkDefeat(level, data, camp);
        }
    }

    /** A block at a camp's standard position was broken — mark it razed and maybe clear the camp. */
    public static void onBannerBroken(ServerLevel level, BlockPos pos) {
        BarbarianData data = BarbarianData.get(level);
        BarbarianCamp camp = data.bannerAt(pos);
        if (camp == null || camp.bannerRazed) return;
        camp.bannerRazed = true;
        data.setDirty();
        refreshFindCampEntry(level, camp); // tick the "raze the standard" objective
        checkDefeat(level, data, camp);
    }

    private static void checkDefeat(ServerLevel level, BarbarianData data, BarbarianCamp camp) {
        if (!camp.clearable()) return;
        removeFindCampEntry(level, camp); // the hunt is over — clear the objective
        discardRoster(level, camp);       // disperse any remaining members
        data.removeCamp(camp);            // permanent — razedChunks blocks re-seeding here forever
        announceCleared(level, camp);
        BannerboundCore.LOGGER.info("Barbarian {} camp {} cleared at {}", camp.type, camp.id,
            new ChunkPos(camp.center));
    }

    private static void announceCleared(ServerLevel level, BarbarianCamp camp) {
        net.minecraft.network.chat.Component msg = net.minecraft.network.chat.Component.translatable(
            "bannerbound.barbarian.camp_cleared", net.minecraft.network.chat.Component.literal(camp.name))
            .withStyle(net.minecraft.ChatFormatting.GREEN);
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().closerThan(camp.center, 200.0)) {
                p.displayClientMessage(msg, false);
            }
        }
    }

    // ─── Raids (hostile camps send a squad to the settlement; repelled raids escalate) ─────────────

    /** Launches raids from hostile camps against discovered settlements whose members are online,
     *  once the per-type cooldown (shortened by {@code raidDifficulty}) has elapsed. */
    private static void scheduleRaids(ServerLevel level) {
        BarbarianData data = BarbarianData.get(level);
        if (data.all().isEmpty()) return;
        long now = level.getGameTime();
        SettlementData sd = SettlementData.get(level);
        for (BarbarianCamp camp : data.all()) {
            if (camp.razed || ACTIVE_RAIDS.containsKey(camp.id)) continue;
            if (now - camp.lastRaidTick < raidPeriod(camp.type, camp.raidDifficulty)) continue;
            for (UUID sid : camp.discoveredBy) {
                Settlement s = sd.getById(sid);
                if (s == null) continue;
                if (!camp.type.isAlwaysHostile()
                        && camp.relationToward(sid) != CampRelationState.HOSTILE) continue;
                if (!hasOnlineMember(level, s)) continue;
                if (triggerRaid(level, data, camp, s)) break; // at most one raid per camp per pass
            }
        }
    }

    /** Builds + spawns a raid squad ringing the settlement, marching on its hub. Returns false if the
     *  hub area isn't loaded or no raiders could spawn. */
    private static boolean triggerRaid(ServerLevel level, BarbarianData data, BarbarianCamp camp,
                                       Settlement settlement) {
        BlockPos target = settlement.hasTownHall() ? settlement.townHallPos() : settlement.bannerPos();
        if (target == null || !level.hasChunk(target.getX() >> 4, target.getZ() >> 4)) return false;

        java.util.Set<String> known = BarbarianTech.campKnownTech(SettlementData.get(level));
        Era techEra = BarbarianTech.techEra(known);

        int size = raidSize(camp.type, camp.raidDifficulty);
        RandomSource rng = level.getRandom();
        java.util.Set<UUID> squad = new java.util.HashSet<>();
        for (int i = 0; i < size; i++) {
            double ang = rng.nextDouble() * Math.PI * 2.0;
            double r = 24.0 + rng.nextDouble() * 12.0; // ring the settlement and converge inward
            int sx = target.getX() + (int) Math.round(Math.cos(ang) * r);
            int sz = target.getZ() + (int) Math.round(Math.sin(ang) * r);
            if (!level.hasChunk(sx >> 4, sz >> 4)) continue;
            int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);
            BarbarianEntity raider = BannerboundCore.BARBARIAN.get().create(level);
            if (raider == null) continue;
            // Per-raider weapon variety (spear/sword/club/bow), same as camp members.
            BarbarianCapability cap = BarbarianTech.memberCapability(known, rng);
            Item weapon = cap.weaponItem().isEmpty() ? Items.AIR
                : BuiltInRegistries.ITEM.get(ResourceLocation.parse(cap.weaponItem()));
            ResourceLocation proj = cap.ranged() && !cap.projectile().isEmpty()
                ? ResourceLocation.parse(cap.projectile()) : null;
            Item raidMelee = cap.meleeWeaponItem().isEmpty() ? Items.AIR
                : BuiltInRegistries.ITEM.get(ResourceLocation.parse(cap.meleeWeaponItem()));
            CitizenGender g = rng.nextBoolean() ? CitizenGender.MALE : CitizenGender.FEMALE;
            raider.initializeCitizen(null, CitizenNameLoader.randomName(rng, techEra, g), g, techEra,
                camp.type.nameColor());
            raider.markSimulated();
            raider.moveTo(sx + 0.5, sy, sz + 0.5, rng.nextFloat() * 360.0F, 0.0F);
            if (!level.addFreshEntity(raider)) continue;
            // Leash anchor = the settlement hub, so they march in and fight; NOT a camp member roster.
            raider.markBarbarianMember(target, camp.id, cap.damage(), cap.attackSpeed(), weapon,
                cap.ranged(), proj, raidMelee, cap.kites());
            squad.add(raider.getUUID());
        }
        if (squad.isEmpty()) return false;
        ACTIVE_RAIDS.put(camp.id,
            new Raid(camp.id, settlement.id(), target, squad, level.getGameTime() + RAID_DURATION));
        camp.lastRaidTick = level.getGameTime();
        data.setDirty();
        announceRaid(level, settlement, camp);
        return true;
    }

    private static void tickRaids(ServerLevel level) {
        if (ACTIVE_RAIDS.isEmpty()) return;
        long now = level.getGameTime();
        BarbarianData data = BarbarianData.get(level);
        java.util.Iterator<Raid> it = ACTIVE_RAIDS.values().iterator();
        while (it.hasNext()) {
            Raid raid = it.next();
            // If the hub unloaded (everyone left), abandon the raid quietly — no escalation.
            if (!level.hasChunk(raid.target().getX() >> 4, raid.target().getZ() >> 4)) {
                it.remove();
                continue;
            }
            raid.squad().removeIf(u -> {
                Entity e = level.getEntity(u);
                return e == null || !e.isAlive();
            });
            BarbarianCamp camp = data.getById(raid.campId());
            if (raid.squad().isEmpty()) { // all slain → repelled → next raid is harder
                if (camp != null) {
                    camp.raidDifficulty++;
                    data.setDirty();
                }
                announceRaidEnd(level, raid, camp, "bannerbound.barbarian.raid_repelled",
                    net.minecraft.ChatFormatting.GREEN);
                it.remove();
            } else if (now > raid.deadlineTick()) { // time up → the survivors withdraw
                for (UUID u : raid.squad()) {
                    Entity e = level.getEntity(u);
                    if (e != null) e.discard();
                }
                announceRaidEnd(level, raid, camp, "bannerbound.barbarian.raid_withdrawn",
                    net.minecraft.ChatFormatting.YELLOW);
                it.remove();
            }
        }
    }

    /** Tells the raided settlement's online members the raid is over (repelled or withdrawn). */
    private static void announceRaidEnd(ServerLevel level, Raid raid, BarbarianCamp camp, String key,
                                        net.minecraft.ChatFormatting color) {
        Settlement s = SettlementData.get(level).getById(raid.settlementId());
        if (s == null) return;
        sendRaidBanner(level, s, false); // raid's over — drop the banner
        String campName = camp != null ? camp.name : "the barbarians";
        net.minecraft.network.chat.Component msg = net.minecraft.network.chat.Component.translatable(
            key, net.minecraft.network.chat.Component.literal(campName)).withStyle(color);
        for (ServerPlayer p : level.players()) {
            if (s.members().contains(p.getUUID())) p.displayClientMessage(msg, false);
        }
    }

    private static int raidSize(CampType type, int difficulty) {
        int base = switch (type) {
            case NOMAD -> 3;
            case TRIBE -> 4;
            case RAIDER -> 6;
            case MARAUDER -> 4;
        };
        return Math.min(12, base + difficulty);
    }

    private static long raidPeriod(CampType type, int difficulty) {
        long base = switch (type) {
            case RAIDER -> 48000L;    // ~2 days — raiders strike often
            case MARAUDER -> 96000L;  // demand-heavy, raids less often
            default -> 72000L;        // ~3 days
        };
        return Math.max(24000L, base - (long) difficulty * 6000L); // harder camps strike sooner
    }

    private static boolean hasOnlineMember(ServerLevel level, Settlement s) {
        for (ServerPlayer p : level.players()) {
            if (s.members().contains(p.getUUID())) return true;
        }
        return false;
    }

    private static void announceRaid(ServerLevel level, Settlement settlement, BarbarianCamp camp) {
        net.minecraft.network.chat.Component msg = net.minecraft.network.chat.Component.translatable(
            "bannerbound.barbarian.raid_incoming", camp.type.displayName(),
            net.minecraft.network.chat.Component.literal(camp.name))
            .withStyle(net.minecraft.ChatFormatting.RED);
        // Night raid against a sleeping watch → say WHY the guards aren't at the border yet (they
        // wake and muster when the raid arrives, but the Night Watch policy would have had them
        // already patrolling). Visibility for a complex system, not a nag — one line per raid.
        net.minecraft.network.chat.Component watchHint = sleepingWatchHint(level, settlement);
        for (ServerPlayer p : level.players()) {
            if (!settlement.members().contains(p.getUUID())) continue;
            p.displayClientMessage(msg, false);
            if (watchHint != null) p.displayClientMessage(watchHint, false);
            // War-horn fanfare + the persistent "being RAIDED" banner (cleared in announceRaidEnd).
            p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(),
                net.minecraft.sounds.SoundEvents.RAID_HORN.value(),
                net.minecraft.sounds.SoundSource.HOSTILE, 1.0F, 1.0F);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
                new com.bannerbound.core.network.RaidWarningPayload(true));
        }
    }

    /** "Your watch was asleep" hint line, or null when it doesn't apply — only for a NIGHT raid on
     *  a settlement that HAS guards but no Night Watch policy. */
    @org.jetbrains.annotations.Nullable
    private static net.minecraft.network.chat.Component sleepingWatchHint(ServerLevel level,
                                                                          Settlement settlement) {
        long t = level.getDayTime() % 24_000L;
        boolean night = t >= 12_500L && t < 23_460L;   // SleepGoal's night window
        if (!night) return null;
        if (settlement.hasPolicy(com.bannerbound.core.api.settlement.PolicyRegistry.NIGHT_WATCH)) {
            return null;
        }
        boolean hasGuard = false;
        for (com.bannerbound.core.entity.CitizenEntity c
                : com.bannerbound.core.api.settlement.SettlementManager.allCitizensOf(level, settlement)) {
            if (c.isGuard()) { hasGuard = true; break; }
        }
        if (!hasGuard) return null;
        return net.minecraft.network.chat.Component
            .translatable("bannerbound.barbarian.raid_watch_asleep")
            .withStyle(net.minecraft.ChatFormatting.YELLOW);
    }

    /** Toggle the client raid banner for every online member of {@code settlement}. */
    private static void sendRaidBanner(ServerLevel level, Settlement settlement, boolean active) {
        if (settlement == null) return;
        for (ServerPlayer p : level.players()) {
            if (settlement.members().contains(p.getUUID())) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
                    new com.bannerbound.core.network.RaidWarningPayload(active));
            }
        }
    }

    /** Op test: forces the nearest non-razed camp to discover {@code player}'s settlement, turn
     *  HOSTILE, and raid immediately (bypassing the cooldown). Returns false if none eligible. */
    public static boolean triggerNearestRaid(ServerLevel level, ServerPlayer player) {
        Settlement s = SettlementData.get(level).getByPlayer(player.getUUID());
        if (s == null) return false;
        BarbarianData data = BarbarianData.get(level);
        BarbarianCamp nearest = null;
        double best = Double.MAX_VALUE;
        for (BarbarianCamp c : data.all()) {
            if (c.razed) continue;
            double d = c.center.distSqr(player.blockPosition());
            if (d < best) {
                best = d;
                nearest = c;
            }
        }
        if (nearest == null) return false;
        nearest.discoveredBy.add(s.id());
        nearest.relState.put(s.id(), CampRelationState.HOSTILE);
        nearest.relScore.put(s.id(), -100);
        ACTIVE_RAIDS.remove(nearest.id); // bypass any existing raid/cooldown
        nearest.lastRaidTick = 0;
        boolean ok = triggerRaid(level, data, nearest, s);
        data.setDirty();
        return ok;
    }

    // ─── Persistent block stamp (decoupled from NPC realize, so camps read as structures / DH-safe) ─

    /** Stamps the camp's blocks once, the first time its chunk is loaded. Persistent thereafter, so
     *  Distant Horizons captures it on its first scan of the chunk and revisits show no pop-in. No-op
     *  if already stamped or the center chunk isn't loaded (never force-loads). */
    private static void tryStamp(ServerLevel level, BarbarianData data, BarbarianCamp camp) {
        if (camp.structureStamped || camp.razed) return;
        ChunkPos cc = new ChunkPos(camp.center);
        // The village footprint spans the center chunk ±1; require the whole span loaded so no piece
        // is silently skipped, then stamp it all at once (cells are hasChunk-guarded regardless).
        for (int dx = -CAMP_CHUNK_RADIUS; dx <= CAMP_CHUNK_RADIUS; dx++) {
            for (int dz = -CAMP_CHUNK_RADIUS; dz <= CAMP_CHUNK_RADIUS; dz++) {
                if (!level.hasChunk(cc.x + dx, cc.z + dz)) return; // wait for a natural full load
            }
        }
        CampStructureStamper.stamp(level, camp);
        camp.structureStamped = true;
        data.setDirty();
    }

    /** When a chunk holding an un-stamped camp loads (exploration / relog), stamp it then — like a
     *  worldgen structure appearing at the render edge rather than popping in at 64 blocks. Deferred a
     *  tick (the chunk may not be FULL yet) and re-checked, mirroring {@code ResourceChunkPopulator}. */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        if (sl.dimension() != Level.OVERWORLD) return;
        BarbarianData data = BarbarianData.get(sl);
        if (data.all().isEmpty()) return;
        ChunkPos cp = event.getChunk().getPos();
        // Any un-stamped camp whose footprint span touches this chunk may now be fully loaded — try it
        // (deferred a tick; the chunk may not be FULL yet, mirroring ResourceChunkPopulator).
        for (BarbarianCamp camp : data.all()) {
            if (camp.razed || camp.structureStamped) continue;
            ChunkPos cc = new ChunkPos(camp.center);
            if (Math.max(Math.abs(cc.x - cp.x), Math.abs(cc.z - cp.z)) > CAMP_CHUNK_RADIUS) continue;
            final BarbarianCamp c = camp;
            sl.getServer().execute(() -> {
                if (data.getById(c.id) != null && !c.structureStamped) tryStamp(sl, data, c);
            });
        }
    }

    private static BarbarianEntity spawnMember(ServerLevel level, BarbarianCamp camp, Era techEra,
                                             java.util.Set<String> known, RandomSource nameRng, int index,
                                             boolean commander) {
        BarbarianEntity npc = BannerboundCore.BARBARIAN.get().create(level);
        if (npc == null) return null;
        // Per-member weapon variety (spear/sword/club, or bow if archery) — stable per camp+slot so a
        // camp's loadout mix doesn't reshuffle every realize.
        RandomSource weaponRng = RandomSource.create(camp.languageSeed ^ (0x9E3779B97F4A7C15L * (index + 1)));
        BarbarianCapability cap = BarbarianTech.memberCapability(known, weaponRng);
        Item weapon = cap.weaponItem().isEmpty() ? Items.AIR
            : BuiltInRegistries.ITEM.get(ResourceLocation.parse(cap.weaponItem()));
        CitizenGender gender = nameRng.nextBoolean() ? CitizenGender.MALE : CitizenGender.FEMALE;
        // Name styled through the camp's own tongue (per-camp phonology via languageSeed), so each camp
        // sounds distinct. settlementId == null → detached citizen; techEra picks the era/gender skin.
        String base = CitizenNameLoader.randomName(nameRng, techEra, gender);
        String name = SettlementLanguage.citizenName(camp.languageSeed, techEra, base, null, null,
            "barb:" + index);
        npc.initializeCitizen(null, name, gender, techEra, camp.type.nameColor());
        // Commanders wear the same crown glyph a settlement chief gets — so you can tell who to kill.
        if (commander) {
            net.minecraft.network.chat.Component current = npc.getCustomName();
            npc.setCustomName(net.minecraft.network.chat.Component.empty()
                .append(com.bannerbound.core.api.Glyphs.crown())
                .append(net.minecraft.network.chat.Component.literal(" "))
                .append(current != null ? current : net.minecraft.network.chat.Component.literal(name)));
        }
        npc.markSimulated(); // never written to NBT; discarded explicitly on ghostify
        double ang = level.random.nextDouble() * Math.PI * 2.0;
        double r = 2.0 + level.random.nextDouble() * 3.0;
        int px = camp.center.getX() + (int) Math.round(Math.cos(ang) * r);
        int pz = camp.center.getZ() + (int) Math.round(Math.sin(ang) * r);
        int py = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
        npc.moveTo(px + 0.5, py, pz + 0.5, level.random.nextFloat() * 360.0F, 0.0F);
        if (!level.addFreshEntity(npc)) return null;
        // Camp wander/leash + capability weapon + retaliation/hostility targeting (+ ranged if any).
        ResourceLocation projectile = cap.ranged() && !cap.projectile().isEmpty()
            ? ResourceLocation.parse(cap.projectile()) : null;
        Item meleeWeapon = cap.meleeWeaponItem().isEmpty() ? Items.AIR
            : BuiltInRegistries.ITEM.get(ResourceLocation.parse(cap.meleeWeaponItem()));
        npc.markBarbarianMember(camp.center, camp.id, cap.damage(), cap.attackSpeed(), weapon,
            cap.ranged(), projectile, meleeWeapon, cap.kites());
        return npc;
    }

    private static void discardRoster(ServerLevel level, BarbarianCamp camp) {
        for (java.util.UUID u : camp.commanderIds) {
            Entity e = level.getEntity(u);
            if (e != null) e.discard();
        }
        for (java.util.UUID u : camp.rosterIds) {
            Entity e = level.getEntity(u);
            if (e != null) e.discard();
        }
        camp.commanderIds.clear();
        camp.rosterIds.clear();
    }

    private static boolean rosterPresent(ServerLevel level, BarbarianCamp camp) {
        for (java.util.UUID u : camp.commanderIds) {
            if (level.getEntity(u) != null) return true;
        }
        for (java.util.UUID u : camp.rosterIds) {
            if (level.getEntity(u) != null) return true;
        }
        return false;
    }

    private static double nearestPlayerHorizSq(ServerLevel level, BlockPos center) {
        double best = Double.MAX_VALUE;
        double cx = center.getX() + 0.5, cz = center.getZ() + 0.5;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            double dx = cx - p.getX();
            double dz = cz - p.getZ();
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best;
    }

    private static void attemptSeed(ServerLevel level, ServerPlayer player, BarbarianData data,
                                    SettlementData settlements) {
        // Candidate center: a few chunks ahead of where the player faces (within render distance,
        // so the biome read below doesn't force-load a chunk).
        net.minecraft.core.Direction facing = player.getDirection();
        ChunkPos playerChunk = new ChunkPos(player.blockPosition());
        ChunkPos candidate = new ChunkPos(
            playerChunk.x + facing.getStepX() * SEED_OFFSET_CHUNKS,
            playerChunk.z + facing.getStepZ() * SEED_OFFSET_CHUNKS);

        BlockPos sample = new BlockPos(candidate.getMinBlockX() + 8, player.blockPosition().getY(),
            candidate.getMinBlockZ() + 8);
        Holder<Biome> biome = level.getBiome(sample);
        ResourceLocation biomeId = biome.unwrapKey().map(k -> k.location()).orElseGet(
            () -> level.registryAccess().registryOrThrow(Registries.BIOME).getKey(biome.value()));

        CampType type = campTypeFor(biome, biomeId);
        if (type == null) return; // ocean/river/beach or otherwise unsuitable

        // Distance gates: clear of settlements (incl. working claims via the same helper) and of any
        // other camp or razed site.
        if (settlements.hasClaimsWithin(candidate, 0, MIN_SETTLEMENT_CHUNKS)) return;
        if (data.hasCampOrRazedWithin(candidate, MIN_CAMP_CHUNKS)) return;
        if (!isSiteSuitable(level, sample.getX(), sample.getZ())) return; // no camps on cliffs / water

        createCamp(level, data, sample, type, biomeId);
    }

    /** Rejects unsuitable camp sites: samples a dense grid across the footprint and bails if any
     *  column is over water OR the relief (max − min) exceeds {@link #MAX_SITE_RELIEF}.
     *  MOTION_BLOCKING_NO_LEAVES counts tree TRUNKS (logs block motion) but ignores foliage, so a
     *  trunk column spikes the relief — this steers camps away from cliffs, trees AND water. */
    private static boolean isSiteSuitable(ServerLevel level, int cx, int cz) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int dx = -CHECK_RADIUS; dx <= CHECK_RADIUS; dx += CHECK_STEP) {
            for (int dz = -CHECK_RADIUS; dz <= CHECK_RADIUS; dz += CHECK_STEP) {
                int x = cx + dx, z = cz + dz;
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (!level.getFluidState(new BlockPos(x, h - 1, z)).isEmpty()) return false; // water
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        }
        return max - min <= MAX_SITE_RELIEF;
    }

    private static BarbarianCamp createCamp(ServerLevel level, BarbarianData data, BlockPos center,
                                            CampType type, ResourceLocation biomeId) {
        UUID id = UUID.randomUUID();
        BarbarianCamp camp = new BarbarianCamp(id, type, center, biomeId);
        // Type-biased language seed (refined with a per-type phonology salt in Phase 4).
        camp.languageSeed = id.getMostSignificantBits() ^ id.getLeastSignificantBits()
            ^ ((long) type.ordinal() << 32);
        camp.name = BarbarianNames.generate(camp.languageSeed); // place-name in the camp's tongue
        camp.commanderCount = type == CampType.RAIDER ? 2 : 1;
        camp.memberTarget = switch (type) {
            case NOMAD -> 6;
            case TRIBE -> 7;
            case RAIDER -> 9;
            case MARAUDER -> 8;
        };
        data.addCamp(camp);
        // Stamp the blocks now if the chunk is loaded (it is when seeded near a player); otherwise the
        // onChunkLoad handler stamps it the first time the chunk loads. Blocks are persistent either way.
        tryStamp(level, data, camp);
        BannerboundCore.LOGGER.info("Created {} camp {} at {} (biome {})",
            type, id, new ChunkPos(center), biomeId);
        return camp;
    }

    // ─── Op test helpers ──────────────────────────────────────────────────────────────────────────

    /** Force-creates a camp at {@code center}, bypassing the chance roll and distance gates. If
     *  {@code type} is null it's resolved from the biome (falling back to MARAUDER for, e.g., a
     *  spawn over water). Walk within 64 blocks to realize it. */
    public static BarbarianCamp forceSpawn(ServerLevel level, BlockPos center, CampType type) {
        BarbarianData data = BarbarianData.get(level);
        Holder<Biome> biome = level.getBiome(center);
        ResourceLocation biomeId = biome.unwrapKey().map(k -> k.location()).orElseGet(
            () -> level.registryAccess().registryOrThrow(Registries.BIOME).getKey(biome.value()));
        CampType t = type != null ? type : campTypeFor(biome, biomeId);
        if (t == null) t = CampType.MARAUDER;
        return createCamp(level, data, center, t, biomeId);
    }

    /** Removes every camp (discarding any realized NPCs) and clears razed memory. Returns the count. */
    public static int clearAllCamps(ServerLevel level) {
        BarbarianData data = BarbarianData.get(level);
        int n = data.all().size();
        for (BarbarianCamp camp : data.all()) {
            discardRoster(level, camp);
        }
        data.clear();
        return n;
    }

    /**
     * Maps a biome to its camp type, or null if no camp should seed there (oceans, rivers, beaches).
     * Vanilla-tag + base-temperature based; could become a datapack table later.
     */
    private static CampType campTypeFor(Holder<Biome> biome, ResourceLocation biomeId) {
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN)
                || biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_BEACH)) {
            return null;
        }
        String path = biomeId == null ? "" : biomeId.getPath();
        if (biome.is(BiomeTags.IS_JUNGLE) || path.contains("swamp") || path.contains("mangrove")) {
            return CampType.TRIBE;
        }
        float temp = biome.value().getBaseTemperature();
        if (biome.is(BiomeTags.IS_BADLANDS) || biome.is(BiomeTags.IS_SAVANNA) || temp >= 1.5f) {
            return CampType.NOMAD;
        }
        if (biome.is(BiomeTags.IS_TAIGA) || temp <= 0.3f) {
            return CampType.RAIDER;
        }
        return CampType.MARAUDER; // temperate plains/forest and the rest of the land
    }
}
