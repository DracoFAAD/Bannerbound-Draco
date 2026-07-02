package com.bannerbound.core.citystate;

import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.Config;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.barbarian.BarbarianTech;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;

/**
 * Server-side driver for AI city-states (CITY_STATES plan, Phase 1). Discovers vanilla villages near
 * players (their meeting-point bell), repurposes them as diplomatic actors with a generated name and
 * tongue, stamps a faction banner beside the bell as the capture objective, and runs each one's
 * abstract, grounded economy + self-evolution on an off-screen clock.
 *
 * <p>Mirrors the static-manager shape of {@code BarbarianCampManager}; driven from
 * {@code ResearchEvents.onServerTick}, self-gated to cheap periodic passes.
 *
 * <p>While a city-state is alive we never touch its villagers or buildings (mod compatibility); the
 * banner is the only block we add, and the economy is an abstract ledger — no items materialise in
 * chests off-screen (anti-cheat, §1D). Only when a city-state is RAZED do we remove its villagers and
 * slowly crumble its buildings (the ruination pass below).
 */
@ApiStatus.Internal
public final class CityStateManager {
    private static final int DETECT_INTERVAL_TICKS = 100;   // ~5s between village scans
    private static final int REALIZE_INTERVAL_TICKS = 20;   // realize/discover pass cadence
    private static final int ECONOMY_INTERVAL_TICKS = 600;  // ~30s economy/evolution clock tick
    private static final int DETECT_RADIUS = 80;            // search this far around a player for a bell
    private static final int MIN_CITYSTATE_CHUNKS = 6;      // one record per village (dedupe)
    private static final int VILLAGE_SCAN_RADIUS = 96;      // POI scan radius to claim the whole village
    private static final int HOME_COUNT_RADIUS = 48;        // beds counted within this of the bell
    private static final double REALIZE_DIST_SQ = 64.0 * 64.0;
    private static final double DISCOVER_DIST_SQ = 48.0 * 48.0;
    private static final int MAX_CITYSTATES = 128;          // soft global cap

    // Grounding-scan budget: claimed chunks classified per realize pass while a player is near.
    // ChunkResources.typeAt probes ~8 columns per chunk, so keep the bite small; a village converges
    // over a few seconds of proximity and the result persists forever.
    private static final int CHUNK_SCANS_PER_PASS = 4;

    private CityStateManager() {
    }

    /** Master switch (Config.ENABLE_CITY_STATES, default off) — city-states are a Classical-era
     *  feature; barbarian camps fill the AI-neighbour role in the Ancient era. Every city-state
     *  surface (ticking, war, protection, diplomacy rows, commands) checks this one gate. */
    public static boolean enabled() {
        return Config.ENABLE_CITY_STATES.get();
    }

    public static void tickAll(MinecraftServer server) {
        if (!enabled()) return;
        ServerLevel overworld = server.overworld();
        long time = overworld.getGameTime();
        if (time % REALIZE_INTERVAL_TICKS == 0) realizePass(overworld);
        if (time % DETECT_INTERVAL_TICKS == 0) detectScan(overworld);
        if (time % ECONOMY_INTERVAL_TICKS == 0) economyTick(overworld);
    }

    // ─── Detection (find villages near players via the meeting-point bell) ──────────────────────────

    private static void detectScan(ServerLevel level) {
        CityStateData data = CityStateData.get(level);
        if (data.all().size() >= MAX_CITYSTATES) return;
        SettlementData settlements = SettlementData.get(level);
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            attemptDetect(level, player, data, settlements);
        }
    }

    private static void attemptDetect(ServerLevel level, ServerPlayer player, CityStateData data,
                                      SettlementData settlements) {
        PoiManager poi = level.getPoiManager();
        Optional<BlockPos> bell = poi.findClosest(
            holder -> holder.is(PoiTypes.MEETING), player.blockPosition(), DETECT_RADIUS,
            PoiManager.Occupancy.ANY);
        if (bell.isEmpty()) return;
        BlockPos center = bell.get();
        ChunkPos cc = new ChunkPos(center);
        if (data.isRazedChunk(cc.toLong())) return;                          // razed villages never re-form
        if (data.hasCityStateWithin(cc, MIN_CITYSTATE_CHUNKS)) return;        // already a city-state here
        if (settlements.getByChunk(cc.toLong()) != null) return;             // inside a player claim — skip
        createCityState(level, data, center);
    }

    private static CityState createCityState(ServerLevel level, CityStateData data, BlockPos center) {
        UUID id = UUID.randomUUID();
        Holder<Biome> biome = level.getBiome(center);
        ResourceLocation biomeId = biome.unwrapKey().map(k -> k.location()).orElseGet(
            () -> level.registryAccess().registryOrThrow(Registries.BIOME).getKey(biome.value()));
        CityState cs = new CityState(id, center, biomeId);
        cs.languageSeed = id.getMostSignificantBits() ^ id.getLeastSignificantBits();
        cs.name = CityStateNames.generate(cs.languageSeed);
        cs.difficulty = CityStateDifficulty.fromName(Config.CITY_STATE_DIFFICULTY.get());
        cs.lastEconomyTick = level.getGameTime();
        cs.dayIndex = level.getGameTime() / 24000L;
        claimVillage(level, cs);
        data.add(cs);
        BannerboundCore.LOGGER.info("Detected city-state {} '{}' at {} (biome {})",
            id, cs.name, new ChunkPos(center), biomeId);
        return cs;
    }

    // ─── Realize-on-observe (stamp banner + discover when a player is near; idle when far) ──────────

    private static void realizePass(ServerLevel level) {
        CityStateData data = CityStateData.get(level);
        for (CityState cs : data.all()) {
            double d = nearestPlayerHorizSq(level, cs.center);
            if (d <= REALIZE_DIST_SQ) {
                if (!cs.realized) {
                    countBelievedPop(level, cs, data);
                    scanJobPois(level, cs, data);
                    // Re-scan the village footprint now more chunks are loaded — claims only grow,
                    // so a sprawling village gets fully covered on first close approach.
                    if (claimVillage(level, cs)) data.reindex(cs);
                    cs.realized = true;
                }
                // Keeps retrying while a player is near (early-outs once stamped) — a cluttered or
                // terraced bell plaza can fail a single attempt.
                stampBanner(level, cs, data);
                scanResourceChunks(level, cs, data);
                discoverNearbyPlayers(level, data, cs);
            } else {
                cs.realized = false;
            }
        }
    }

    /** Counts vanilla home POIs (beds) around the bell — the grounded population BASELINE. Believed
     *  population = homes + the prosperity-driven daily drift (CityStateEconomy). Only runs when a
     *  player is near (chunks loaded). */
    private static void countBelievedPop(ServerLevel level, CityState cs, CityStateData data) {
        long homes = level.getPoiManager().getCountInRange(
            holder -> holder.is(PoiTypes.HOME), cs.center, HOME_COUNT_RADIUS, PoiManager.Occupancy.ANY);
        int counted = Math.max(CityState.BASE_POP, (int) Math.min(64, homes));
        int pop = Math.max(CityState.BASE_POP, Math.min(64, counted + cs.popDrift));
        if (counted != cs.countedHomes || pop != cs.believedPop) {
            cs.countedHomes = counted;
            cs.believedPop = pop;
            data.setDirty();
        }
    }

    /** Counts the village's job-site POIs by type (farms, butchers, masons…) — persisted so the
     *  economy's production weights stay grounded in the real village off-screen. */
    private static void scanJobPois(ServerLevel level, CityState cs, CityStateData data) {
        java.util.Map<String, Integer> found = new java.util.LinkedHashMap<>();
        level.getPoiManager().getInRange(
            h -> h.is(PoiTypeTags.ACQUIRABLE_JOB_SITE), cs.center, HOME_COUNT_RADIUS,
            PoiManager.Occupancy.ANY)
            .forEach(rec -> {
                ResourceLocation type = rec.getPoiType().unwrapKey()
                    .map(k -> k.location()).orElse(null);
                if (type != null) found.merge(type.toString(), 1, Integer::sum);
            });
        if (!found.equals(cs.jobPois)) {
            cs.jobPois.clear();
            cs.jobPois.putAll(found);
            cs.activeGoodsCache = null;
            data.setDirty();
        }
    }

    /** Lazily classifies the city-state's claimed chunks against the specialized-chunks layer
     *  ({@code ChunkResources.typeAt}) — a small budget per pass, currently-loaded chunks only, each
     *  chunk exactly once. This is how a village on a TIN chunk comes to sell tin. */
    private static void scanResourceChunks(ServerLevel level, CityState cs, CityStateData data) {
        if (cs.scannedChunks.size() >= cs.claimedChunks.size()) return;
        int budget = CHUNK_SCANS_PER_PASS;
        boolean dirty = false;
        for (long packed : cs.claimedChunks) {
            if (budget <= 0) break;
            if (cs.scannedChunks.contains(packed)) continue;
            ChunkPos cp = new ChunkPos(packed);
            if (!level.hasChunk(cp.x, cp.z)) continue; // never force-load; catch it on a later visit
            budget--;
            com.bannerbound.core.territory.ChunkResource type =
                com.bannerbound.core.territory.ChunkResources.typeAt(level, cp);
            cs.scannedChunks.add(packed);
            if (type != com.bannerbound.core.territory.ChunkResource.NONE) {
                cs.resourceChunks.merge(type.name(), 1, Integer::sum);
                cs.activeGoodsCache = null;
            }
            dirty = true;
        }
        if (dirty) data.setDirty();
    }

    private static void discoverNearbyPlayers(ServerLevel level, CityStateData data, CityState cs) {
        SettlementData sd = SettlementData.get(level);
        double cx = cs.center.getX() + 0.5, cz = cs.center.getZ() + 0.5;
        boolean dirty = false;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            double dx = cx - p.getX(), dz = cz - p.getZ();
            if (dx * dx + dz * dz > DISCOVER_DIST_SQ) continue;
            Settlement s = sd.getByPlayer(p.getUUID());
            if (s == null) continue;
            if (cs.discoveredBy.add(s.id())) {
                cs.relScore.putIfAbsent(s.id(), 0);
                dirty = true;
                announceDiscovery(level, cs, s);
                com.bannerbound.core.api.settlement.DiplomacyManager.broadcastDiplomacyState(
                    level.getServer(), s);
            }
        }
        if (dirty) data.setDirty();
    }

    private static void announceDiscovery(ServerLevel level, CityState cs, Settlement s) {
        net.minecraft.network.chat.Component csName = net.minecraft.network.chat.Component
            .literal(cs.name).withStyle(net.minecraft.ChatFormatting.AQUA);
        net.minecraft.network.chat.Component settName = net.minecraft.network.chat.Component
            .literal(s.name()).withStyle(s.identityFormatting());
        net.minecraft.network.chat.Component msg = net.minecraft.network.chat.Component.translatable(
            "bannerbound.citystate.discovered", csName, settName)
            .withStyle(net.minecraft.ChatFormatting.GRAY);
        for (ServerPlayer p : level.players()) {
            if (s.members().contains(p.getUUID())) p.displayClientMessage(msg, false);
        }
    }

    // ─── Banner stamp (the only block we add — beside the bell, the capture objective) ─────────────

    private static void stampBanner(ServerLevel level, CityState cs, CityStateData data) {
        if (cs.bannerStamped) return;
        // Keep the banner DOWN while its standard is in play (being carried) or already captured —
        // the broken standard is the objective. A returned/lost standard clears this so it re-raises. §2
        if (cs.standardInPlayOrCaptured()) return;
        ChunkPos cc = new ChunkPos(cs.center);
        if (!level.hasChunk(cc.x, cc.z)) return; // never force-load; stamp on the next near pass
        DyeColor dye = DyeColor.byId((int) Math.floorMod(cs.languageSeed, 16));
        BannerBlock bannerBlock = (BannerBlock) BannerBlock.byColor(dye);
        BlockState banner = bannerBlock.defaultBlockState();
        // Candidate columns spiral outward from the bell; the terraced/cluttered plazas real villages
        // sit on mean a fixed-height probe often misses, so each column reads its own ground surface
        // via the heightmap (clamped near the bell's Y — never on a roof or down a cliff).
        int[][] offsets = {
            {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {2, -2}, {-2, 2}, {-2, -2},
            {3, 0}, {-3, 0}, {0, 3}, {0, -3}, {3, 3}, {3, -3}, {-3, 3}, {-3, -3},
            {4, 0}, {-4, 0}, {0, 4}, {0, -4}, {4, 2}, {-4, -2}, {2, -4}, {-2, 4}};
        for (int[] off : offsets) {
            int x = cs.center.getX() + off[0];
            int z = cs.center.getZ() + off[1];
            int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types
                .MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(surfaceY - cs.center.getY()) > 4) continue; // roof / cliff — not the plaza
            BlockPos pos = new BlockPos(x, surfaceY, z);
            if (!level.getBlockState(pos).canBeReplaced()) continue;
            if (!banner.canSurvive(level, pos)) continue;
            int rot = RotationSegment.convertToSegment(yawToward(pos, cs.center));
            level.setBlock(pos, banner.setValue(BannerBlock.ROTATION, rot), 3);
            cs.bannerPos = pos.immutable();
            cs.bannerStamped = true;
            data.setDirty();
            return;
        }
    }

    private static float yawToward(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
    }

    // ─── Economy + evolution (the living economy — CityStateEconomy, plan Phase 3) ─────────────────

    private static void economyTick(ServerLevel level) {
        CityStateData data = CityStateData.get(level);
        if (data.all().isEmpty()) return;
        long now = level.getGameTime();
        SettlementData sd = SettlementData.get(level);
        // The cap on the city-state's own tech: everything the most-advanced settlement has EXCEPT its
        // latest completion (reused from the barbarian model) — so city-states always lag players and
        // never advance an era on their own.
        java.util.Set<String> techCap = BarbarianTech.campKnownTech(sd);
        for (CityState cs : data.all()) {
            cs.lastEconomyTick = now;
            if (cs.isFrozen()) continue; // war (pending/active/captured) freezes the whole economy
            CityStateEconomy.tick(cs, now, techCap);
        }
        data.setDirty();
    }

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

    // ─── Village footprint claiming ──────────────────────────────────────────────────────────────

    /** Claims the whole village: every chunk holding a village POI (homes, meeting-point, job sites)
     *  within range, each expanded by one chunk to bridge gaps + paths, plus the bell ring. Additive
     *  (claims only grow as more of the village loads). Returns true if any new chunk was added. */
    private static boolean claimVillage(ServerLevel level, CityState cs) {
        int before = cs.claimedChunks.size();
        ChunkPos bell = new ChunkPos(cs.center);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) cs.claimedChunks.add(ChunkPos.asLong(bell.x + dx, bell.z + dz));
        }
        level.getPoiManager().getInRange(
            h -> h.is(PoiTypes.HOME) || h.is(PoiTypes.MEETING) || h.is(PoiTypeTags.ACQUIRABLE_JOB_SITE),
            cs.center, VILLAGE_SCAN_RADIUS, PoiManager.Occupancy.ANY)
            .forEach(rec -> {
                ChunkPos pc = new ChunkPos(rec.getPos());
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) cs.claimedChunks.add(ChunkPos.asLong(pc.x + dx, pc.z + dz));
                }
            });
        return cs.claimedChunks.size() != before;
    }

    // ─── Op test helpers ────────────────────────────────────────────────────────────────────────

    /** Op test: detects the nearest village to the player as a city-state, bypassing the scan timer.
     *  Returns the created/existing city-state, or null if no village bell is within range. */
    public static CityState forceDetectNearest(ServerLevel level, ServerPlayer player) {
        CityStateData data = CityStateData.get(level);
        PoiManager poi = level.getPoiManager();
        Optional<BlockPos> bell = poi.findClosest(
            holder -> holder.is(PoiTypes.MEETING), player.blockPosition(), DETECT_RADIUS,
            PoiManager.Occupancy.ANY);
        if (bell.isEmpty()) return null;
        BlockPos center = bell.get();
        CityState existing = data.getByChunk(new ChunkPos(center).toLong());
        if (existing != null) return existing;
        return createCityState(level, data, center);
    }

    /** Removes every city-state record (does NOT remove placed banner blocks). Returns the count. */
    public static int clearAll(ServerLevel level) {
        CityStateData data = CityStateData.get(level);
        int n = data.all().size();
        data.clear();
        return n;
    }
}
