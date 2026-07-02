package com.bannerbound.core.api.settlement;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.world.level.block.Block;

/**
 * A culture <b>palette</b> loaded from a JSON datapack file (see
 * {@link com.bannerbound.core.api.settlement.data.PaletteLoader}). A palette is a named bundle of
 * blocks, each carrying an appeal <b>bonus</b>. Unlike a {@link CultureStyle} (whose per-block
 * values <i>replace</i> the base appeal), a palette's bonus is <b>added</b> on top of the resolved
 * appeal while the palette is one of the settlement's active palettes — see {@link AppealResolver}.
 * So a {@code Brown Haven} palette that grants {@code dirt +0.15} raises dirt's settlement-wide
 * appeal by 0.15 as long as it's active.
 *
 * <p>Palettes are unlocked via the culture/research tree: a node's {@code unlocks.palette: ["id"]}
 * folds into the flag {@code unlock.palette.<id>}, queried through
 * {@link com.bannerbound.core.api.research.ResearchManager#hasFlagEitherTree}.
 *
 * @param id      palette id, matching its JSON file stem (e.g. {@code "brown_haven"})
 * @param name    player-facing display name (a literal string, like culture styles)
 * @param bonuses per-block appeal bonus, insertion-ordered for stable UI rendering
 */
@ApiStatus.Internal
public record Palette(String id, String name, Map<Block, Float> bonuses) {
    /** Whether this palette grants a bonus for {@code block}. */
    public boolean has(Block block) {
        return bonuses.containsKey(block);
    }

    /** This palette's appeal bonus for {@code block} ({@code 0} when it doesn't list it). */
    public float bonus(Block block) {
        return bonuses.getOrDefault(block, 0f);
    }

    /** The palette's blocks in authoring order — drives the slot/list icon row in the UI. */
    public List<Block> blocks() {
        return List.copyOf(bonuses.keySet());
    }
}
