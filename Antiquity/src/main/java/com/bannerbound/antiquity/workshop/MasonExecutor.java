package com.bannerbound.antiquity.workshop;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.masonry.MasonryOutput;
import com.bannerbound.antiquity.masonry.MasonryOutputManager;
import com.bannerbound.antiquity.masonry.StoneFamily;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * The Mason NPC's craft driver at a Mason's Bench — the stone analogue of {@link CarpenterExecutor}.
 * The player path is the headline (the budget + build list + chisel minigame); this is the
 * delegation layer: the mason withdraws a stone family's base block from workshop storage and
 * produces a dressed building block at the data-driven {@code base_cost → yield}. Masonry has
 * <b>no quality</b>, so {@code finish} returns the plain result.
 *
 * <p>It participates in the settlement's production chain via the Stocker surfaces
 * ({@link #missingInputs} = "haul me base stone for what I want to craft", {@link #retainedItems} =
 * "don't haul those back out").
 */
@ApiStatus.Internal
public class MasonExecutor implements WorkExecutor {
    /** XP key — per-profession bucket (Core jobXp map). */
    public static final String XP_KEY = "masonry";
    private static final int TICKS_PER_BEAT = 30;
    private static final int BEATS = 3;

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

    /** Finds a family + output row that yields {@code wanted} and whose base stone storage holds. */
    @Nullable
    private static Craft tryCraftFor(ServerLevel sl, Workshop workshop, BlockPos workBlock, Item wanted) {
        for (StoneFamily fam : StoneFamily.values()) {
            Item base = fam.baseItem();
            for (MasonryOutput o : MasonryOutputManager.all()) {
                Item out = fam.variant(o.variant());
                if (out == null || out != wanted) continue;
                if (!CraftGating.canProduceAt(sl, workBlock, out)) continue;
                if (WorkshopStorage.count(sl, workshop, base) < o.baseCost()) continue;
                List<ItemStack> inputs = new ArrayList<>(1);
                inputs.add(new ItemStack(base, o.baseCost()));
                return new Craft(inputs, new ItemStack(out, o.yield()), BEATS * TICKS_PER_BEAT, BEATS);
            }
        }
        return null;
    }

    @Override
    public List<ItemStack> possibleOutputs(ServerLevel sl, BlockPos workBlock) {
        List<ItemStack> out = new ArrayList<>();
        for (StoneFamily fam : StoneFamily.values()) {
            for (MasonryOutput o : MasonryOutputManager.all()) {
                Item item = fam.variant(o.variant());
                if (item != null && CraftGating.canProduceAt(sl, workBlock, item)) {
                    out.add(new ItemStack(item, o.yield()));
                }
            }
        }
        return out;
    }

    // ── Stocker surfaces (production chain) ──────────────────────────────────────────────────────

    /** Per-wanted-recipe input buffer the stocker tops the workshop up to (≈ this many crafts). */
    private static final int INPUT_BUFFER_CRAFTS = 4;

    private static boolean wants(ServerLevel sl, Settlement settlement, Workshop workshop,
                                 Item result, Set<Item> ordered) {
        return ordered.contains(result)
            || Workshops.wantedByMinStock(sl, settlement, workshop, new ItemStack(result));
    }

    @Override
    public List<ItemStack> missingInputs(ServerLevel sl, Settlement settlement, Workshop workshop,
                                         BlockPos workBlock) {
        return demandStacks(sl, settlement, workshop, workBlock, true);
    }

    @Override
    public List<ItemStack> trueInputDemand(ServerLevel sl, Settlement settlement, Workshop workshop,
                                           BlockPos workBlock) {
        // Production sizing: no rolling buffer — if the base stone has to be chain-crafted, the
        // producer makes only what the orders/min-stock truly need.
        return demandStacks(sl, settlement, workshop, workBlock, false);
    }

    /** Per-input deficit for every wanted output. {@code bufferRaws} = the haul surface (base stone
     *  pre-stocked to {@link #INPUT_BUFFER_CRAFTS} crafts of any min-stock output); without it the
     *  base is sized at the TRUE need (orders + min-stock deficit) for chain production. */
    private List<ItemStack> demandStacks(ServerLevel sl, Settlement settlement, Workshop workshop,
                                         BlockPos workBlock, boolean bufferRaws) {
        Map<Item, Integer> desired = new LinkedHashMap<>();
        for (StoneFamily fam : StoneFamily.values()) {
            Item base = fam.baseItem();
            for (MasonryOutput o : MasonryOutputManager.all()) {
                Item out = fam.variant(o.variant());
                if (out == null || !CraftGating.canProduceAt(sl, workBlock, out)) continue;
                int orders = Workshops.orderedCraftCount(workshop, out);
                if (bufferRaws) {
                    if (orders > 0) addDesired(desired, base, o.baseCost() * orders);
                    if (Workshops.wantedByMinStock(sl, settlement, workshop, new ItemStack(out))) {
                        addDesired(desired, base, o.baseCost() * INPUT_BUFFER_CRAFTS);
                    }
                } else {
                    int need = orders
                        + Workshops.minStockDeficit(sl, settlement, workshop, new ItemStack(out));
                    addDesired(desired, base, o.baseCost() * need);
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

    @Override
    public Set<Item> retainedItems(ServerLevel sl, Settlement settlement, Workshop workshop,
                                   BlockPos workBlock) {
        Set<Item> ordered = new HashSet<>(Workshops.orderedItems(workshop));
        Set<Item> keep = new HashSet<>();
        for (StoneFamily fam : StoneFamily.values()) {
            for (MasonryOutput o : MasonryOutputManager.all()) {
                Item out = fam.variant(o.variant());
                if (out == null || !CraftGating.canProduceAt(sl, workBlock, out)) continue;
                if (wants(sl, settlement, workshop, out, ordered)) keep.add(fam.baseItem());
            }
        }
        return keep;
    }

    @Override
    public void onBeat(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, int beatIndex) {
        sl.playSound(null, workBlock, SoundEvents.STONE_HIT, SoundSource.BLOCKS, 0.8F, 0.9F);
        sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()),
            workBlock.getX() + 0.5, workBlock.getY() + 1.0, workBlock.getZ() + 0.5,
            5, 0.3, 0.1, 0.3, 0.02);
    }

    @Override
    public ItemStack finish(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        sl.playSound(null, workBlock, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.9F, 1.0F);
        return craft.result(); // masonry has no quality — plain result
    }
}
