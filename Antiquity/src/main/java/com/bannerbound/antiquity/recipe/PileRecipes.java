package com.bannerbound.antiquity.recipe;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.item.Item;

/**
 * Shared pile-matching for the count-based "place a pile, match a recipe" workstations — the crafting
 * stone (knapping), fletching station, and pottery slab. Each recipe keeps its own ingredient record
 * (and its own JSON codec); they only need to expose item + count via {@link Counted} to reuse the
 * one multiset-matching implementation instead of carrying three identical copies.
 */
public final class PileRecipes {
    private PileRecipes() {
    }

    /** A counted ingredient: one concrete item and how many of it the pile must contain. */
    public interface Counted {
        Item item();

        int count();
    }

    /** The item-to-count multiset required by a pile recipe. */
    public static Map<Item, Integer> requiredCounts(List<? extends Counted> ingredients) {
        Map<Item, Integer> m = new HashMap<>();
        for (Counted ing : ingredients) {
            m.merge(ing.item(), ing.count(), Integer::sum);
        }
        return m;
    }

    /** Exact match: {@code placed} must equal the recipe's required multiset. */
    public static boolean matches(List<? extends Counted> ingredients, Map<Item, Integer> placed) {
        return requiredCounts(ingredients).equals(placed);
    }
}
