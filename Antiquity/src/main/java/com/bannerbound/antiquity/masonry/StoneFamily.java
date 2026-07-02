package com.bannerbound.antiquity.masonry;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A stone "family" the Mason's Bench works — keyed by its base block (cobblestone, stone, sandstone,
 * …) and able to resolve that family's dressed variants ({@code slab}, {@code stairs}, {@code wall},
 * {@code bricks}, {@code polished}, …) by name. This is the stone analogue of carpentry's
 * {@code WoodFamily}: a masonry output row is written ONCE per variant and resolved per family here,
 * so we don't hand-author a recipe for every family × variant.
 *
 * <p>Unlike wood (open-ended via the {@code _planks} convention), vanilla's stone matrix is small and
 * <b>irregular</b> — there is no {@code granite_slab} or {@code stone_wall}, "polished" is a prefix
 * not a suffix, sandstone uses {@code cut_}/{@code smooth_}/{@code chiseled_} forms, and chiseled
 * stone is {@code chiseled_stone_bricks}. So the seven supported families are fixed (the set the user
 * asked for) and each variant tries a short list of candidate id templates, returning the first that
 * actually exists in the item registry — unsupported combos simply resolve to {@code null} and are
 * skipped everywhere. Deepslate / tuff / blackstone are deliberately excluded (not antiquity stone).
 */
@ApiStatus.Internal
public enum StoneFamily {
    COBBLESTONE("minecraft", "cobblestone"),
    STONE("minecraft", "stone"),
    SANDSTONE("minecraft", "sandstone"),
    RED_SANDSTONE("minecraft", "red_sandstone"),
    ANDESITE("minecraft", "andesite"),
    DIORITE("minecraft", "diorite"),
    GRANITE("minecraft", "granite");

    /** Candidate id templates per variant key ({@code %s} = the family base path). The first template
     *  that resolves to a registered item wins; none → the family doesn't have that variant. */
    private static final Map<String, String[]> TEMPLATES = new LinkedHashMap<>();
    static {
        TEMPLATES.put("slab",     new String[] {"%s_slab"});
        TEMPLATES.put("stairs",   new String[] {"%s_stairs"});
        TEMPLATES.put("wall",     new String[] {"%s_wall"});
        TEMPLATES.put("bricks",   new String[] {"%s_bricks"});
        TEMPLATES.put("polished", new String[] {"polished_%s"});
        TEMPLATES.put("smooth",   new String[] {"smooth_%s"});
        TEMPLATES.put("cut",      new String[] {"cut_%s"});
        TEMPLATES.put("chiseled", new String[] {"chiseled_%s", "chiseled_%s_bricks"});
    }

    private final String namespace;
    private final String base;

    StoneFamily(String namespace, String base) {
        this.namespace = namespace;
        this.base = base;
    }

    /** Stable key for NBT + offer maps: {@code <namespace>:<base>}. */
    public String key() {
        return namespace + ":" + base;
    }

    /** The family's base block item (the budget material the bench consumes). */
    public Item baseItem() {
        return lookup(base);
    }

    /** Resolves this family's variant by key (see {@link #TEMPLATES}), or {@code null} if vanilla
     *  has no such block for this family. */
    @Nullable
    public Item variant(String variantKey) {
        String[] templates = TEMPLATES.get(variantKey);
        if (templates == null) return null;
        for (String t : templates) {
            Item it = lookup(String.format(t, base));
            if (it != null) return it;
        }
        return null;
    }

    @Nullable
    private Item lookup(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, path);
        return BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.get(id) : null;
    }

    /** The family whose base block is {@code item}, or {@code null} if {@code item} isn't a base. */
    @Nullable
    public static StoneFamily fromBase(Item item) {
        for (StoneFamily f : values()) {
            if (f.baseItem() == item) return f;
        }
        return null;
    }

    /** Rebuilds a family from its {@link #key()} (NBT round-trip), or {@code null} if unknown. */
    @Nullable
    public static StoneFamily fromKey(String key) {
        for (StoneFamily f : values()) {
            if (f.key().equals(key)) return f;
        }
        return null;
    }

    /** True if {@code stack} is a base stone the bench accepts as budget. */
    public static boolean isBudgetStone(ItemStack stack) {
        return !stack.isEmpty() && fromBase(stack.getItem()) != null;
    }
}
