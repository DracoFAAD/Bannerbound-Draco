package com.bannerbound.antiquity.food;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.item.FoodSpoilage;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * Server-side stack operations for the food-freshness layer: stamp a perishable stack as fresh, roll
 * its once-a-second chance to degrade a level, and apply salt's keeps-longer bonus. Freshness is a
 * discrete level on the stack ({@link FoodSpoilage}); there is no per-stack clock.
 *
 * <p><b>Stacking:</b> because the component carries only a level (and a salt flag), every fresh stack
 * of a food is identical and merges with every other fresh stack — likewise bland with bland. Spoilage
 * never fragments food beyond at most "a fresh pile, a bland pile, and a spoiled pile".
 *
 * <p><b>Spoilage is probabilistic.</b> Each second a stack is observed (carried, dropped, or scanned
 * in claimed storage), {@link #tick} rolls a chance to drop one level: {@code FRESH → BLAND}, then
 * {@code BLAND →} the terminal {@code spoiled_food} item. The chance for a phase is {@code 20 / meanTicks}
 * (one roll/sec, geometric mean = the phase's data-driven duration), so a stack's <i>average</i> shelf
 * life matches {@link FoodSpoilageData} while the exact moment it turns varies stack to stack. Salt
 * divides the chance by {@link FoodSpoilageData#saltLifeMultiplier()}, so salted food keeps longer.
 */
public final class Spoilage {
    private Spoilage() {}

    /** Ticks between spoilage rolls — the spoilage sweeps run once a second (every 20 ticks). */
    private static final double ROLL_TICKS = 20.0;

    /** Stamps a perishable stack that has no freshness yet, as FRESH. Mutates {@code stack} in place. */
    public static void ensureStamped(ItemStack stack) {
        if (stack.isEmpty() || stack.has(BannerboundAntiquity.FOOD_SPOILAGE.get())) return;
        if (!FoodSpoilageData.isPerishable(stack.getItem())) return;
        stack.set(BannerboundAntiquity.FOOD_SPOILAGE.get(), new FoodSpoilage(FoodSpoilage.FRESH, false));
    }

    /**
     * Stamps {@code stack} as fresh if it is an unstamped perishable, without rolling spoilage — used
     * the instant food enters the world so dropped/harvested food carries the FRESH component before
     * pickup and merges with the fresh food already in an inventory. Returns a stamped copy when it
     * stamps, otherwise the original stack.
     */
    public static ItemStack stamp(ItemStack stack) {
        if (stack.isEmpty() || stack.has(BannerboundAntiquity.FOOD_SPOILAGE.get())
            || !FoodSpoilageData.isPerishable(stack.getItem())) {
            return stack;
        }
        ItemStack copy = stack.copy();
        ensureStamped(copy);
        return copy;
    }

    /**
     * Stamps {@code stack} if needed, then rolls its once-a-second spoilage chance. May return a
     * fresh-stamped copy, a copy degraded to bland, a {@code spoiled_food} stack, or the original
     * {@code stack} unchanged. Callers write the result back when it differs from what they passed.
     */
    public static ItemStack tick(ItemStack stack, Level level) {
        if (stack.isEmpty()) return stack;
        ItemStack current = stack;
        boolean copied = false;
        if (!current.has(BannerboundAntiquity.FOOD_SPOILAGE.get())
            && FoodSpoilageData.isPerishable(current.getItem())) {
            current = stack.copy();
            copied = true;
            ensureStamped(current);
        }
        FoodSpoilage fs = current.get(BannerboundAntiquity.FOOD_SPOILAGE.get());
        if (fs == null) return current;

        double saltMult = fs.salted() ? FoodSpoilageData.saltLifeMultiplier() : 1.0;
        if (fs.isFresh()) {
            int phase = Math.max(1, FoodSpoilageData.freshTicks(current.getItem()));
            if (roll(level, ROLL_TICKS / (phase * saltMult))) {
                if (!copied) current = stack.copy();
                current.set(BannerboundAntiquity.FOOD_SPOILAGE.get(),
                    new FoodSpoilage(FoodSpoilage.BLAND, fs.salted()));
            }
        } else {
            int phase = Math.max(1, FoodSpoilageData.blandTicks(current.getItem()));
            if (roll(level, ROLL_TICKS / (phase * saltMult))) {
                return new ItemStack(BannerboundAntiquity.SPOILED_FOOD.get(), current.getCount());
            }
        }
        return current;
    }

    private static boolean roll(Level level, double chance) {
        return chance > 0.0 && level.getRandom().nextDouble() < chance;
    }

    /** Stamps and rolls every slot of a container, then re-merges any fragmented food stacks. */
    public static void sweep(Container container, Level level) {
        int n = container.getContainerSize();
        for (int i = 0; i < n; i++) {
            ItemStack s = container.getItem(i);
            ItemStack r = tick(s, level);
            if (r != s) container.setItem(i, r);
        }
        // For a player, only compact the main 36 inventory slots — never pull food out of the
        // armor (36-39) or off-hand (40) slots, which the player may be holding food in on purpose.
        int compactLimit = container instanceof net.minecraft.world.entity.player.Inventory
            ? Math.min(36, n) : n;
        compactFood(container, compactLimit);
    }

    /**
     * Re-merges food stacks that stamping or degrading may have fragmented. Vanilla never retroactively
     * merges two equal stacks, so food stamped into separate slots — or picked up unstamped next to an
     * already-stamped stack — would otherwise stay as partial stacks. The same is true of food that
     * degrades a level slot-by-slot. Only compactable food is touched (see {@link #isCompactableFood}),
     * and {@link ItemStack#isSameItemSameComponents} guarantees we only fuse same-freshness food
     * (different freshness has a different level component and is correctly left apart).
     */
    private static void compactFood(Container container, int limit) {
        for (int i = 0; i < limit; i++) {
            ItemStack a = container.getItem(i);
            if (a.isEmpty() || a.getCount() >= a.getMaxStackSize() || !isCompactableFood(a)) continue;
            for (int j = i + 1; j < limit; j++) {
                ItemStack b = container.getItem(j);
                if (b.isEmpty() || !ItemStack.isSameItemSameComponents(a, b)) continue;
                int move = Math.min(b.getCount(), a.getMaxStackSize() - a.getCount());
                if (move <= 0) continue;
                a.grow(move);
                b.shrink(move);
                container.setItem(j, b);
                if (a.getCount() >= a.getMaxStackSize()) break;
            }
            container.setItem(i, a);
        }
    }

    /**
     * Re-merges fragmented food across an item handler's slots — the storage-side counterpart to
     * {@link #compactFood}, used by Core's larder scan after it stamps/degrades stacks slot-by-slot.
     * Operates on copies because {@link IItemHandlerModifiable#getStackInSlot} forbids mutating the
     * returned stack, writing merged results back via {@code setStackInSlot}.
     */
    public static void compactStorage(IItemHandlerModifiable handler) {
        int n = handler.getSlots();
        for (int i = 0; i < n; i++) {
            ItemStack a = handler.getStackInSlot(i);
            if (a.isEmpty() || a.getCount() >= a.getMaxStackSize() || !isCompactableFood(a)) continue;
            a = a.copy();
            boolean changed = false;
            for (int j = i + 1; j < n; j++) {
                ItemStack b = handler.getStackInSlot(j);
                if (b.isEmpty() || !ItemStack.isSameItemSameComponents(a, b)) continue;
                int move = Math.min(b.getCount(), a.getMaxStackSize() - a.getCount());
                if (move <= 0) continue;
                a.grow(move);
                ItemStack bShrunk = b.copy();
                bShrunk.shrink(move);
                handler.setStackInSlot(j, bShrunk);
                changed = true;
                if (a.getCount() >= a.getMaxStackSize()) break;
            }
            if (changed) handler.setStackInSlot(i, a);
        }
    }

    /** Food that may be re-merged after a sweep: any perishable (same-freshness only, enforced by the
     *  component check) and the terminal {@code spoiled_food} a sweep produces. */
    private static boolean isCompactableFood(ItemStack stack) {
        return stack.is(BannerboundAntiquity.SPOILED_FOOD.get())
            || FoodSpoilageData.isPerishable(stack.getItem());
    }

    /**
     * Applies salt to a food stack once: marks it salted so it keeps longer (its per-roll spoil chance
     * is divided by {@link FoodSpoilageData#saltLifeMultiplier()}). Returns {@code true} if salt was
     * actually applied. Salt preserves what's left; it does not refresh bland food back to fresh.
     */
    public static boolean applySalt(ItemStack food, Level level) {
        if (food.isEmpty() || !FoodSpoilageData.isPerishable(food.getItem())) return false;
        ensureStamped(food);
        FoodSpoilage fs = food.get(BannerboundAntiquity.FOOD_SPOILAGE.get());
        if (fs == null || fs.salted()) return false;
        food.set(BannerboundAntiquity.FOOD_SPOILAGE.get(), new FoodSpoilage(fs.level(), true));
        return true;
    }

    /** Whether this stack is already salted. */
    public static boolean isSalted(ItemStack stack) {
        FoodSpoilage fs = stack.get(BannerboundAntiquity.FOOD_SPOILAGE.get());
        return fs != null && fs.salted();
    }

    /** Whether this stack is the terminal spoiled-food item. */
    public static boolean isSpoiled(ItemStack stack) {
        return stack.is(BannerboundAntiquity.SPOILED_FOOD.get());
    }
}
