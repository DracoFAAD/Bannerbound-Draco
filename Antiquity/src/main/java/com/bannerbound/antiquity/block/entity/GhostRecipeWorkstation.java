package com.bannerbound.antiquity.block.entity;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/**
 * A workstation with the ghost-recipe preview (crafting stone / fletching station): a partial pile
 * shows a candidate recipe's missing ingredients as silhouettes, browse arrows cycle between
 * candidates, and clicking the floating ghost result pulls the missing items from the player's
 * inventory. Lets the click payload handler and the client-side targeting treat both stations
 * uniformly.
 */
@ApiStatus.Internal
public interface GhostRecipeWorkstation {
    /** The matched recipe's result (EMPTY = pile doesn't exactly match — ghost preview territory). */
    ItemStack getResult();

    /** The selected candidate's result (EMPTY = no ghost preview showing). */
    ItemStack getGhostResult();

    /** The selected candidate's still-missing ingredients (counts = how many more). */
    List<ItemStack> getGhostIngredients();

    /** How many researched candidate recipes the pile could still become (arrows show when ≥ 2). */
    int getGhostCandidateCount();

    /** Local Y of the floating result preview — where the ghost result + browse arrows sit. */
    double ghostPreviewY();

    /** Cycles the ghost preview to the previous (-1) / next (+1) candidate. Also locks the
     *  selection — cycling is an explicit player choice. Server-side only. */
    void cycleGhost(int dir);

    /** Locks the current selection as player-chosen: it stops auto-switching when the pile
     *  changes, and the preview hides (rather than jumping to another recipe) if the pile becomes
     *  incompatible with it. The lock clears when the pile empties (craft / all items removed).
     *  Server-side only. */
    void lockGhost();

    /** Adds ONE of {@code held} to the pile. Returns true if it fit. Server-side only. */
    boolean insertOne(ItemStack held, Direction from);

    /** Removes ONE item from the most-recently-touched pile cell and returns it (EMPTY if nothing
     *  removable). Server-side only — completes the place/remove pair of the shared pile idiom. */
    ItemStack removeOne();
}
