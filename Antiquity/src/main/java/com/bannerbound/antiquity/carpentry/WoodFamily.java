package com.bannerbound.antiquity.carpentry;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Resolves a wood "family" from a log item and looks up that family's plank-derived variants by
 * suffix — so carpentry recipes are written ONCE per variant ({@code stairs}, {@code slab}, …) and
 * resolved per wood at runtime instead of hand-authoring a recipe for every wood × variant.
 *
 * <p>A family is keyed by {@code <namespace>:<base>} (e.g. {@code minecraft:oak},
 * {@code minecraft:crimson}). The base is the log path stripped of the {@code stripped_} prefix and
 * the {@code _log}/{@code _wood}/{@code _stem}/{@code _hyphae}/{@code _block} suffix. A would-be
 * family is only valid if {@code <namespace>:<base>_planks} exists in the item registry — that's the
 * single membership test, so any modded wood that follows the vanilla naming convention works for
 * free.
 */
@ApiStatus.Internal
public final class WoodFamily {
    private final String namespace;
    private final String base;
    private final Item planks;

    private WoodFamily(String namespace, String base, Item planks) {
        this.namespace = namespace;
        this.base = base;
        this.planks = planks;
    }

    /** A budget item must be a log (in {@code minecraft:logs}) whose family resolves a planks item. */
    public static boolean isBudgetLog(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ItemTags.LOGS) && fromLog(stack.getItem()) != null;
    }

    /** The family a log belongs to, or {@code null} if it doesn't follow the {@code _planks} convention. */
    @Nullable
    public static WoodFamily fromLog(Item log) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(log);
        if (id == null) return null;
        String base = id.getPath();
        if (base.startsWith("stripped_")) base = base.substring("stripped_".length());
        for (String suffix : new String[] {"_log", "_wood", "_stem", "_hyphae", "_block"}) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
                break;
            }
        }
        return build(id.getNamespace(), base);
    }

    /** Rebuilds a family from its {@link #key()} (NBT round-trip), or {@code null} if it no longer resolves. */
    @Nullable
    public static WoodFamily fromKey(String key) {
        int colon = key.indexOf(':');
        if (colon < 0) return null;
        return build(key.substring(0, colon), key.substring(colon + 1));
    }

    @Nullable
    private static WoodFamily build(String namespace, String base) {
        ResourceLocation planksId = ResourceLocation.fromNamespaceAndPath(namespace, base + "_planks");
        if (!BuiltInRegistries.ITEM.containsKey(planksId)) return null;
        return new WoodFamily(namespace, base, BuiltInRegistries.ITEM.get(planksId));
    }

    /** Stable key for NBT + budget map: {@code <namespace>:<base>}. */
    public String key() {
        return namespace + ":" + base;
    }

    /** The family's planks item (its {@code planks} variant). */
    public Item planks() {
        return planks;
    }

    /** A representative log item to draw on the table during the saw minigame: {@code _log}, then
     *  {@code _stem}/{@code _block}, falling back to the planks. */
    public Item representativeLog() {
        for (String suffix : new String[] {"_log", "_stem", "_block", "_wood"}) {
            Item it = lookup(base + suffix);
            if (it != null) return it;
        }
        return planks;
    }

    /** Resolves this family's variant by suffix — {@code "planks"} returns the planks item, anything
     *  else resolves {@code <namespace>:<base>_<suffix>} from the registry (null if it doesn't exist). */
    @Nullable
    public Item variant(String suffix) {
        if ("planks".equals(suffix)) return planks;
        return lookup(base + "_" + suffix);
    }

    @Nullable
    private Item lookup(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, path);
        return BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.get(id) : null;
    }
}
