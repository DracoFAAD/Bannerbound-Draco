package com.bannerbound.antiquity.item;

/**
 * Flint Knife — the first real tool: a {@link KnifeItem} (3 dmg, 2.0 speed, low durability). The
 * crafting-stone carving and grass/leaf harvesting all live on the {@link KnifeItem} base, so this
 * is just the flint-tier stats.
 */
public class FlintKnifeItem extends KnifeItem {
    public static final int FLINT_KNIFE_DURABILITY = 26;

    public FlintKnifeItem(Properties properties) {
        super(properties, FLINT_KNIFE_DURABILITY, 3.0, 2.0);
    }
}
