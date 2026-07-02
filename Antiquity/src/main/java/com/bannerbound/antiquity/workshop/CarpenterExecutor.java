package com.bannerbound.antiquity.workshop;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.carpentry.CarpentryAssembly;
import com.bannerbound.antiquity.carpentry.CarpentryAssemblyManager;
import com.bannerbound.antiquity.carpentry.CarpentryOutput;
import com.bannerbound.antiquity.carpentry.CarpentryOutputManager;
import com.bannerbound.antiquity.carpentry.WoodFamily;
import com.bannerbound.core.api.research.CraftGating;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.workshop.WorkExecutor;
import com.bannerbound.core.api.workshop.WorkshopStorage;
import com.bannerbound.core.api.workshop.Workshops;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * The Carpenter NPC's craft driver at a Carpenter's Table. The player path is the headline (the
 * budget + build list + saw minigame); this is the "hook now, build later" delegation layer: the
 * carpenter withdraws a wood family's canonical logs from workshop storage and produces a building
 * block at the data-driven {@code log_cost → yield}, or builds an assembly recipe (wooden tools,
 * sticks, ladder, bowl) from stocked planks/sticks. Carpentry has <b>no quality</b>, so {@code finish}
 * returns the plain result — no roll, no {@code TOOL_QUALITY} component. Wood families are limited to
 * the common overworld set for the NPC so its min-stock rows stay manageable; the player picker is
 * unrestricted.
 *
 * <p>It participates in the settlement's production chain via the Stocker surfaces
 * ({@link #missingInputs} = "haul me logs/planks/sticks for what I want to craft",
 * {@link #retainedItems} = "don't haul those back out"); because {@link #possibleOutputs} already
 * lists planks etc., a downstream workshop wanting planks auto-orders them onto this carpenter.
 */
@ApiStatus.Internal
public class CarpenterExecutor implements WorkExecutor {
    /** XP key — per-profession bucket (Core jobXp map). */
    public static final String XP_KEY = "carpentry";
    private static final int TICKS_PER_BEAT = 30;
    private static final int BEATS = 3;

    /** Wood families the NPC will work (canonical logs). The player picker has no such limit. */
    private static final String[] FAMILIES = {
        "minecraft:oak", "minecraft:spruce", "minecraft:birch", "minecraft:jungle",
        "minecraft:acacia", "minecraft:dark_oak", "minecraft:mangrove", "minecraft:cherry"
    };

    @Override
    @Nullable
    public Craft chooseCraft(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock) {
        for (Item wanted : Workshops.orderedItems(workshop)) {
            Craft c = tryCraftFor(sl, workshop, workBlock, wanted);
            if (c != null) return c;
        }
        for (ItemStack possible : possibleOutputs(sl, workBlock)) {
            if (!Workshops.wantedByMinStock(sl, settlement, workshop, possible)) continue;
            Craft c = tryCraftFor(sl, workshop, workBlock, possible.getItem());
            if (c != null) return c;
        }
        return null;
    }

    /** Finds a family + output row that yields {@code wanted} and whose logs storage holds, or an
     *  assembly recipe (wooden tools etc.) whose planks/sticks storage holds. */
    @Nullable
    private static Craft tryCraftFor(ServerLevel sl, Workshop workshop, BlockPos workBlock, Item wanted) {
        for (String key : FAMILIES) {
            WoodFamily fam = WoodFamily.fromKey(key);
            if (fam == null) continue;
            Item log = fam.representativeLog();
            for (CarpentryOutput o : CarpentryOutputManager.all()) {
                Item out = fam.variant(o.variant());
                if (out == null || out != wanted) continue;
                if (!CraftGating.canProduceAt(sl, workBlock, out)) continue;
                if (WorkshopStorage.count(sl, workshop, log) < o.logCost()) continue;
                List<ItemStack> inputs = new ArrayList<>(1);
                inputs.add(new ItemStack(log, o.logCost()));
                return new Craft(inputs, new ItemStack(out, o.yield()), BEATS * TICKS_PER_BEAT, BEATS);
            }
        }
        for (CarpentryAssembly a : CarpentryAssemblyManager.all()) {
            if (a.result() != wanted) continue;
            if (!CraftGating.canProduceAt(sl, workBlock, a.result())) continue;
            List<ItemStack> inputs = resolveInputs(sl, workshop, a);
            if (inputs == null) continue;
            return new Craft(inputs, new ItemStack(a.result(), a.yield()), BEATS * TICKS_PER_BEAT, BEATS);
        }
        return null;
    }

    /** Resolves an assembly recipe's ingredients to concrete stacks the storage holds enough of
     *  (e.g. {@code #planks} → the first stocked plank type), or {@code null} if any is short. */
    @Nullable
    private static List<ItemStack> resolveInputs(ServerLevel sl, Workshop workshop, CarpentryAssembly a) {
        List<ItemStack> inputs = new ArrayList<>(a.ingredients().size());
        for (CarpentryAssembly.Ingredient in : a.ingredients()) {
            Item chosen = null;
            for (Item cand : in.candidates()) {
                if (WorkshopStorage.count(sl, workshop, cand) >= in.count()) {
                    chosen = cand;
                    break;
                }
            }
            if (chosen == null) return null;
            inputs.add(new ItemStack(chosen, in.count()));
        }
        return inputs;
    }

    @Override
    public List<ItemStack> possibleOutputs(ServerLevel sl, BlockPos workBlock) {
        List<ItemStack> out = new ArrayList<>();
        for (String key : FAMILIES) {
            WoodFamily fam = WoodFamily.fromKey(key);
            if (fam == null) continue;
            for (CarpentryOutput o : CarpentryOutputManager.all()) {
                Item item = fam.variant(o.variant());
                if (item != null && CraftGating.canProduceAt(sl, workBlock, item)) {
                    out.add(new ItemStack(item, o.yield()));
                }
            }
        }
        for (CarpentryAssembly a : CarpentryAssemblyManager.all()) {
            if (CraftGating.canProduceAt(sl, workBlock, a.result())) {
                out.add(new ItemStack(a.result(), a.yield()));
            }
        }
        return out;
    }

    // ── Stocker surfaces (production chain) ──────────────────────────────────────────────────────

    /** Per-wanted-recipe input buffer the stocker tops the workshop up to (≈ this many crafts). */
    private static final int INPUT_BUFFER_CRAFTS = 4;

    /** True if {@code result} is something this workshop currently wants to craft: a queued order or
     *  auto-order ({@code ordered}), or a positive min-stock deficit. Mirrors the canonical
     *  {@code wantedRecipes} gate (FletcherExecutor). */
    private static boolean wants(ServerLevel sl, Settlement settlement, Workshop workshop,
                                 Item result, Set<Item> ordered) {
        return ordered.contains(result)
            || Workshops.wantedByMinStock(sl, settlement, workshop, new ItemStack(result));
    }

    /**
     * The Stocker's SUPPLY surface: for every craft this carpenter currently WANTS, the deficit
     * between a small input buffer and what storage holds — logs for family outputs, the stocked (or
     * default) plank/stick for assembly recipes. Concrete items only (the haul system is item-keyed,
     * so a {@code #planks} ingredient resolves to a concrete plank via {@link #supplyTarget}).
     */
    @Override
    public List<ItemStack> missingInputs(ServerLevel sl, Settlement settlement, Workshop workshop,
                                         BlockPos workBlock) {
        return demandStacks(sl, settlement, workshop, workBlock, true);
    }

    @Override
    public List<ItemStack> trueInputDemand(ServerLevel sl, Settlement settlement, Workshop workshop,
                                           BlockPos workBlock) {
        // Production sizing: no rolling buffer — a chain-crafted input (e.g. planks for an assembly,
        // which this same carpenter makes) is ordered at the true need, never a buffer's worth.
        return demandStacks(sl, settlement, workshop, workBlock, false);
    }

    /** Per-input deficit for every wanted output/assembly. {@code bufferRaws} = the haul surface
     *  (logs/planks pre-stocked to {@link #INPUT_BUFFER_CRAFTS} crafts of a min-stock product);
     *  without it inputs are sized at the TRUE need (orders + min-stock deficit) for chain
     *  production. */
    private List<ItemStack> demandStacks(ServerLevel sl, Settlement settlement, Workshop workshop,
                                         BlockPos workBlock, boolean bufferRaws) {
        Map<Item, Integer> desired = new LinkedHashMap<>();
        for (String key : FAMILIES) {
            WoodFamily fam = WoodFamily.fromKey(key);
            if (fam == null) continue;
            Item log = fam.representativeLog();
            for (CarpentryOutput o : CarpentryOutputManager.all()) {
                Item out = fam.variant(o.variant());
                if (out == null || !CraftGating.canProduceAt(sl, workBlock, out)) continue;
                int orders = Workshops.orderedCraftCount(workshop, out);
                if (bufferRaws) {
                    if (orders > 0) addDesired(desired, log, o.logCost() * orders);
                    if (Workshops.wantedByMinStock(sl, settlement, workshop, new ItemStack(out))) {
                        addDesired(desired, log, o.logCost() * INPUT_BUFFER_CRAFTS);
                    }
                } else {
                    int need = orders
                        + Workshops.minStockDeficit(sl, settlement, workshop, new ItemStack(out));
                    addDesired(desired, log, o.logCost() * need);
                }
            }
        }
        for (CarpentryAssembly a : CarpentryAssemblyManager.all()) {
            if (!CraftGating.canProduceAt(sl, workBlock, a.result())) continue;
            int orders = Workshops.orderedCraftCount(workshop, a.result());
            boolean minStockWanted = Workshops.wantedByMinStock(
                sl, settlement, workshop, new ItemStack(a.result()));
            int need = bufferRaws ? 0
                : orders + Workshops.minStockDeficit(sl, settlement, workshop, new ItemStack(a.result()));
            if (bufferRaws ? (orders <= 0 && !minStockWanted) : need <= 0) continue;
            for (CarpentryAssembly.Ingredient in : a.ingredients()) {
                Item target = supplyTarget(sl, workshop, in);
                if (target == null) continue;
                if (bufferRaws) {
                    if (orders > 0) addDesired(desired, target, in.count() * orders);
                    if (minStockWanted) addDesired(desired, target, in.count() * INPUT_BUFFER_CRAFTS);
                } else {
                    addDesired(desired, target, in.count() * need);
                }
            }
        }
        Map<Item, Integer> deficit = deficits(sl, workshop, desired);
        List<ItemStack> out = new ArrayList<>(deficit.size());
        for (Map.Entry<Item, Integer> e : deficit.entrySet()) {
            out.add(new ItemStack(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static void addDesired(Map<Item, Integer> desired, Item item, int count) {
        if (count > 0) desired.merge(item, count, Integer::sum);
    }

    private static Map<Item, Integer> deficits(ServerLevel sl, Workshop workshop,
                                               Map<Item, Integer> desired) {
        Map<Item, Integer> deficit = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : desired.entrySet()) {
            int have = WorkshopStorage.count(sl, workshop, e.getKey());
            if (have < e.getValue()) deficit.put(e.getKey(), e.getValue() - have);
        }
        return deficit;
    }

    /** The concrete item to request for a tag/item ingredient: the candidate the storage already
     *  holds the most of (top it up), else the first candidate as a deterministic default. */
    @Nullable
    private static Item supplyTarget(ServerLevel sl, Workshop workshop, CarpentryAssembly.Ingredient in) {
        List<Item> candidates = in.candidates();
        if (candidates.isEmpty()) return null;
        Item best = null;
        int bestCount = 0;
        for (Item c : candidates) {
            int n = WorkshopStorage.count(sl, workshop, c);
            if (n > bestCount) {
                bestCount = n;
                best = c;
            }
        }
        return best != null ? best : candidates.get(0);
    }

    /**
     * The Stocker's KEEP surface: every input the carpenter's wanted crafts consume — so the stocker
     * never hauls them back out. For an assembly tag ingredient ({@code #planks}) this keeps every
     * candidate the storage actually holds (so a half-stack of, say, birch planks isn't yanked).
     */
    @Override
    public Set<Item> retainedItems(ServerLevel sl, Settlement settlement, Workshop workshop,
                                   BlockPos workBlock) {
        Set<Item> ordered = new HashSet<>(Workshops.orderedItems(workshop));
        Set<Item> keep = new HashSet<>();
        for (String key : FAMILIES) {
            WoodFamily fam = WoodFamily.fromKey(key);
            if (fam == null) continue;
            for (CarpentryOutput o : CarpentryOutputManager.all()) {
                Item out = fam.variant(o.variant());
                if (out == null || !CraftGating.canProduceAt(sl, workBlock, out)) continue;
                if (wants(sl, settlement, workshop, out, ordered)) keep.add(fam.representativeLog());
            }
        }
        for (CarpentryAssembly a : CarpentryAssemblyManager.all()) {
            if (!CraftGating.canProduceAt(sl, workBlock, a.result())) continue;
            if (!wants(sl, settlement, workshop, a.result(), ordered)) continue;
            for (CarpentryAssembly.Ingredient in : a.ingredients()) {
                for (Item c : in.candidates()) {
                    if (WorkshopStorage.count(sl, workshop, c) > 0) keep.add(c);
                }
            }
        }
        return keep;
    }

    @Override
    public void onBeat(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, int beatIndex) {
        sl.playSound(null, workBlock, BannerboundAntiquity.SAW_SOUND.get(),
            SoundSource.BLOCKS, 0.8F, 1.0F);
        sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_PLANKS.defaultBlockState()),
            workBlock.getX() + 0.5, workBlock.getY() + 1.0, workBlock.getZ() + 0.5,
            5, 0.3, 0.1, 0.3, 0.02);
    }

    @Override
    public ItemStack finish(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        sl.playSound(null, workBlock, BannerboundAntiquity.SAW_DONE_SOUND.get(),
            SoundSource.BLOCKS, 0.9F, 1.0F);
        return craft.result(); // carpentry has no quality — plain result
    }
}
