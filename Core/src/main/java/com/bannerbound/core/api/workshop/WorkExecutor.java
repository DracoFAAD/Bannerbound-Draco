package com.bannerbound.core.api.workshop;

import java.util.List;

import javax.annotation.Nullable;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * The per-work-block craft driver an expansion registers alongside its work block (see
 * {@code WorkBlockRegistry.WorkBlockDef}). The crafter goal owns navigation, input withdrawal,
 * arm swings, pacing and output deposit; the executor owns everything recipe- and station-
 * specific: choosing what to craft from the available storage, the station's visual/audio beats,
 * and finishing the result (e.g. rolling craftsmanship quality).
 *
 * <p><b>FLAG — waiting-stage crafters (drying / baking / smelting / proving / fermenting).</b> If a
 * station has a step where a committed unit finishes on its own clock while the worker walks away,
 * a single order will OVERPRODUCE unless {@link #chooseCraft} (a) collects finished waiting units
 * UNGATED and (b) gates the START of a new unit on {@link Workshops#wantsAnother} passing the count
 * of units already in flight. Read the full contract on {@code Workshops.wantsAnother}. Crafters
 * that BLOCK the worker for the whole step (kiln tended via {@link #externallyComplete}, or any
 * instant station) are automatically safe. The Tannery (drying rack) is the worked example; future
 * smith/baker/fermenter stations MUST follow it.
 */
public interface WorkExecutor {

    /**
     * One planned craft: the exact input stacks to withdraw from workshop storage, the base
     * result, total work duration and how many animation "beats" (sound/particle pulses —
     * e.g. one per simulated stretch) it plays across that duration.
     */
    record Craft(List<ItemStack> inputs, ItemStack result, int workTicks, int beats) {
    }

    /**
     * Chooses the next craft this work block can perform given the workshop's storage contents,
     * or {@code null} when there's nothing to do (no recipe inputs available / outputs gated /
     * min-stock satisfied). Executors must respect the order queue via {@link Workshops#orderedItems}
     * and the min-stock governor via {@link Workshops#wantedByMinStock}: no orders and no positive
     * min-stock row means no craft is wanted.
     */
    @Nullable
    Craft chooseCraft(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock);

    /** The block position the citizen should stand at/look at while performing this craft. Most
     *  crafts use their work block; multi-block crafts can redirect to a related structure. */
    default BlockPos workTarget(ServerLevel sl, Settlement settlement, Workshop workshop,
                                BlockPos workBlock, Craft craft) {
        return workBlock;
    }

    /** Every output this work block could produce for the settlement right now (researched recipe
     *  results, ignoring input availability) — drives the menu's min-stock rows. */
    default List<ItemStack> possibleOutputs(ServerLevel sl, BlockPos workBlock) {
        return List.of();
    }

    /**
     * The Stocker's SUPPLY surface: ingredient stacks this work block wants delivered into the
     * workshop storage — for every craft it currently WANTS (queued orders first, then the
     * positive min-stock deficits), the deficit between what a small input
     * buffer needs and what storage already holds. Empty = nothing to haul in.
     */
    default List<ItemStack> missingInputs(ServerLevel sl, Settlement settlement, Workshop workshop,
                                          BlockPos workBlock) {
        return List.of();
    }

    /**
     * The TRUE (un-buffered) per-input demand: the deficit between what this work block's wanted
     * crafts actually require — explicit orders + min-stock deficit, no rolling haul buffer — and
     * what storage holds. Sizes CHAIN PRODUCTION orders (a producer should craft only what's truly
     * needed), whereas {@link #missingInputs} may report MORE (the buffer) to pre-haul raws from a
     * stockpile in fewer trips. Default = {@code missingInputs} (buffered), so an executor that
     * never feeds a chain producer needs no override; executors whose inputs can be chain-crafted
     * elsewhere override this to avoid over-producing the intermediate (a bow pulling 4× string).
     */
    default List<ItemStack> trueInputDemand(ServerLevel sl, Settlement settlement, Workshop workshop,
                                            BlockPos workBlock) {
        return missingInputs(sl, settlement, workshop, workBlock);
    }

    /**
     * The Stocker's KEEP surface: every item that is an ingredient of a craft this work block
     * currently wants (regardless of availability) — the stocker must NOT haul these out of the
     * workshop storage. Anything in storage outside this set is surplus, free to move to the
     * stockpile (e.g. finished outputs, or raws for crafts nothing wants any more).
     */
    default java.util.Set<net.minecraft.world.item.Item> retainedItems(
            ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock) {
        return java.util.Set.of();
    }

    /** Craft begins (inputs already withdrawn). Stations with a visible pile place it here. */
    default void onStart(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
    }

    /** Called every work tick after the craft has started. Most executors only need beat/finish
     *  hooks; long-running structures can keep their block entity state fed here. */
    default void onWorkTick(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft,
                            int ticksLeft) {
    }

    /** True when an external work block has already completed the craft before the goal timer ends. */
    default boolean externallyComplete(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock,
                                       Craft craft, int ticksLeft) {
        return false;
    }

    /** One animation beat (the goal swings the arm; play station sounds/particles here). */
    default void onBeat(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, int beatIndex) {
    }

    /** One animation beat with the active craft available. The legacy overload remains so existing
     *  executors do not need to care unless they need craft-specific visuals. */
    default void onBeat(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft,
                        int beatIndex) {
        onBeat(sl, citizen, workBlock, beatIndex);
    }

    /** Whether this input is consumed by a successful craft. Reusable helpers, such as fire
     *  starters, can return false and the crafter loop will put them back into workshop storage. */
    default boolean consumesInput(Craft craft, ItemStack input) {
        return true;
    }

    /** How many queued order units this completed craft satisfies. Most workstations complete one
     *  queued unit per craft; batch executors can return a larger number. */
    default int fulfilledOrderUnits(ServerLevel sl, BlockPos workBlock, Craft craft, ItemStack output) {
        return 1;
    }

    /** Craft finished — return the final result stack (roll quality here) and clean the station
     *  up (clear any visible pile). The goal deposits the returned stack into workshop storage. */
    default ItemStack finish(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        return craft.result();
    }

    /** Craft aborted mid-way (citizen interrupted / inputs vanished). Clean the station up; the
     *  goal returns the withdrawn inputs to storage. */
    default void onAbort(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
    }
}
