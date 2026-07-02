package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.SettlementDropFilter;
import com.bannerbound.core.api.settlement.PolicyRegistry;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Effect hook of the {@link PolicyRegistry#PROSPECTING_QUARRY Prospecting Quarry} policy:
 * quarryworkers mining NATURAL stone have a small chance to turn up common raw ore. This is the
 * <b>scarcity floor</b> for ore-poor starts (MINER_PLAN.md phase 2) — it un-softlocks a civ with
 * no ore chunks, but is deliberately worse than trading or working a deposit, so trade leverage
 * survives. The tooltip says "small chance" and never the number.
 *
 * <p>The percentage is NOT the real defense against farming — the <b>per-settlement daily cap</b>
 * is: even a cobblestone-generator + repeat-dig-order farm can't beat a cap. Natural-stone-only
 * ({@code BASE_STONE_OVERWORLD}, which excludes cobblestone and player-processed blocks) closes
 * the cheap generator path on top. Common ores only — <b>iron must never leak through
 * prospecting</b> or it punches a hole in perception-gating.
 *
 * <p>The daily counter is transient (resets on server restart — worth at most one extra cap of
 * ore, not worth Settlement NBT wiring).
 */
@ApiStatus.Internal
public final class ProspectingQuarry {
    /** Chance per natural stone block a stone-tier quarryworker mines. Tooltip says "small". */
    private static final float CHANCE = 0.02f;
    /** Hard per-settlement, per-Minecraft-day ceiling — the real anti-exploit. */
    private static final int DAILY_CAP = 8;
    private static final ResourceLocation RAW_TIN_ID =
        ResourceLocation.fromNamespaceAndPath("bannerboundantiquity", "raw_tin");

    /** settlementId → {dayStamp, found-today}. */
    private static final Map<UUID, long[]> FOUND_TODAY = new ConcurrentHashMap<>();

    private ProspectingQuarry() {
    }

    /**
     * Rolls the prospecting bonus for one mined block. Returns the bonus stack, or
     * {@link ItemStack#EMPTY} when the policy is off, the block isn't raw natural stone, the
     * role isn't stone-tier mining, the day's cap is spent, or the roll misses.
     */
    public static ItemStack tryBonus(ServerLevel sl, Settlement settlement, BlockState mined,
                                     String toolRole) {
        if (settlement == null || !settlement.hasPolicy(PolicyRegistry.PROSPECTING_QUARRY)) {
            return ItemStack.EMPTY;
        }
        if (!"pickaxe".equals(toolRole)) return ItemStack.EMPTY;          // stone-tier mining only
        if (!mined.is(BlockTags.BASE_STONE_OVERWORLD)) return ItemStack.EMPTY; // raw stone, never cobble
        if (!underDailyCap(sl, settlement)) return ItemStack.EMPTY;
        if (sl.random.nextFloat() >= CHANCE) return ItemStack.EMPTY;

        // Common ores only (never iron). Tin joins the pool once the civ knows it; before that,
        // copper carries the whole roll rather than half the hits fizzling.
        List<Item> pool = new ArrayList<>(2);
        addIfKnown(pool, settlement, Items.RAW_COPPER);
        addIfKnown(pool, settlement, BuiltInRegistries.ITEM.getOptional(RAW_TIN_ID).orElse(null));
        if (pool.isEmpty()) return ItemStack.EMPTY;

        spend(sl, settlement);
        return new ItemStack(pool.get(sl.random.nextInt(pool.size())));
    }

    private static void addIfKnown(List<Item> pool, Settlement settlement, Item item) {
        if (item == null) return;
        if (SettlementDropFilter.shouldDrop(settlement, null, new ItemStack(item))) pool.add(item);
    }

    private static long today(ServerLevel sl) {
        return sl.getDayTime() / 24_000L;   // dayTime, so sleeping through the night resets too
    }

    private static boolean underDailyCap(ServerLevel sl, Settlement settlement) {
        long[] e = FOUND_TODAY.get(settlement.id());
        return e == null || e[0] != today(sl) || e[1] < DAILY_CAP;
    }

    private static void spend(ServerLevel sl, Settlement settlement) {
        long day = today(sl);
        FOUND_TODAY.compute(settlement.id(), (id, e) ->
            (e == null || e[0] != day) ? new long[]{day, 1} : new long[]{day, e[1] + 1});
    }
}
