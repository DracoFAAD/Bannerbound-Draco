package com.bannerbound.antiquity.item;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.recipe.ArrowPart;
import com.bannerbound.antiquity.recipe.ArrowPartRegistry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The accessor everything goes through for the modular {@link CompositeArrowItem arrow}'s three parts
 * — tip, shaft, back. Each part is a short material id stored on the stack as the
 * {@code ARROW_TIP/SHAFT/BACK} data components; the part's stats, ingredient item, and textures all
 * come from the DATA-DRIVEN {@link ArrowPartRegistry} (datapack {@code arrow_parts/*.json}), so a
 * modpack adds an arrow material with a JSON + two textures and it flows through crafting, stats, the
 * NPC fletcher, the in-flight projectile, and the inventory icon with no code.
 *
 * <p>Defaults (a bare {@code new ItemStack(ARROW)} or an undefined material): flint tip / wood shaft /
 * feather back — the basic arrow — with neutral stats if the registry has no matching part.
 */
@ApiStatus.Internal
public final class ArrowParts {
    private ArrowParts() {}

    public static final String DEFAULT_TIP = "flint";
    public static final String DEFAULT_SHAFT = "wood";
    public static final String DEFAULT_BACK = "feather";

    // ── Component access ──────────────────────────────────────────────────────────────────────

    public static String tip(ItemStack stack) {
        String v = stack.get(BannerboundAntiquity.ARROW_TIP.get());
        return v == null ? DEFAULT_TIP : v;
    }

    public static String shaft(ItemStack stack) {
        String v = stack.get(BannerboundAntiquity.ARROW_SHAFT.get());
        return v == null ? DEFAULT_SHAFT : v;
    }

    public static String back(ItemStack stack) {
        String v = stack.get(BannerboundAntiquity.ARROW_BACK.get());
        return v == null ? DEFAULT_BACK : v;
    }

    /** A composite arrow stack stamped with the three part ids. */
    public static ItemStack makeArrow(String tip, String shaft, String back, int count) {
        ItemStack stack = new ItemStack(BannerboundAntiquity.ARROW.get(), count);
        stack.set(BannerboundAntiquity.ARROW_TIP.get(), tip);
        stack.set(BannerboundAntiquity.ARROW_SHAFT.get(), shaft);
        stack.set(BannerboundAntiquity.ARROW_BACK.get(), back);
        return stack;
    }

    @Nullable public static ArrowPart tipPart(ItemStack stack)   { return ArrowPartRegistry.get(ArrowPart.SLOT_TIP, tip(stack)); }
    @Nullable public static ArrowPart shaftPart(ItemStack stack) { return ArrowPartRegistry.get(ArrowPart.SLOT_SHAFT, shaft(stack)); }
    @Nullable public static ArrowPart backPart(ItemStack stack)  { return ArrowPartRegistry.get(ArrowPart.SLOT_BACK, back(stack)); }

    // ── Ingredient ⇄ material (data-driven, used by the fletching match + NPC fletcher) ──────────

    /** The material an ingredient item supplies for a slot, or {@code null} if it is not a valid part. */
    @Nullable
    public static String materialOf(String slot, Item item) {
        for (ArrowPart p : ArrowPartRegistry.sorted(slot)) {
            if (p.ingredient() == item) return p.material();
        }
        return null;
    }

    @Nullable public static String tipMaterial(Item item)   { return materialOf(ArrowPart.SLOT_TIP, item); }
    @Nullable public static String shaftMaterial(Item item) { return materialOf(ArrowPart.SLOT_SHAFT, item); }
    @Nullable public static String backMaterial(Item item)  { return materialOf(ArrowPart.SLOT_BACK, item); }

    /** The crafting ingredient item for a slot+material, or {@code null} if undefined. */
    @Nullable
    public static Item ingredient(String slot, String material) {
        ArrowPart p = ArrowPartRegistry.get(slot, material);
        return p == null ? null : p.ingredient();
    }

    @Nullable public static Item tipItem(String material)   { return ingredient(ArrowPart.SLOT_TIP, material); }
    @Nullable public static Item shaftItem(String material) { return ingredient(ArrowPart.SLOT_SHAFT, material); }
    @Nullable public static Item backItem(String material)  { return ingredient(ArrowPart.SLOT_BACK, material); }

    /** Parts of a slot, best-first (an NPC fletcher consumes the highest-priority stocked part). */
    public static List<ArrowPart> sorted(String slot) {
        return ArrowPartRegistry.sorted(slot);
    }

    /** Every recognized arrow part ingredient (the Fletcher's stocker keeps these). */
    public static List<Item> allPartItems() {
        List<Item> out = new ArrayList<>();
        for (ArrowPart p : ArrowPartRegistry.all()) out.add(p.ingredient());
        return out;
    }

    // ── Derived stats ─────────────────────────────────────────────────────────────────────────

    /** Total damage multiplier: the tip's base factor, bumped a little by the shaft's metal weight. */
    public static double damageMultiplier(ItemStack stack) {
        ArrowPart t = tipPart(stack);
        ArrowPart s = shaftPart(stack);
        double tipFactor = t == null ? 1.0 : t.damage();
        int shaftWeight = s == null ? 0 : s.weight();
        return tipFactor * (1.0 + shaftWeight * 0.04);
    }

    /** Combined density of the tip + shaft (0 = all-light, higher = heavier). */
    public static int weightPoints(ItemStack stack) {
        ArrowPart t = tipPart(stack);
        ArrowPart s = shaftPart(stack);
        return (t == null ? 0 : t.weight()) + (s == null ? 0 : s.weight());
    }

    /** Multiplier on the bow's inaccuracy from the back/fletching (lower = tighter grouping). */
    public static float inaccuracyMultiplier(ItemStack stack) {
        ArrowPart b = backPart(stack);
        return b == null ? 1.0F : (float) b.accuracy();
    }

    /** Per-tick downward acceleration for this arrow's parts (vanilla arrows use 0.05). */
    public static double gravityFor(ItemStack stack) {
        return 0.05 * (1.0 + 0.07 * weightPoints(stack));
    }

    // ── Knowledge (a foreign arrow is unusable until you know ALL its part ingredients) ──────────

    /** True if every part's crafting ingredient passes {@code known} — i.e. the civ recognizes the
     *  tip/shaft/back materials. A part whose material isn't in the registry counts as unknown. Used by
     *  both the server gate ({@code ItemKnowledge}) and client gate ({@code UnknownItemHelper}) via the
     *  side-appropriate {@code known} test, so a stray arrow made from a metal you haven't researched
     *  reads as an unknown item (can't be fired, shows as ???). Non-arrow stacks are never restricted. */
    public static boolean partsKnown(ItemStack stack, java.util.function.Predicate<Item> known) {
        if (!(stack.getItem() instanceof CompositeArrowItem)) {
            return true;
        }
        return partKnown(ArrowPart.SLOT_TIP, tip(stack), known)
            && partKnown(ArrowPart.SLOT_SHAFT, shaft(stack), known)
            && partKnown(ArrowPart.SLOT_BACK, back(stack), known);
    }

    private static boolean partKnown(String slot, String material, java.util.function.Predicate<Item> known) {
        Item ing = ingredient(slot, material);
        return ing != null && known.test(ing);
    }

    // ── Textures (for the renderers) ──────────────────────────────────────────────────────────

    /** The inventory-icon (atlas sprite) texture for a slot+material, or {@code null} if undefined. */
    @Nullable
    public static ResourceLocation itemTexture(String slot, String material) {
        ArrowPart p = ArrowPartRegistry.get(slot, material);
        return p == null ? null : p.itemTexture();
    }

    /** The in-flight projectile texture (full {@code textures/…png} path) for a slot+material. */
    @Nullable
    public static ResourceLocation projectileTexture(String slot, String material) {
        ArrowPart p = ArrowPartRegistry.get(slot, material);
        return p == null ? null : p.projectileTexture();
    }
}
