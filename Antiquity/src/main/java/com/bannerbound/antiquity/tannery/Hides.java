package com.bannerbound.antiquity.tannery;

import java.util.Map;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;

/**
 * Single source of truth mapping an animal species to its raw-hide item. Used by BOTH acquisition
 * paths — the hunting drop injection ({@code AnimalDropsEvents}) and the herder cull hook
 * ({@code AntiquityHerderHooks}). A species absent from the map yields no hide.
 */
public final class Hides {
    private Hides() {
    }

    private static final Map<EntityType<?>, java.util.function.Supplier<Item>> BY_SPECIES = Map.of(
        EntityType.COW, BannerboundAntiquity.COW_HIDE,
        EntityType.SHEEP, BannerboundAntiquity.SHEEP_HIDE,
        EntityType.PIG, BannerboundAntiquity.PIG_HIDE,
        EntityType.GOAT, BannerboundAntiquity.GOAT_HIDE,
        EntityType.HORSE, BannerboundAntiquity.HORSE_HIDE);

    /** The raw-hide item for this species, or {@code null} if the species yields no hide. */
    @Nullable
    public static Item hideFor(EntityType<?> type) {
        java.util.function.Supplier<Item> s = BY_SPECIES.get(type);
        return s == null ? null : s.get();
    }

    /** True if {@code item} is one of the per-species raw hides (the tanning rack's scrape input). */
    public static boolean isRawHide(Item item) {
        for (java.util.function.Supplier<Item> s : BY_SPECIES.values()) {
            if (s.get() == item) return true;
        }
        return false;
    }
}
