package com.bannerbound.antiquity.recipe;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;

/**
 * A data-driven NAMED stew recipe (the cooking pot), matched by the <b>set of ingredient types</b>
 * present — counts don't change a stew's identity. Four cooked chickens + one carrot is still chicken
 * stew, just heartier; one chicken + seven carrots is still chicken stew, just mostly carrot value
 * (cheaper, less filling). The cooked food value is the sum of every ingredient's value × how many
 * were added, all times {@code bonus} — so more (and richer) ingredients make a more nourishing pot.
 *
 * <p>An ingredient is a concrete {@code item} <i>or</i> a {@code tag} (e.g. a generic "vegetable stew"
 * from any mix of vegetables). A recipe matches when every placed type satisfies one of its
 * ingredients AND every ingredient is satisfied by some placed type. Concrete recipes are preferred
 * over tag recipes ({@code StewRecipeManager#findMatch}), so a single beetroot is "Beetroot Stew", but
 * carrot + beetroot (no specific recipe) falls back to "Vegetable Stew".
 *
 * <p>Each ingredient's value defaults to its registered food value; an optional per-ingredient
 * {@code value} supplies one for items with no standalone food value (e.g. mushrooms). Loaded from
 * {@code data/<namespace>/stew_recipes/*.json}; unmatched ingredient sets still cook into a generic
 * stew, so named recipes are pure overrides, never gates.
 *
 * <p>Examples:
 * <pre>
 * { "ingredients": [ { "item": "minecraft:cooked_chicken" }, { "item": "minecraft:carrot" } ],
 *   "name": "stew.chicken", "tint": "C68642" }
 * { "ingredients": [ { "tag": "bannerboundantiquity:stew_vegetables" } ],
 *   "name": "stew.vegetable", "tint": "6E8B3D", "bonus": 1.15 }
 * </pre>
 */
@ApiStatus.Internal
public record StewRecipe(List<Ing> ingredients, String name, int tint, double bonus,
                         int servings, int cookTicks, List<MobEffectInstance> effects) {

    /** One stew ingredient: a concrete {@code item} OR a {@code tag}, with an optional cooking food
     *  value ({@code < 0} = use the matched item's own registered food value). */
    public record Ing(Optional<Item> item, Optional<TagKey<Item>> tag, double value) {
        public static final Codec<Ing> CODEC = RecordCodecBuilder.create(in -> in.group(
            BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("item").forGetter(Ing::item),
            TagKey.codec(Registries.ITEM).optionalFieldOf("tag").forGetter(Ing::tag),
            Codec.DOUBLE.optionalFieldOf("value", -1.0).forGetter(Ing::value)
        ).apply(in, Ing::new));

        /** True if {@code it} satisfies this ingredient (the exact item, or a member of the tag). */
        public boolean matchesItem(Item it) {
            if (item.isPresent()) return it == item.get();
            if (tag.isPresent()) return it.builtInRegistryHolder().is(tag.get());
            return false;
        }

        public boolean isTag() { return item.isEmpty() && tag.isPresent(); }
    }

    public static final Codec<StewRecipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Ing.CODEC.listOf().fieldOf("ingredients").forGetter(StewRecipe::ingredients),
        Codec.STRING.fieldOf("name").forGetter(StewRecipe::name),
        GrogRecipe.TINT_CODEC.optionalFieldOf("tint", 0xB5651D).forGetter(StewRecipe::tint),
        // The cook bonus: cooked stew is worth this × the sum of its ingredients' food value.
        Codec.DOUBLE.optionalFieldOf("bonus", 1.25).forGetter(StewRecipe::bonus),
        Codec.INT.optionalFieldOf("servings", 6).forGetter(StewRecipe::servings),
        Codec.INT.optionalFieldOf("cook_ticks", 400).forGetter(StewRecipe::cookTicks),
        GrogRecipe.EFFECT_CODEC.listOf().optionalFieldOf("effects", List.of()).forGetter(StewRecipe::effects)
    ).apply(instance, StewRecipe::new));

    /** Whether the SET of placed ingredient types is exactly covered by this recipe: every placed type
     *  satisfies some ingredient, and every ingredient is satisfied by some placed type. */
    public boolean matchesTypes(Set<Item> placedTypes) {
        for (Item it : placedTypes) {
            boolean covered = false;
            for (Ing ing : ingredients) {
                if (ing.matchesItem(it)) { covered = true; break; }
            }
            if (!covered) return false;
        }
        for (Ing ing : ingredients) {
            boolean present = false;
            for (Item it : placedTypes) {
                if (ing.matchesItem(it)) { present = true; break; }
            }
            if (!present) return false;
        }
        return true;
    }

    /** The cooking food value this recipe assigns {@code item}, or {@code < 0} to use the item's own. */
    public double valueFor(Item item) {
        for (Ing ing : ingredients) {
            if (ing.matchesItem(item)) return ing.value();
        }
        return -1.0;
    }

    /** How many of this recipe's ingredients are tag-based (used to prefer specific recipes). */
    public int tagIngredientCount() {
        int n = 0;
        for (Ing ing : ingredients) {
            if (ing.isTag()) n++;
        }
        return n;
    }
}
