package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.SettlementDropFilter;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * The hunter's two "nobody is watching" behaviours, polled from {@code CitizenEntity.aiStep}
 * every 20 ticks (hunter-job citizens only):
 *
 * <h2>Passive yield</h2>
 * When no player is near ({@link CitizenEntity#isAiActive()} false — the activation tier that
 * idles the real stalk/chase AI), the hunter keeps producing <i>as if</i> it were out hunting:
 * on a randomized cadence it picks a huntable species <b>weighted by what actually spawns in the
 * wild band's biome</b> (a hunter beside a jungle yields jungle game), rolls that animal's death
 * loot table, filters it through the settlement known-set like every worker, and inserts it into
 * the drop-off. No animal is actually killed — it's the same simulation-over-simulation trade the
 * caravan system makes. The moment a player wanders close, {@code isAiActive()} flips and the
 * real {@link HunterWorkGoal} takes over seamlessly (this ticker goes dormant).
 *
 * <h2>Dusk teleport home</h2>
 * Hunters trip farther out than any other worker, so a day's hunt can end a long walk from bed.
 * When the evening social window opens and the hunter is still far outside the claims, it is
 * teleported to a chunk <b>adjacent to</b> the town hall's chunk (just outside the heart of the
 * settlement, on the side it was returning from) — close enough to stroll in, socialize, and
 * sleep, without an hours-long trudge or a night stranded in the wilds.
 */
@ApiStatus.Internal
public final class HunterOffscreenTicker {
    /** Average ticks between passive yields — tuned to roughly a real hunt's kill cadence
     *  (roam + stalk + chase + kill ≈ one to two minutes). Actual spacing is BASE + rand(BASE). */
    private static final int PASSIVE_INTERVAL_BASE_TICKS = 1600;
    /** Band sampling for the passive biome pick — mirrors the real goal's trip range. */
    private static final int BAND_SAMPLE_RADIUS = 80;
    /** Work hours: passive yield only while the real goal would also be working (dawn → the
     *  pre-dusk social window at 10100; see WorkGoal.isAfternoonGathering). */
    private static final long WORK_END_DAYTIME = 10_100L;
    /** Dusk window: from the social-window cutoff until citizens are in bed. */
    private static final long DUSK_TELEPORT_FROM = 10_100L;
    private static final long DUSK_TELEPORT_UNTIL = 13_000L;
    /** "Far from home" — beyond this distance from the town hall (and outside the claims) the
     *  dusk walk home isn't worth simulating; teleport instead. */
    private static final double FAR_FROM_HOME_SQ = 64.0 * 64.0;

    private static final String NEXT_YIELD_TAG = "HunterPassiveNext";

    private HunterOffscreenTicker() {
    }

    /** Polled every 20 ticks for hunter-job citizens (server side). */
    public static void tick(CitizenEntity citizen, ServerLevel sl) {
        long dayTime = sl.getDayTime() % 24_000L;
        if (dayTime >= DUSK_TELEPORT_FROM && dayTime < DUSK_TELEPORT_UNTIL) {
            maybeTeleportHome(citizen, sl);
            return;   // evening — no yield rolls during the social/sleep window
        }
        if (dayTime < WORK_END_DAYTIME) {
            maybePassiveYield(citizen, sl);
        }
    }

    // ─── Passive yield ─────────────────────────────────────────────────────────────────────────────

    private static void maybePassiveYield(CitizenEntity citizen, ServerLevel sl) {
        if (citizen.isAiActive()) return;            // a player is watching → the real AI hunts
        if (!citizen.isGatherJobReady(HunterWorkGoal.JOB_TYPE_ID)) return;
        if (citizen.isStaminaExhausted() || citizen.isPregnant() || citizen.isChild()) return;

        long now = sl.getGameTime();
        var data = citizen.getPersistentData();
        if (!data.contains(NEXT_YIELD_TAG)) {
            // First idle tick: schedule the first yield a full interval out (no instant meat the
            // moment the player walks away).
            data.putLong(NEXT_YIELD_TAG, now + rollInterval(citizen));
            return;
        }
        if (now < data.getLong(NEXT_YIELD_TAG)) return;

        Container depot = DropOffContainers.resolveJobDepot(citizen);
        if (depot == null || !DropOffContainers.hasFreeSlot(depot)) return;   // retry next poll

        EntityType<?> prey = pickBiomePrey(citizen, sl);
        data.putLong(NEXT_YIELD_TAG, now + rollInterval(citizen));
        if (prey == null) return;   // nothing huntable spawns around here → no yield this round

        List<ItemStack> drops = rollLoot(sl, citizen, prey);
        SettlementDropFilter.filterStacks(citizen.getSettlement(),
            BuiltInRegistries.ENTITY_TYPE.getKey(prey), drops);
        com.bannerbound.core.api.research.InsightManager.recordEvent(
            sl.getServer(), citizen.getSettlement(), "kill_entity",
            com.bannerbound.core.api.research.InsightManager.matcherFor(prey), 1);
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            // Deposited meat feeds the town via the larder now, not a live status bonus (COOKING_PLAN.md Part 1).
            ItemStack leftover = DropOffContainers.insert(depot, drop);
            if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
        }
        citizen.consumeStamina(8);   // same cost as a real kill (HunterWorkGoal.STAMINA_PER_KILL)
    }

    private static boolean insertedSome(ItemStack original, ItemStack remainder) {
        if (original.isEmpty()) return false;
        return remainder.isEmpty() || remainder.getCount() < original.getCount();
    }

    private static long rollInterval(CitizenEntity citizen) {
        return PASSIVE_INTERVAL_BASE_TICKS + citizen.getRandom().nextInt(PASSIVE_INTERVAL_BASE_TICKS);
    }

    /**
     * A huntable, player-enabled species weighted by the CREATURE spawn list of the biome at a
     * sampled point in the wild band around the drop-off — so the passive yield matches the local
     * fauna (pigs by the jungle, sheep on the plains). Falls back to the hunter's own biome when
     * no band point is found. Null when nothing huntable spawns there.
     */
    private static EntityType<?> pickBiomePrey(CitizenEntity citizen, ServerLevel sl) {
        BlockPos sample = sampleBandPoint(citizen, sl);
        if (sample == null) sample = citizen.blockPosition();
        List<MobSpawnSettings.SpawnerData> candidates = new ArrayList<>();
        int totalWeight = 0;
        for (MobSpawnSettings.SpawnerData d
                : sl.getBiome(sample).value().getMobSettings().getMobs(MobCategory.CREATURE).unwrap()) {
            if (!d.type.is(HunterWorkGoal.HUNTABLE_TAG)) continue;
            if (!citizen.isHunterPreyEnabled(d.type)) continue;
            candidates.add(d);
            totalWeight += d.getWeight().asInt();
        }
        if (candidates.isEmpty() || totalWeight <= 0) return null;
        int roll = citizen.getRandom().nextInt(totalWeight);
        for (MobSpawnSettings.SpawnerData d : candidates) {
            roll -= d.getWeight().asInt();
            if (roll < 0) return d.type;
        }
        return candidates.get(candidates.size() - 1).type;
    }

    /** A surface point in unclaimed land near the drop-off — where the hunter WOULD be hunting.
     *  Loaded-chunk checks keep this safe when the band has unloaded behind the player. */
    private static BlockPos sampleBandPoint(CitizenEntity citizen, ServerLevel sl) {
        Settlement settlement = citizen.getSettlement();
        BlockPos anchor = citizen.getDropOff() != null ? citizen.getDropOff() : citizen.blockPosition();
        if (settlement == null) return null;
        for (int attempt = 0; attempt < 8; attempt++) {
            int x = anchor.getX() + citizen.getRandom().nextInt(BAND_SAMPLE_RADIUS * 2 + 1) - BAND_SAMPLE_RADIUS;
            int z = anchor.getZ() + citizen.getRandom().nextInt(BAND_SAMPLE_RADIUS * 2 + 1) - BAND_SAMPLE_RADIUS;
            if (!sl.isLoaded(new BlockPos(x, anchor.getY(), z))) continue;
            if (SettlementData.get(sl).getByChunk(ChunkPos.asLong(x >> 4, z >> 4)) != null) continue;
            return new BlockPos(x, sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        }
        return null;
    }

    /** Rolls {@code prey}'s death loot table against a transient (never-added) carcass entity —
     *  the same LootParams shape the herder's cull uses, no real animal involved. */
    private static List<ItemStack> rollLoot(ServerLevel sl, CitizenEntity citizen, EntityType<?> prey) {
        List<ItemStack> out = new ArrayList<>();
        Entity carcass = prey.create(sl);
        if (carcass == null) return out;
        try {
            carcass.moveTo(citizen.getX(), citizen.getY(), citizen.getZ(), 0.0F, 0.0F);
            ResourceKey<LootTable> key = prey.getDefaultLootTable();
            LootTable table = sl.getServer().reloadableRegistries().getLootTable(key);
            LootParams params = new LootParams.Builder(sl)
                .withParameter(LootContextParams.THIS_ENTITY, carcass)
                .withParameter(LootContextParams.ORIGIN, carcass.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE, sl.damageSources().generic())
                .create(LootContextParamSets.ENTITY);
            out.addAll(table.getRandomItems(params));
        } finally {
            carcass.discard();
        }
        return out;
    }

    // ─── Dusk teleport home ────────────────────────────────────────────────────────────────────────

    private static void maybeTeleportHome(CitizenEntity citizen, ServerLevel sl) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null || !settlement.hasTownHall()) return;
        BlockPos th = settlement.townHallPos();
        if (citizen.distanceToSqr(th.getX() + 0.5, th.getY(), th.getZ() + 0.5) <= FAR_FROM_HOME_SQ) return;
        // Only rescue hunters stranded OUTSIDE the claims — inside them the normal walk home is short.
        if (SettlementData.get(sl).getByChunk(new ChunkPos(citizen.blockPosition()).toLong()) != null) return;

        // Land in the chunk adjacent to the town hall's, on the side the hunter is returning from —
        // reads as "emerging from the wilds at dusk" rather than popping into the plaza.
        ChunkPos home = new ChunkPos(th);
        int dx = Integer.signum(citizen.blockPosition().getX() - th.getX());
        int dz = Integer.signum(citizen.blockPosition().getZ() - th.getZ());
        if (dx == 0 && dz == 0) dx = 1;
        ChunkPos target = new ChunkPos(home.x + dx, home.z + dz);
        int x = target.getMiddleBlockX();
        int z = target.getMiddleBlockZ();
        int y = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        citizen.getNavigation().stop();
        citizen.teleportTo(x + 0.5, y, z + 0.5);
        CitizenEntity.tagDeliberateTeleport(citizen); // else a rope near the landing bounces him back
        citizen.setYRot(Mth.wrapDegrees((float) Math.toDegrees(
            Math.atan2(th.getZ() + 0.5 - z, th.getX() + 0.5 - x)) - 90.0F));
    }
}
