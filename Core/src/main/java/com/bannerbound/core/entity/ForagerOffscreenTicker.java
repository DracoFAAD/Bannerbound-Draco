package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.forager.ForageCategory;
import com.bannerbound.core.api.forager.ForagerHooks;
import com.bannerbound.core.api.research.SettlementDropFilter;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.data.FoodValueLoader;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The forager's "nobody is watching" behaviours, polled from {@code CitizenEntity.aiStep} every
 * 20 ticks (forager-job citizens only) — the direct counterpart to {@link HunterOffscreenTicker}.
 *
 * <h2>Passive yield</h2>
 * When no player is near ({@link CitizenEntity#isAiActive()} false — the activation tier that idles
 * the real roam/gather AI), the forager keeps producing <i>as if</i> it were out in the band: on a
 * randomized cadence it picks one of its enabled-and-unlocked {@link ForageCategory categories} and
 * produces that category's canonical yield (berries, a flower/mushroom, or — for the scavenging
 * category — the fiber + wheat-seed raws via {@link ForagerHooks}), filters it through the settlement
 * known-set like every worker, and inserts it into the drop-off. No block is actually broken — the
 * same simulation-over-simulation trade {@link HunterOffscreenTicker} makes. Unlike the hunter (whose
 * prey is biome-weighted from spawn settings), the forager has no equivalent flora-spawn API, so the
 * yield is canonical-per-category rather than biome-faithful. The moment a player wanders close,
 * {@code isAiActive()} flips and the real {@link ForagerWorkGoal} takes over (this ticker goes dormant).
 *
 * <h2>Dusk teleport home</h2>
 * Foragers roam up to a 64-block leash from their drop-off, so — like hunters — a day's gathering can
 * end a long way from bed. When the evening social window opens and the forager is still far outside
 * the claims, it is teleported to a chunk adjacent to the town hall's, on the side it was returning
 * from, rather than trudge home (or stay stranded for the night while inactive).
 */
@ApiStatus.Internal
public final class ForagerOffscreenTicker {
    /** Average ticks between passive yields. Deliberately conservative (the active forager is a
     *  higher-volume gatherer) so a long player absence doesn't flood the drop-off. BASE + rand(BASE). */
    private static final int PASSIVE_INTERVAL_BASE_TICKS = 600;
    /** Work hours: passive yield only while the real goal would also be working (dawn → the pre-dusk
     *  social window at 10100), matching {@link HunterOffscreenTicker}. */
    private static final long WORK_END_DAYTIME = 10_100L;
    /** Dusk window: from the social-window cutoff until citizens are in bed. */
    private static final long DUSK_TELEPORT_FROM = 10_100L;
    private static final long DUSK_TELEPORT_UNTIL = 13_000L;
    /** "Far from home" — beyond this distance from the town hall (and outside the claims) the dusk
     *  walk home isn't worth simulating; teleport instead. */
    private static final double FAR_FROM_HOME_SQ = 64.0 * 64.0;

    private static final String NEXT_YIELD_TAG = "ForagerPassiveNext";

    /** Canonical flora for the flower/mushroom categories — picked at random when those categories
     *  yield passively (not biome-faithful; the known-set filter drops any the civ can't recognize). */
    private static final Item[] SMALL_FLOWERS = {
        Items.DANDELION, Items.POPPY, Items.AZURE_BLUET, Items.OXEYE_DAISY, Items.CORNFLOWER };
    private static final Item[] TALL_FLOWERS = {
        Items.SUNFLOWER, Items.LILAC, Items.ROSE_BUSH, Items.PEONY };

    private ForagerOffscreenTicker() {
    }

    /** Polled every 20 ticks for forager-job citizens (server side). */
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
        if (citizen.isAiActive()) return;            // a player is watching → the real AI gathers
        if (!citizen.isForagerReady()) return;
        if (citizen.isStaminaExhausted() || citizen.isPregnant() || citizen.isChild()) return;

        long now = sl.getGameTime();
        var data = citizen.getPersistentData();
        if (!data.contains(NEXT_YIELD_TAG)) {
            // First idle tick: schedule the first yield a full interval out (no instant loot the
            // moment the player walks away).
            data.putLong(NEXT_YIELD_TAG, now + rollInterval(citizen));
            return;
        }
        if (now < data.getLong(NEXT_YIELD_TAG)) return;

        Container depot = DropOffContainers.resolveJobDepot(citizen);
        if (depot == null || !DropOffContainers.hasFreeSlot(depot)) return;   // retry next poll

        ForageCategory cat = pickActiveCategory(citizen);
        data.putLong(NEXT_YIELD_TAG, now + rollInterval(citizen));
        if (cat == null) return;   // nothing enabled + unlocked → no yield this round

        List<ItemStack> drops = passiveYieldFor(cat, sl, citizen.getRandom());
        if (drops.isEmpty()) return;   // an unlucky roll (e.g. fiber + seed both missed)
        SettlementDropFilter.filterStacks(citizen.getSettlement(), null, drops);

        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            // Deposited forage feeds the town via the larder now, not a live status bonus (COOKING_PLAN.md Part 1).
            ItemStack leftover = DropOffContainers.insert(depot, drop);
            if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
        }
        citizen.consumeStamina(1);   // same cost as a real harvest (ForagerWorkGoal)
    }

    /** Canonical drops for {@code cat}, mirroring what the active {@link ForagerWorkGoal#harvest}
     *  would produce for that kind of growth. */
    private static List<ItemStack> passiveYieldFor(ForageCategory cat, ServerLevel sl, RandomSource rng) {
        List<ItemStack> out = new ArrayList<>(2);
        switch (cat) {
            case BERRIES -> out.add(new ItemStack(Items.SWEET_BERRIES, 1 + rng.nextInt(2)));
            case SMALL_FLOWERS -> out.add(new ItemStack(SMALL_FLOWERS[rng.nextInt(SMALL_FLOWERS.length)]));
            case TALL_FLOWERS -> out.add(new ItemStack(TALL_FLOWERS[rng.nextInt(TALL_FLOWERS.length)]));
            case MUSHROOMS -> out.add(new ItemStack(rng.nextBoolean() ? Items.RED_MUSHROOM : Items.BROWN_MUSHROOM));
            case VINES -> out.add(new ItemStack(Items.VINE));
            case GRASS -> out.add(new ItemStack(Items.SHORT_GRASS));   // shear yield = the block itself
            case LEAVES -> out.add(new ItemStack(Items.OAK_LEAVES));   // shear yield = the leaf block
            case STICKS_FIBERS -> {
                // The scavenging raws: bias toward grass (fiber + the seeds the farmer chain needs);
                // occasionally leaves (sticks). Reuses the same hook the active harvest does.
                BlockState rep = rng.nextFloat() < 0.7f
                    ? Blocks.SHORT_GRASS.defaultBlockState()
                    : Blocks.OAK_LEAVES.defaultBlockState();
                out.addAll(ForagerHooks.scavenge(sl, rep, rng));
            }
        }
        return out;
    }

    /** A category the forager has both enabled (Job-tab bit) AND research-unlocked, chosen at random;
     *  null when none qualify. Mirrors {@code ForagerWorkGoal.activeCategories}. */
    private static ForageCategory pickActiveCategory(CitizenEntity citizen) {
        Settlement settlement = citizen.getSettlement();
        int enabled = citizen.getForageTargetBits();
        List<ForageCategory> active = new ArrayList<>();
        for (ForageCategory c : ForageCategory.values()) {
            if ((enabled & c.bit()) != 0 && c.isUnlocked(settlement)) active.add(c);
        }
        if (active.isEmpty()) return null;
        return active.get(citizen.getRandom().nextInt(active.size()));
    }

    private static boolean insertedSome(ItemStack original, ItemStack remainder) {
        if (original.isEmpty()) return false;
        return remainder.isEmpty() || remainder.getCount() < original.getCount();
    }

    private static long rollInterval(CitizenEntity citizen) {
        return PASSIVE_INTERVAL_BASE_TICKS + citizen.getRandom().nextInt(PASSIVE_INTERVAL_BASE_TICKS);
    }

    // ─── Dusk teleport home ────────────────────────────────────────────────────────────────────────

    private static void maybeTeleportHome(CitizenEntity citizen, ServerLevel sl) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null || !settlement.hasTownHall()) return;
        BlockPos th = settlement.townHallPos();
        if (citizen.distanceToSqr(th.getX() + 0.5, th.getY(), th.getZ() + 0.5) <= FAR_FROM_HOME_SQ) return;
        // Only rescue foragers stranded OUTSIDE the claims — inside them the normal walk home is short.
        if (SettlementData.get(sl).getByChunk(new ChunkPos(citizen.blockPosition()).toLong()) != null) return;

        // Land in the chunk adjacent to the town hall's, on the side the forager is returning from —
        // reads as "wandering back from the band at dusk" rather than popping into the plaza.
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
        CitizenEntity.tagDeliberateTeleport(citizen); // else a rope near the landing bounces her back
        citizen.setYRot(Mth.wrapDegrees((float) Math.toDegrees(
            Math.atan2(th.getZ() + 0.5 - z, th.getX() + 0.5 - x)) - 90.0F));
    }
}
