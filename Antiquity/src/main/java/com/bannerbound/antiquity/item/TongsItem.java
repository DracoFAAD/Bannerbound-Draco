package com.bannerbound.antiquity.item;

import net.minecraft.world.item.Item;

/**
 * Crucible tongs — held in the <b>other hand</b> to lift a molten crucible without cooking yourself.
 * A freshly-melted crucible is a ~1000°C clay pot; carrying one bare-handed sets you alight and burns
 * you each second (see {@link CrucibleItem#inventoryTick}). With tongs in the opposite hand the heat
 * goes into the tongs' durability instead of your health.
 *
 * <p>The material is the whole story: <b>green-wood</b> tongs char out fast (low durability — the
 * authentic pre-metal stopgap that lets you lift your very first melt), while cast <b>tin / copper /
 * bronze</b> tongs last far longer. Tongs are not a weapon or a digging tool — durability is their
 * only stat; {@code durability(n)} already caps the stack at 1.
 */
public class TongsItem extends Item {
    public TongsItem(Properties properties, int durability) {
        super(properties.durability(durability));
    }
}
