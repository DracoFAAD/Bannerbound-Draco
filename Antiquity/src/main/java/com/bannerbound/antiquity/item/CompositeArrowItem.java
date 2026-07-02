package com.bannerbound.antiquity.item;

import java.util.List;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.entity.CompositeArrowEntity;
import com.bannerbound.core.api.quality.QualityTier;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * The modular arrow — ONE bow-ammo item assembled from three interchangeable parts (tip / shaft / back),
 * each stored as a data component (see {@link ArrowParts}). The tip and shaft metal weight scale the
 * shot's damage; the back scales accuracy (handled by {@code PrimitiveBowItem}); craftsmanship quality
 * scales on top. The inventory icon picks a layered combo model from the parts (the {@code arrow_combo}
 * property), and the in-flight {@link CompositeArrowEntity} carries the parts for the 3-layer render.
 * Replaces the old per-material flint/copper/tin/bronze arrow items.
 */
public class CompositeArrowItem extends ArrowItem {

    public CompositeArrowItem(Properties properties) {
        super(properties);
    }

    /** The icon is composited at render time from the data-driven part textures (BEWLR), so a
     *  modpack-added material's icon works with no model files. Client-only; never run on a server. */
    @Override
    public void initializeClient(java.util.function.Consumer<
            net.neoforged.neoforge.client.extensions.common.IClientItemExtensions> consumer) {
        consumer.accept(new net.neoforged.neoforge.client.extensions.common.IClientItemExtensions() {
            private com.bannerbound.antiquity.client.CompositeArrowItemRenderer renderer;
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    renderer = new com.bannerbound.antiquity.client.CompositeArrowItemRenderer();
                }
                return renderer;
            }
        });
    }

    @Override
    public AbstractArrow createArrow(Level level, ItemStack ammo, LivingEntity shooter,
                                     @Nullable ItemStack weapon) {
        CompositeArrowEntity arrow = new CompositeArrowEntity(level, shooter, ammo, weapon);
        arrow.setBaseDamage(arrow.getBaseDamage()
            * ArrowParts.damageMultiplier(ammo) * QualityTier.of(ammo).statMultiplier());
        return arrow;
    }

    /** Named for its tip once assembled ("Bronze Arrow", "Flint Arrow"); a bare, un-stamped stack
     *  (the generic order/min-stock entry) is just "Arrow". */
    @Override
    public Component getName(ItemStack stack) {
        String tip = stack.get(com.bannerbound.antiquity.BannerboundAntiquity.ARROW_TIP.get());
        if (tip == null) {
            return Component.translatable("item.bannerboundantiquity.arrow");
        }
        return Component.translatable("bannerboundantiquity.arrow.named", materialName(tip));
    }

    /** A part material's display name — its lang key if defined, else a humanized fallback of the id,
     *  so a modpack-added material reads nicely ("Steel") even without a lang entry. */
    private static Component materialName(String material) {
        StringBuilder pretty = new StringBuilder();
        for (String word : material.split("_")) {
            if (word.isEmpty()) continue;
            if (pretty.length() > 0) pretty.append(' ');
            pretty.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return Component.translatableWithFallback(
            "bannerboundantiquity.arrow.material." + material, pretty.toString());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        // Parts line.
        tooltip.add(Component.translatable("bannerboundantiquity.arrow.parts",
                materialName(ArrowParts.tip(stack)),
                materialName(ArrowParts.shaft(stack)),
                materialName(ArrowParts.back(stack)))
            .withStyle(ChatFormatting.GRAY));
        // Visual stat bars (5 pips each), scaled to indicative reference ranges (data-driven parts so
        // the bar is a feel, not an exact number — the real values live in the arrow_parts JSON).
        int damage = Mth.clamp((int) Math.round(ArrowParts.damageMultiplier(stack) / 1.6 * 5.0), 1, 5);
        // Accuracy is the inverse of inaccuracy: a tighter (lower) multiplier fills more pips.
        float inacc = ArrowParts.inaccuracyMultiplier(stack);
        int accuracy = Mth.clamp((int) Math.round((1.6 - inacc) / 1.1 * 5.0), 1, 5);
        int weight = Mth.clamp((int) Math.round(ArrowParts.weightPoints(stack) / 6.0 * 5.0), 0, 5);
        tooltip.add(statLine("damage", damage, ChatFormatting.RED));
        tooltip.add(statLine("accuracy", accuracy, ChatFormatting.AQUA));
        tooltip.add(statLine("weight", weight, ChatFormatting.GOLD));
    }

    private static Component statLine(String key, int filled, ChatFormatting fill) {
        MutableComponent line = Component.translatable("bannerboundantiquity.arrow.stat." + key)
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal("  "));
        for (int i = 0; i < 5; i++) {
            line.append(Component.literal("▮")
                .withStyle(i < filled ? fill : ChatFormatting.DARK_GRAY));
        }
        return line;
    }
}
