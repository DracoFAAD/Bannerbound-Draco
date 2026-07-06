package com.bannerbound.core.api.research;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.research.data.StartingItemsLoader;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Single source of truth for "does this civ recognize this item?". The effective known set is
 * the global starting items (every settlement knows these regardless of era) unioned with the
 * settlement's own research unlocks ({@code unlocks.items}).
 * <p>
 * A {@code null} settlement means there's no civ context (a player without a settlement, a wild
 * mob dying with no killer, etc.) — in that case only the global starting items are known, which
 * mirrors {@link com.bannerbound.core.event.UnknownItemBlocker}'s "starting items only" fallback.
 * <p>
 * This is the server-side counterpart to the client's
 * {@link com.bannerbound.core.client.UnknownItemHelper}; both consult the same starting-items +
 * research-unlock sets so the inventory question-mark and the drop filter never disagree.
 */
public final class ItemKnowledge {
    private ItemKnowledge() {
    }

    /**
     * An extra, component-aware knowledge test an expansion can register so a single item id can be
     * "unknown" depending on its data — e.g. a modular arrow whose parts use a material the civ hasn't
     * researched. Gates only ever RESTRICT: the base item must already be known, then every gate must
     * also pass. Returning {@code true} = "no objection" (the default for items a gate doesn't care about).
     */
    @FunctionalInterface
    public interface StackGate {
        boolean isKnown(@Nullable Settlement settlement, ItemStack stack);
    }

    private static final List<StackGate> STACK_GATES = new CopyOnWriteArrayList<>();

    /** Registers a component-aware knowledge gate (call once at mod setup). */
    public static void registerStackGate(StackGate gate) {
        STACK_GATES.add(gate);
    }

    /** True if the settlement recognizes this exact stack — the item id is known AND every registered
     *  {@link StackGate} accepts it (so a modular arrow with an unresearched material reads unknown). */
    public static boolean isKnown(@Nullable Settlement settlement, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (!isKnown(settlement, stack.getItem())) {
            return false;
        }
        for (StackGate gate : STACK_GATES) {
            if (!gate.isKnown(settlement, stack)) {
                return false;
            }
        }
        return true;
    }

    /** True if {@code settlement} (or, when null, the global starting set alone) recognizes {@code item}. */
    public static boolean isKnown(@Nullable Settlement settlement, Item item) {
        if (item == null) {
            return false;
        }
        if (StartingItemsLoader.contains(item)) {
            return true;
        }
        if (settlement == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) {
            return false;
        }
        // Both trees grant item knowledge: science AND culture unlocks.items are first-class.

        return settlement.knownItems().contains(id.toString());

        /*
        if (ResearchManager.computeUnlockedItems(settlement).contains(id.toString())) {
            return true;
        }
        return CultureManager.computeUnlockedItems(settlement).contains(id.toString());
         */
    }
}
