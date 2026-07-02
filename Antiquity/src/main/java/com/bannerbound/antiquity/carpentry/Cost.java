package com.bannerbound.antiquity.carpentry;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

/**
 * One per-unit input requirement for a queued carpenter's-table craft — the runtime cost model that
 * unifies the two recipe schemas. A {@link CarpentryOutput} family/variant offer contributes a
 * single {@link Kind#FAMILY} cost ("any log of this wood family"); a {@link CarpentryAssembly} tool
 * recipe contributes its {@link Kind#TAG}/{@link Kind#ITEM} costs ("any plank", "a stick"). Since
 * logs, planks and sticks are disjoint pools, the deposited pile is reserved/consumed per cost with
 * no cross-pool contention.
 *
 * @param kind    which matcher flavour
 * @param ref     FAMILY → a {@link WoodFamily#key()} ("minecraft:oak"); TAG → an item-tag id
 *                ("minecraft:planks"); ITEM → an item id ("minecraft:stick")
 * @param perUnit how many matching items one crafted unit costs
 */
@ApiStatus.Internal
public record Cost(Kind kind, String ref, int perUnit) {
    public enum Kind { FAMILY, TAG, ITEM }

    /** Budget categories for the tabletop's per-type readout. */
    public enum Category { LOG, PLANK, STICK, OTHER }

    /** True if {@code stack} satisfies this cost (one item of the matched kind). */
    public boolean matches(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return switch (kind) {
            case FAMILY -> {
                WoodFamily fam = WoodFamily.fromLog(stack.getItem());
                yield fam != null && fam.key().equals(ref);
            }
            case TAG -> {
                ResourceLocation id = ResourceLocation.tryParse(ref);
                yield id != null && stack.is(TagKey.create(Registries.ITEM, id));
            }
            case ITEM -> {
                ResourceLocation id = ResourceLocation.tryParse(ref);
                yield id != null && id.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            }
        };
    }

    /** Which budget pool this cost draws from, for the per-type tabletop readout. */
    public Category category() {
        return categoryOf(kind, ref);
    }

    /** Classifies a deposited pile stack into a budget pool (mirrors {@link #category()}). */
    public static Category categoryOf(ItemStack stack) {
        if (WoodFamily.isBudgetLog(stack)) return Category.LOG;
        if (stack.is(ItemTags.PLANKS)) return Category.PLANK;
        if (stack.is(net.neoforged.neoforge.common.Tags.Items.RODS_WOODEN)) return Category.STICK;
        return Category.OTHER;
    }

    private static Category categoryOf(Kind kind, String ref) {
        return switch (kind) {
            case FAMILY -> Category.LOG;
            case TAG -> "minecraft:planks".equals(ref) ? Category.PLANK
                : ref.contains("rod") ? Category.STICK : Category.OTHER;
            case ITEM -> "minecraft:stick".equals(ref) ? Category.STICK : Category.OTHER;
        };
    }

    public CompoundTag save() {
        CompoundTag c = new CompoundTag();
        c.putString("K", kind.name());
        c.putString("Ref", ref);
        c.putInt("Per", perUnit);
        return c;
    }

    public static Cost load(CompoundTag c) {
        return new Cost(Kind.valueOf(c.getString("K")), c.getString("Ref"), c.getInt("Per"));
    }
}
