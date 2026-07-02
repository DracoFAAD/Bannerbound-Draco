package com.bannerbound.antiquity.workshop;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Fletching;
import com.bannerbound.antiquity.block.entity.FletchingStationBlockEntity;
import com.bannerbound.antiquity.item.ArrowParts;
import com.bannerbound.antiquity.recipe.ArrowPart;
import com.bannerbound.antiquity.recipe.FletchingRecipe;
import com.bannerbound.antiquity.recipe.FletchingRecipeManager;
import com.bannerbound.antiquity.recipe.ModularArrow;
import com.bannerbound.core.api.quality.QualityMath;
import com.bannerbound.core.api.quality.QualityTier;
import com.bannerbound.core.api.research.CraftGating;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.workshop.WorkExecutor;
import com.bannerbound.core.api.workshop.WorkshopStorage;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * The Fletcher's craft driver at a Fletching Station: picks any researched fletching recipe whose
 * ingredients the workshop storage holds, places the REAL items on the station pile (spectators
 * watch them appear), plays one stretch beat per recipe stretch (the player minigame's sound and
 * particles), and finishes by simulating the per-stretch scores through the SAME server-side
 * {@code QualityMath} path players use. Phase 2 simulates a journeyman's spread (mean ~70); the
 * Phase-3 NPC experience system replaces that constant with an XP-driven mean/variance.
 */
@ApiStatus.Internal
public class FletcherExecutor implements WorkExecutor {
    /** Ticks per simulated stretch (matches the feel of a deliberate player rep). */
    private static final int TICKS_PER_STRETCH = 36;
    /** XP key — per-profession, so a retrained fletcher remembers fletching (Core jobXp map). */
    public static final String XP_KEY = "fletchery";

    @Override
    @Nullable
    public Craft chooseCraft(ServerLevel sl, com.bannerbound.core.api.settlement.Settlement settlement,
                             Workshop workshop, BlockPos workBlock) {
        // Player orders first (FIFO; orders outrank + ignore the min-stock governor; an order
        // whose ingredients are missing is skipped, never blocking the rest of the queue).
        for (net.minecraft.world.item.Item wanted
                : com.bannerbound.core.api.workshop.Workshops.orderedItems(workshop)) {
            if (wanted == BannerboundAntiquity.ARROW.get()) {
                Craft c = tryModularArrow(sl, workshop, workBlock);
                if (c != null) return c;
                continue;
            }
            for (FletchingRecipe recipe : FletchingRecipeManager.all()) {
                if (recipe.result().getItem() != wanted) continue;
                Craft c = tryCraft(sl, workshop, workBlock, recipe);
                if (c != null) return c;
            }
        }
        // Then positive min-stock deficits — the modular arrow first, then JSON recipes (the bow).
        if (com.bannerbound.core.api.workshop.Workshops.wantedByMinStock(
                sl, settlement, workshop, new ItemStack(BannerboundAntiquity.ARROW.get()))) {
            Craft c = tryModularArrow(sl, workshop, workBlock);
            if (c != null) return c;
        }
        for (FletchingRecipe recipe : FletchingRecipeManager.all()) {
            if (!com.bannerbound.core.api.workshop.Workshops.wantedByMinStock(
                    sl, settlement, workshop, recipe.result())) continue;
            Craft c = tryCraft(sl, workshop, workBlock, recipe);
            if (c != null) return c;
        }
        return null;
    }

    /** Assembles a batch of modular arrows from the BEST parts the workshop currently holds (so an
     *  NPC consumes player-stocked metal heads/ingots before the basic flint/wood/feather the stocker
     *  auto-supplies). One tip + one shaft + one back → a batch of {@link ModularArrow#BATCH}; null if
     *  any part category is empty or the arrow isn't researched here. */
    @Nullable
    private Craft tryModularArrow(ServerLevel sl, Workshop workshop, BlockPos workBlock) {
        if (!CraftGating.canProduceAt(sl, workBlock, BannerboundAntiquity.ARROW.get())) return null;
        ArrowPart tip = firstAvailable(sl, workshop, ArrowPart.SLOT_TIP);
        ArrowPart shaft = firstAvailable(sl, workshop, ArrowPart.SLOT_SHAFT);
        ArrowPart back = firstAvailable(sl, workshop, ArrowPart.SLOT_BACK);
        if (tip == null || shaft == null || back == null) return null;
        List<ItemStack> inputs = List.of(
            new ItemStack(tip.ingredient(), 1),
            new ItemStack(shaft.ingredient(), 1),
            new ItemStack(back.ingredient(), 1));
        int beats = 3;
        return new Craft(inputs,
            ArrowParts.makeArrow(tip.material(), shaft.material(), back.material(), ModularArrow.BATCH),
            beats * TICKS_PER_STRETCH, beats);
    }

    /** The best-priority part of a slot whose ingredient the workshop storage holds, or null. */
    @Nullable
    private static ArrowPart firstAvailable(ServerLevel sl, Workshop workshop, String slot) {
        for (ArrowPart p : ArrowParts.sorted(slot)) {
            if (WorkshopStorage.count(sl, workshop, p.ingredient()) > 0) return p;
        }
        return null;
    }

    /** Crafts the stocker keeps an input buffer for, per wanted recipe. */
    private static final int INPUT_BUFFER_CRAFTS = 4;

    /** Recipes this workshop currently WANTS (queued orders + positive min-stock deficits),
     *  gating applied, input availability ignored — the stocker's planning view. */
    private static List<FletchingRecipe> wantedRecipes(ServerLevel sl,
            com.bannerbound.core.api.settlement.Settlement settlement,
            Workshop workshop, BlockPos workBlock) {
        List<FletchingRecipe> out = new ArrayList<>();
        for (net.minecraft.world.item.Item wanted
                : com.bannerbound.core.api.workshop.Workshops.orderedItems(workshop)) {
            for (FletchingRecipe r : FletchingRecipeManager.all()) {
                if (r.result().getItem() == wanted
                        && CraftGating.canProduceAt(sl, workBlock, r.result().getItem())
                        && !out.contains(r)) {
                    out.add(r);
                }
            }
        }
        for (FletchingRecipe r : FletchingRecipeManager.all()) {
            if (!CraftGating.canProduceAt(sl, workBlock, r.result().getItem())) continue;
            if (!com.bannerbound.core.api.workshop.Workshops.wantedByMinStock(
                    sl, settlement, workshop, r.result())) continue;
            if (!out.contains(r)) out.add(r);
        }
        return out;
    }

    @Override
    public List<ItemStack> missingInputs(ServerLevel sl,
            com.bannerbound.core.api.settlement.Settlement settlement,
            Workshop workshop, BlockPos workBlock) {
        return demandStacks(sl, settlement, workshop, workBlock, true);
    }

    @Override
    public List<ItemStack> trueInputDemand(ServerLevel sl,
            com.bannerbound.core.api.settlement.Settlement settlement,
            Workshop workshop, BlockPos workBlock) {
        // Production sizing: no rolling buffer — a chain producer (e.g. the general-crafts stone
        // making plant string) crafts only what the bow orders truly need, never a buffer's worth.
        return demandStacks(sl, settlement, workshop, workBlock, false);
    }

    /** Per-input deficit for every wanted recipe. {@code bufferRaws} = the haul surface (inputs
     *  pre-stocked to {@link #INPUT_BUFFER_CRAFTS} crafts); without it inputs are sized at the TRUE
     *  need (orders + min-stock deficit) for chain production. */
    private List<ItemStack> demandStacks(ServerLevel sl,
            com.bannerbound.core.api.settlement.Settlement settlement,
            Workshop workshop, BlockPos workBlock, boolean bufferRaws) {
        java.util.Map<net.minecraft.world.item.Item, Integer> desired = new java.util.LinkedHashMap<>();
        for (FletchingRecipe r : wantedRecipes(sl, settlement, workshop, workBlock)) {
            int orders = com.bannerbound.core.api.workshop.Workshops
                .orderedCraftCount(workshop, r.result().getItem());
            int crafts = bufferRaws
                ? (orders > 0 ? orders
                    : com.bannerbound.core.api.workshop.Workshops
                        .wantedByMinStock(sl, settlement, workshop, r.result()) ? INPUT_BUFFER_CRAFTS : 0)
                : orders + com.bannerbound.core.api.workshop.Workshops
                    .minStockDeficit(sl, settlement, workshop, r.result());
            if (crafts <= 0) continue;
            for (FletchingRecipe.Ing ing : r.ingredients()) {
                desired.merge(ing.item(), ing.count() * crafts, Integer::sum);
            }
        }
        java.util.Map<net.minecraft.world.item.Item, Integer> deficit =
            deficits(sl, workshop, desired);
        List<ItemStack> out = new ArrayList<>(deficit.size());
        for (var e : deficit.entrySet()) out.add(new ItemStack(e.getKey(), e.getValue()));
        out.addAll(arrowPartDemand(sl, settlement, workshop, workBlock, bufferRaws));
        return out;
    }

    /** Supply surface for the modular arrow: the BASIC flint/wood/feather parts the stocker should
     *  haul in to keep a hands-off settlement producing arrows. Sized by CRAFTS (each batch = one part
     *  of each), and reduced by however many batches the parts ALREADY in storage can make — so the
     *  stocker doesn't double-haul when the player has stocked metal parts of their own. */
    private List<ItemStack> arrowPartDemand(ServerLevel sl,
            com.bannerbound.core.api.settlement.Settlement settlement,
            Workshop workshop, BlockPos workBlock, boolean bufferRaws) {
        net.minecraft.world.item.Item arrow = BannerboundAntiquity.ARROW.get();
        if (!CraftGating.canProduceAt(sl, workBlock, arrow)) return List.of();
        int orders = com.bannerbound.core.api.workshop.Workshops.orderedCraftCount(workshop, arrow);
        boolean minWanted = com.bannerbound.core.api.workshop.Workshops
            .wantedByMinStock(sl, settlement, workshop, new ItemStack(arrow));
        int wantedArrows = bufferRaws
            ? (orders > 0 ? orders : (minWanted ? ModularArrow.BATCH : 0))
            : orders + com.bannerbound.core.api.workshop.Workshops
                .minStockDeficit(sl, settlement, workshop, new ItemStack(arrow));
        if (wantedArrows <= 0) return List.of();
        int neededCrafts = (wantedArrows + ModularArrow.BATCH - 1) / ModularArrow.BATCH;
        int makeable = Math.min(Math.min(
                categoryCount(sl, workshop, ArrowPart.SLOT_TIP),
                categoryCount(sl, workshop, ArrowPart.SLOT_SHAFT)),
            categoryCount(sl, workshop, ArrowPart.SLOT_BACK));
        int shortfall = Math.max(0, neededCrafts - makeable);
        if (shortfall <= 0) return List.of();
        // Auto-supply the BASIC default parts. If a default isn't defined, supply nothing for that slot.
        net.minecraft.world.item.Item tipItem = ArrowParts.tipItem(ArrowParts.DEFAULT_TIP);
        net.minecraft.world.item.Item shaftItem = ArrowParts.shaftItem(ArrowParts.DEFAULT_SHAFT);
        net.minecraft.world.item.Item backItem = ArrowParts.backItem(ArrowParts.DEFAULT_BACK);
        List<ItemStack> out = new ArrayList<>(3);
        if (tipItem != null) out.add(new ItemStack(tipItem, shortfall));
        if (shaftItem != null) out.add(new ItemStack(shaftItem, shortfall));
        if (backItem != null) out.add(new ItemStack(backItem, shortfall));
        return out;
    }

    /** Total of all of a part slot's ingredient items currently in workshop storage. */
    private static int categoryCount(ServerLevel sl, Workshop workshop, String slot) {
        int n = 0;
        for (ArrowPart p : ArrowParts.sorted(slot)) n += WorkshopStorage.count(sl, workshop, p.ingredient());
        return n;
    }

    private static java.util.Map<net.minecraft.world.item.Item, Integer> deficits(
            ServerLevel sl, Workshop workshop,
            java.util.Map<net.minecraft.world.item.Item, Integer> desired) {
        java.util.Map<net.minecraft.world.item.Item, Integer> deficit =
            new java.util.LinkedHashMap<>();
        for (var e : desired.entrySet()) {
            int have = WorkshopStorage.count(sl, workshop, e.getKey());
            if (have < e.getValue()) deficit.put(e.getKey(), e.getValue() - have);
        }
        return deficit;
    }

    @Override
    public java.util.Set<net.minecraft.world.item.Item> retainedItems(ServerLevel sl,
            com.bannerbound.core.api.settlement.Settlement settlement,
            Workshop workshop, BlockPos workBlock) {
        java.util.Set<net.minecraft.world.item.Item> keep = new java.util.HashSet<>();
        for (FletchingRecipe r : wantedRecipes(sl, settlement, workshop, workBlock)) {
            for (FletchingRecipe.Ing ing : r.ingredients()) keep.add(ing.item());
        }
        // If arrows are wanted, keep EVERY arrow part — so the stocker never hauls out the metal
        // heads/ingots a player stocked to steer the Fletcher toward fancier arrows.
        if (com.bannerbound.core.api.workshop.Workshops
                .wantedByMinStock(sl, settlement, workshop, new ItemStack(BannerboundAntiquity.ARROW.get()))
            || com.bannerbound.core.api.workshop.Workshops
                .orderedCraftCount(workshop, BannerboundAntiquity.ARROW.get()) > 0) {
            keep.addAll(ArrowParts.allPartItems());
        }
        return keep;
    }

    /** Gating + ingredient-availability check for one recipe; null when not craftable right now. */
    @Nullable
    private static Craft tryCraft(ServerLevel sl, Workshop workshop, BlockPos workBlock,
                                  FletchingRecipe recipe) {
        if (!CraftGating.canProduceAt(sl, workBlock, recipe.result().getItem())) return null;
        List<ItemStack> inputs = new ArrayList<>();
        for (FletchingRecipe.Ing ing : recipe.ingredients()) {
            if (WorkshopStorage.count(sl, workshop, ing.item()) < ing.count()) return null;
            inputs.add(new ItemStack(ing.item(), ing.count()));
        }
        int beats = Math.max(1, recipe.stretches());
        return new Craft(inputs, recipe.result().copy(), beats * TICKS_PER_STRETCH, beats);
    }

    @Override
    public List<ItemStack> possibleOutputs(ServerLevel sl, BlockPos workBlock) {
        List<ItemStack> out = new ArrayList<>();
        for (FletchingRecipe recipe : FletchingRecipeManager.all()) {
            if (CraftGating.canProduceAt(sl, workBlock, recipe.result().getItem())) {
                out.add(recipe.result().copy());
            }
        }
        // The modular arrow is ordered/min-stocked as ONE generic "Arrow" (a bare stack, so it reads
        // "Arrow" not "Flint Arrow"); the Fletcher assembles whichever variant the stocked parts allow.
        if (CraftGating.canProduceAt(sl, workBlock, BannerboundAntiquity.ARROW.get())) {
            out.add(new ItemStack(BannerboundAntiquity.ARROW.get()));
        }
        return out;
    }

    /** A batch craft fulfills its whole output count of queued arrow units (one craft = a batch). */
    @Override
    public int fulfilledOrderUnits(ServerLevel sl, BlockPos workBlock, Craft craft, ItemStack output) {
        return Math.max(1, output.getCount());
    }

    @Override
    public void onStart(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        // Put the real items on the table, one by one — same pile players use, same renderer.
        if (sl.getBlockEntity(workBlock) instanceof FletchingStationBlockEntity be) {
            for (ItemStack input : craft.inputs()) {
                for (int i = 0; i < input.getCount(); i++) {
                    be.insertOne(input.copyWithCount(1), Direction.NORTH);
                }
            }
        }
    }

    @Override
    public void onBeat(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, int beatIndex) {
        sl.playSound(null, workBlock, BannerboundAntiquity.FLETCHING_STRETCH_SOUND.get(),
            SoundSource.BLOCKS, 0.8F, 1.0F);
        sl.sendParticles(ParticleTypes.CRIT,
            workBlock.getX() + 0.5, workBlock.getY() + 1.0, workBlock.getZ() + 0.5,
            3, 0.25, 0.15, 0.25, 0.0);
    }

    @Override
    public ItemStack finish(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        if (sl.getBlockEntity(workBlock) instanceof FletchingStationBlockEntity be) {
            be.consumePile();
        }
        // Simulate one score per stretch through the same XP-driven curve every crafter NPC uses
        // (QualityMath.simulateNpcTier): novices roll Crude/Standard, veterans reliable Fine and
        // regular MASTERWORK — the NPC-only tier.
        QualityTier tier = QualityMath.simulateNpcTier(
            sl.random, citizen.getJobXp(XP_KEY), Math.max(1, craft.beats()));
        // XP for the craft is granted by CrafterWorkGoal (appeal-scaled) — not here, so every
        // executor pays into the same per-profession bucket without duplicating the multiplier.
        ItemStack out = Fletching.applyQuality(craft.result().copy(), tier);
        sl.playSound(null, workBlock, SoundEvents.VILLAGER_WORK_FLETCHER,
            SoundSource.BLOCKS, 0.8F, 1.0F);
        sl.sendParticles(ParticleTypes.HAPPY_VILLAGER,
            workBlock.getX() + 0.5, workBlock.getY() + 1.1, workBlock.getZ() + 0.5,
            10, 0.3, 0.2, 0.3, 0.0);
        return out;
    }

    @Override
    public void onAbort(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        // The pile items are display copies of the withdrawn inputs (which the goal returns to
        // storage) — clear them without dropping.
        if (sl.getBlockEntity(workBlock) instanceof FletchingStationBlockEntity be) {
            be.consumePile();
        }
    }
}
