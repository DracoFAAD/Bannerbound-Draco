package com.bannerbound.core.farmer;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;

/**
 * Server-side seed catalog. The candidate list is driven by the item tag
 * {@code bannerbound:farmer_seeds} — modders add their own seed items to that tag and
 * the picker picks them up automatically. The default tag entries cover the four vanilla
 * crops (wheat, beetroot, carrots, potatoes).
 * <p>
 * Per-seed lookups: the planted crop block is derived from {@code BlockItem.getBlock()} (covers
 * any {@code ItemNameBlockItem} wrapping a {@link CropBlock}, which is what every vanilla seed
 * — including carrot + potato — already is). The shown "output" icon for the floating marker
 * comes from a small hardcoded map for vanilla crops; modded seeds fall back to the seed item
 * itself if no mapping is registered.
 */
@ApiStatus.Internal
public final class SeedCandidates {
    public static final TagKey<Item> FARMER_SEEDS = TagKey.create(
        Registries.ITEM, ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "farmer_seeds"));

    /** Optional override: seed item id → the item shown as the "yield" icon above the selection.
     *  Falls back to the seed itself when not present. */
    private static final Map<String, Item> SEED_TO_OUTPUT = Map.of(
        "minecraft:wheat_seeds", Items.WHEAT,
        "minecraft:beetroot_seeds", Items.BEETROOT,
        "minecraft:carrot", Items.CARROT,
        "minecraft:potato", Items.POTATO
    );

    private SeedCandidates() {
    }

    /** Namespaced item ids of every seed currently in the {@link #FARMER_SEEDS} tag, in
     *  registry order. Sent inline in the picker payload. Server-side only — the client gets
     *  the list as a payload field, no tag lookup needed there. */
    public static List<String> itemIds() {
        List<String> out = new ArrayList<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(FARMER_SEEDS)) {
            holder.unwrapKey().ifPresent(key -> out.add(key.location().toString()));
        }
        return out;
    }

    /** The {@link CropBlock} that gets placed (at age 0) when {@code seedItem} is planted.
     *  Returns null for items that aren't backed by a CropBlock — silently skipped at plant
     *  time so a misconfigured tag entry can't crash the worker. */
    public static Block cropFor(Item seedItem) {
        if (seedItem instanceof BlockItem bi && bi.getBlock() instanceof CropBlock cb) {
            return cb;
        }
        return null;
    }

    /** True if {@code id} is currently in the {@link #FARMER_SEEDS} tag. Used by the server-side
     *  pick-seed handler to reject junk values. */
    public static boolean isValid(String id) {
        if (id == null || id.isEmpty()) return false;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return false;
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == Items.AIR) return false;
        return item.builtInRegistryHolder().is(FARMER_SEEDS);
    }

    /** Item to render as the "yield" icon hovering above a farmer selection. Vanilla crops use
     *  the hardcoded mapping (seeds → harvested produce); unknown / modded seeds fall back to
     *  the seed item itself. */
    public static Item outputFor(String seedItemId) {
        if (seedItemId == null || seedItemId.isEmpty()) return Items.AIR;
        Item explicit = SEED_TO_OUTPUT.get(seedItemId);
        if (explicit != null) return explicit;
        ResourceLocation rl = ResourceLocation.tryParse(seedItemId);
        if (rl == null) return Items.AIR;
        return BuiltInRegistries.ITEM.get(rl);
    }
}
