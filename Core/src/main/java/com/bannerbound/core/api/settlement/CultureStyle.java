package com.bannerbound.core.api.settlement;

import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * A culture style (Forest, Desert, Dark Oak, …) loaded from a JSON datapack file. A style is a
 * named bundle of two override tables:
 * <ul>
 *   <li><b>Per-block appeal overrides</b> — where a style lists a block, that value <b>replaces</b>
 *       the block's base appeal entirely (see {@link AppealResolver}). A Desert town rewards
 *       sandstone; a Forest town rewards wood.</li>
 *   <li><b>Per-item food overrides</b> — same semantics for the food-deposit value of an item
 *       (see {@code FoodValueLoader.effective}). A Desert town values cactus higher than a
 *       generic settlement; a Forest town values berries higher.</li>
 * </ul>
 *
 * <p>Picking a style at settlement founding is how a player declares a biome / culture aesthetic.
 *
 * @param id             style id, matching its JSON file stem (e.g. {@code "forest"})
 * @param nameKey        translation key for the player-facing style name
 * @param imageKey       ResourceLocation string of the preview image shown in the founding picker
 *                       (e.g. {@code "bannerbound:textures/gui/culture/forest.png"})
 * @param overrides      per-block appeal values that override the base appeal for those blocks
 * @param foodOverrides  per-item food values that override the base food value for those items
 */
@ApiStatus.Internal
public record CultureStyle(String id, String nameKey, String imageKey,
                            Map<Block, Float> overrides,
                            Map<Item, Float> foodOverrides) {
    /** Whether this style sets an appeal value for {@code block}. */
    public boolean hasOverride(Block block) {
        return overrides.containsKey(block);
    }

    /** This style's appeal value for {@code block}; only meaningful when {@link #hasOverride}. */
    public float override(Block block) {
        return overrides.getOrDefault(block, 0f);
    }

    /** Whether this style sets a food value for {@code item}. */
    public boolean hasFoodOverride(Item item) {
        return foodOverrides.containsKey(item);
    }

    /** This style's food value for {@code item}; only meaningful when {@link #hasFoodOverride}. */
    public float foodOverride(Item item) {
        return foodOverrides.getOrDefault(item, 0f);
    }
}
