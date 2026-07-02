package com.bannerbound.antiquity.recipe;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.antiquity.item.ArrowParts;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The fletching station's free-mix arrow assembly. Rather than a JSON recipe per material combination,
 * a pile of exactly three parts — one tip, one shaft, one back (each a single item) — is matched here
 * and turned into a synthetic {@link FletchingRecipe} whose result is a batch of composite arrows
 * stamped with the corresponding {@code ARROW_TIP/SHAFT/BACK} components (see {@link ArrowParts}). The
 * station runs the normal stretch minigame on it, so quality rolls exactly as for any other fletch.
 *
 * <p>Valid parts (resolved by {@link ArrowParts}, tag-mirrored in {@code tags/item/arrow_*}):
 * tips = flint blade / cast metal arrowheads; shafts = stick / metal ingot (metal costs an ingot per
 * batch); backs = feather / plant fiber.
 */
@ApiStatus.Internal
public final class ModularArrow {
    private ModularArrow() {}

    /** Arrows produced per assembled batch (one tip + one shaft + one back). */
    public static final int BATCH = 16;

    /** A synthetic recipe for the parts in {@code contents}, or {@code null} if the pile is not exactly
     *  one tip + one shaft + one back. Research gating is applied by the caller on the result item. */
    @Nullable
    public static FletchingRecipe tryMatch(List<ItemStack> contents) {
        String tip = null;
        String shaft = null;
        String back = null;
        Item tipItem = null;
        Item shaftItem = null;
        Item backItem = null;
        int total = 0;

        for (ItemStack s : contents) {
            if (s.isEmpty()) continue;
            total += s.getCount();
            if (s.getCount() != 1 || total > 3) return null; // exactly one of each part, nothing extra

            Item it = s.getItem();
            String t = ArrowParts.tipMaterial(it);
            String sh = ArrowParts.shaftMaterial(it);
            String b = ArrowParts.backMaterial(it);
            if (t != null) {
                if (tip != null) return null;
                tip = t; tipItem = it;
            } else if (sh != null) {
                if (shaft != null) return null;
                shaft = sh; shaftItem = it;
            } else if (b != null) {
                if (back != null) return null;
                back = b; backItem = it;
            } else {
                return null; // an item that isn't a valid arrow part → no modular match
            }
        }

        if (tip == null || shaft == null || back == null) return null;

        return recipe(tipItem, shaftItem, backItem, tip, shaft, back);
    }

    /** A "could become" preview for a PARTIAL arrow pile: any mix of valid parts (at least one, no
     *  invalid items, ≤3 total) → a synthetic recipe whose missing slots are filled with representative
     *  defaults (flint blade / stick / feather) so the station's ghost shows the parts still needed and
     *  the arrow they would make. Returns {@code null} if the pile has no arrow part or holds junk. */
    @Nullable
    public static FletchingRecipe ghostCandidate(List<ItemStack> contents) {
        String tip = null, shaft = null, back = null;
        Item tipItem = null, shaftItem = null, backItem = null;
        int total = 0;
        boolean any = false;

        for (ItemStack s : contents) {
            if (s.isEmpty()) continue;
            total += s.getCount();
            if (total > 3) return null;
            Item it = s.getItem();
            String t = ArrowParts.tipMaterial(it);
            String sh = ArrowParts.shaftMaterial(it);
            String b = ArrowParts.backMaterial(it);
            if (t != null) {
                if (tip != null) return null;
                tip = t; tipItem = it; any = true;
            } else if (sh != null) {
                if (shaft != null) return null;
                shaft = sh; shaftItem = it; any = true;
            } else if (b != null) {
                if (back != null) return null;
                back = b; backItem = it; any = true;
            } else {
                return null;
            }
        }
        if (!any) return null;

        // Fill the empty slots with the basic default parts (data-driven) so the ghost shows what's
        // still needed. If a default part isn't defined in the registry, there's nothing to preview.
        if (tipItem == null)   { tip = ArrowParts.DEFAULT_TIP;   tipItem = ArrowParts.tipItem(tip); }
        if (shaftItem == null) { shaft = ArrowParts.DEFAULT_SHAFT; shaftItem = ArrowParts.shaftItem(shaft); }
        if (backItem == null)  { back = ArrowParts.DEFAULT_BACK;  backItem = ArrowParts.backItem(back); }
        if (tipItem == null || shaftItem == null || backItem == null) return null;
        return recipe(tipItem, shaftItem, backItem, tip, shaft, back);
    }

    private static FletchingRecipe recipe(Item tipItem, Item shaftItem, Item backItem,
                                          String tip, String shaft, String back) {
        List<FletchingRecipe.Ing> ings = List.of(
            new FletchingRecipe.Ing(tipItem, 1),
            new FletchingRecipe.Ing(shaftItem, 1),
            new FletchingRecipe.Ing(backItem, 1));
        ItemStack result = ArrowParts.makeArrow(tip, shaft, back, BATCH);
        // Minigame knobs match the old metal-arrow fletch recipe.
        return new FletchingRecipe(ings, result, 3, 0.20F, 0.70F, 0.07F, 0.06F, Optional.empty());
    }
}
