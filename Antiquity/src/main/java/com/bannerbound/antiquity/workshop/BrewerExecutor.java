package com.bannerbound.antiquity.workshop;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.block.FermentationTroughBlock;
import com.bannerbound.antiquity.block.entity.FermentationTroughBlockEntity;
import com.bannerbound.antiquity.recipe.GrogRecipeManager;
import com.bannerbound.antiquity.recipe.MortarRecipe;
import com.bannerbound.antiquity.recipe.MortarRecipeManager;
import com.bannerbound.core.api.research.CraftGating;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.workshop.WorkExecutor;
import com.bannerbound.core.api.workshop.WorkshopStorage;
import com.bannerbound.core.api.workshop.Workshops;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * NPC driver for the Brewery workshop (the generic Crafter staffs it). The work block is a
 * fermentation-trough POOL anchor (a connected run counts as one station); the brewer keeps every
 * pool producing: PESTLE raw fermentables (berries) at the workshop's Mortar &amp; Pestle, FILL the
 * pool with hand-scooped water, CHARGE it with a pestled item — then the trough's own ferment
 * timer runs while the brewer walks away, and citizens drink straight from the pool
 * ({@code GrogDrinkGoal}), so there is no COLLECT step and no orderable "grog" item.
 *
 * <p><b>Waiting-stage contract</b> (see {@code Workshops.wantsAnother}): the ferment is the
 * unattended wait, but its in-flight gate is structural — {@code chargePool} refuses an
 * already-charged pool and finished grog never spoils, so CHARGE is idempotent per pool and can
 * never overproduce. Only PESTLE makes an item, and it follows the normal orders → min-stock
 * governor, plus one standing demand: a charge item for each of this workshop's uncharged pools.
 */
public class BrewerExecutor implements WorkExecutor {
    private static final int BEATS = 3;
    private static final int FILL_TICKS = 40;    // hand-scooping water into the pool
    private static final int CHARGE_TICKS = 40;  // working the mash into the water
    private static final int PESTLE_TICKS = 60;  // grinding a batch at the mortar

    @Nullable
    @Override
    public Craft chooseCraft(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock) {
        // ── This pool's tending. A charged pool (fermenting or holding finished grog) needs nothing:
        // citizens self-serve and the empty pool re-enters this loop after the last serving drains.
        if (sl.getBlockState(workBlock).getBlock() instanceof FermentationTroughBlock
                && !FermentationTroughBlock.isPoolCharged(sl, workBlock)) {
            int units = FermentationTroughBlock.poolUnits(sl, workBlock);
            int capacity = FermentationTroughBlock.poolCapacity(sl, workBlock);
            boolean waterReachable = FermentationTroughBlock.findScoopWater(sl, workBlock) != null;
            // FILL first — more water = more servings per batch (top-up is blocked once charged).
            if (units < capacity && waterReachable) {
                return new Craft(List.of(), ItemStack.EMPTY, FILL_TICKS, BEATS);
            }
            // CHARGE once no more water can be fetched (pool full, or none in scoop range — a
            // rain-fed pool with enough for the recipe still brews rather than stalling forever).
            Item charge = chargeItemFor(sl, workshop, units);
            if (charge != null) {
                return new Craft(List.of(new ItemStack(charge)), ItemStack.EMPTY, CHARGE_TICKS, BEATS);
            }
        }
        // ── PESTLE (the only item-producing step): orders first, then min-stock, then the standing
        // pool demand. Two passes, the ordered-first convention.
        Craft ordered = tryPestle(sl, settlement, workshop, workBlock, true);
        if (ordered != null) return ordered;
        return tryPestle(sl, settlement, workshop, workBlock, false);
    }

    /** The first stocked charge item whose grog recipe can start on {@code units} of water. */
    @Nullable
    private static Item chargeItemFor(ServerLevel sl, Workshop workshop, int units) {
        for (Item input : GrogRecipeManager.inputs()) {
            if (WorkshopStorage.count(sl, workshop, input) <= 0) continue;
            var match = GrogRecipeManager.findForInput(input);
            if (match != null && units >= match.getValue().minWaterUnits()) return input;
        }
        return null;
    }

    @Nullable
    private Craft tryPestle(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock,
                            boolean ordersOnly) {
        for (MortarRecipe recipe : brewablePestles()) {
            Item result = recipe.resultItem().getItem();
            if (!CraftGating.canProduceAt(sl, workBlock, result)) continue;
            boolean wanted = ordersOnly
                ? Workshops.orderedCraftCount(workshop, result) > 0
                : Workshops.wantedByMinStock(sl, settlement, workshop, recipe.resultItem())
                    || standingPoolDemand(sl, workshop);
            if (!wanted) continue;
            Item raw = stockedIngredient(sl, workshop, recipe);
            if (raw == null) continue;
            return new Craft(List.of(new ItemStack(raw)), recipe.resultItem().copy(), PESTLE_TICKS, BEATS + 1);
        }
        return null;
    }

    /** Standing demand: pestle when this workshop has more uncharged pools than charge items on
     *  hand — one pestled batch per waiting pool, and not a berry more without orders/min-stock. */
    private static boolean standingPoolDemand(ServerLevel sl, Workshop workshop) {
        int pools = BreweryWorkshopRules.unchargedPools(sl, workshop);
        if (pools <= 0) return false;
        int stocked = 0;
        for (Item input : GrogRecipeManager.inputs()) {
            stocked += WorkshopStorage.count(sl, workshop, input);
        }
        return stocked < pools;
    }

    /** Dry mortar recipes whose ground output charges a trough — the brewer's pestle repertoire.
     *  Fully data-driven: a modpack grog with a new pestled input auto-extends the brewer. */
    private static List<MortarRecipe> brewablePestles() {
        Set<Item> grogInputs = GrogRecipeManager.inputs();
        List<MortarRecipe> out = new ArrayList<>();
        for (MortarRecipe r : MortarRecipeManager.all()) {
            if (r.baseLiquid().isEmpty() && !r.resultItem().isEmpty()
                    && grogInputs.contains(r.resultItem().getItem())) {
                out.add(r);
            }
        }
        return out;
    }

    /** A stocked item matching the recipe's ingredient, or {@code null}. */
    @Nullable
    private static Item stockedIngredient(ServerLevel sl, Workshop workshop, MortarRecipe recipe) {
        for (ItemStack option : recipe.ingredient().getItems()) {
            if (WorkshopStorage.count(sl, workshop, option.getItem()) > 0) return option.getItem();
        }
        return null;
    }

    // ── Craft discrimination (the Tannery idiom: shape of inputs/result identifies the step). ──

    /** FILL scoops water — no inputs, no result. */
    private static boolean isFill(Craft craft) {
        return craft.inputs().isEmpty() && craft.result().isEmpty();
    }

    /** CHARGE works a pestled item into the pool — one grog-input item, no result. */
    private static boolean isCharge(Craft craft) {
        return !craft.inputs().isEmpty() && craft.result().isEmpty();
    }

    /** PESTLE grinds a raw fermentable into its pestled item — the only step with a result. */
    private static boolean isPestle(Craft craft) {
        return !craft.result().isEmpty();
    }

    /** PESTLE happens at the mortar; FILL at the pool cell nearest water; CHARGE at the anchor. */
    @Override
    public BlockPos workTarget(ServerLevel sl, Settlement settlement, Workshop workshop,
                               BlockPos workBlock, Craft craft) {
        if (isPestle(craft)) {
            BlockPos mortar = BreweryWorkshopRules.findMortar(sl, workshop);
            return mortar != null ? mortar : workBlock;
        }
        if (isFill(craft)) {
            BlockPos cell = FermentationTroughBlock.findScoopWater(sl, workBlock);
            return cell != null ? cell : workBlock;
        }
        return workBlock;
    }

    @Override
    public ItemStack finish(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        if (isFill(craft)) {
            FermentationTroughBlock.npcAddWater(sl, workBlock, FermentationTroughBlockEntity.UNITS_PER_CELL);
            return ItemStack.EMPTY;
        }
        if (isCharge(craft)) {
            ItemStack input = craft.inputs().get(0);
            // A player charged/drained the pool mid-walk → hand the pestled item back to storage
            // instead of letting it vanish.
            return FermentationTroughBlock.npcCharge(sl, workBlock, input.getItem())
                ? ItemStack.EMPTY : input.copy();
        }
        return craft.result().copy();
    }

    @Override
    public void onBeat(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft, int beatIndex) {
        BlockPos at = citizen.blockPosition();
        if (isFill(craft)) {
            sl.playSound(null, at, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 0.6F, 1.1F);
            splash(sl, citizen, 6);
        } else if (isCharge(craft)) {
            sl.playSound(null, at, SoundEvents.GENERIC_SPLASH, SoundSource.BLOCKS, 0.6F, 0.8F);
            splash(sl, citizen, 8);
        } else {
            // Grinding at the mortar — low stone scrape + a puff of crushed-berry crumbs.
            sl.playSound(null, at, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.4F, 0.6F);
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIMSON_SPORE,
                citizen.getX(), citizen.getY() + 1.0, citizen.getZ(), 4, 0.15, 0.1, 0.15, 0.0);
        }
    }

    private static void splash(ServerLevel sl, CitizenEntity citizen, int count) {
        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH,
            citizen.getX(), citizen.getY() + 0.8, citizen.getZ(), count, 0.25, 0.1, 0.25, 0.0);
    }

    /** Min-stock rows: the pestled charge items (players can stockpile them as trade goods). Grog
     *  itself is standing availability in the pool, never an item — deliberately absent here. */
    @Override
    public List<ItemStack> possibleOutputs(ServerLevel sl, BlockPos workBlock) {
        List<ItemStack> out = new ArrayList<>();
        for (MortarRecipe r : brewablePestles()) {
            if (CraftGating.canProduceAt(sl, workBlock, r.resultItem().getItem())) {
                out.add(r.resultItem().copy());
            }
        }
        return out;
    }

    /** Stocker SUPPLY: raw fermentables while any pestle demand stands (orders, min-stock, or an
     *  uncharged pool waiting on a charge item). Small rolling buffer, the tannery idiom. */
    @Override
    public List<ItemStack> missingInputs(ServerLevel sl, Settlement settlement, Workshop workshop,
                                         BlockPos workBlock) {
        List<ItemStack> out = new ArrayList<>();
        for (MortarRecipe recipe : brewablePestles()) {
            ItemStack result = recipe.resultItem();
            boolean wanted = Workshops.orderedCraftCount(workshop, result.getItem()) > 0
                || Workshops.wantedByMinStock(sl, settlement, workshop, result)
                || standingPoolDemand(sl, workshop);
            if (!wanted) continue;
            for (ItemStack option : recipe.ingredient().getItems()) {
                addDeficit(out, sl, workshop, option.getItem(), 4);
            }
        }
        return out;
    }

    private static void addDeficit(List<ItemStack> out, ServerLevel sl, Workshop workshop, Item item, int buffer) {
        int have = WorkshopStorage.count(sl, workshop, item);
        if (have < buffer) out.add(new ItemStack(item, buffer - have));
    }

    /** Stocker KEEP: raw fermentables and every pestled charge item — a pestled batch waiting for
     *  its pool to drain must not be hauled off to the stockpile between crafts. */
    @Override
    public Set<Item> retainedItems(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock) {
        Set<Item> keep = new LinkedHashSet<>(GrogRecipeManager.inputs());
        for (MortarRecipe recipe : brewablePestles()) {
            for (ItemStack option : recipe.ingredient().getItems()) {
                keep.add(option.getItem());
            }
        }
        return keep;
    }
}
