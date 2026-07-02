package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.BloomeryBlock;
import com.bannerbound.antiquity.block.entity.BloomeryBlockEntity;
import com.bannerbound.antiquity.block.entity.BloomeryHeat;
import com.bannerbound.antiquity.item.CrucibleContents;
import com.bannerbound.antiquity.recipe.BloomeryRecipe;
import com.bannerbound.antiquity.recipe.BloomeryRecipeManager;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The bloomery temperature readout (METALWORKING_PLAN.md Part 1). While the player looks at a
 * bloomery, draws a dark box below the crosshair with three rows: the fire-driven temperature judged
 * against the active band ({@code 856°C (Good)}), the contents/output line ({@code Output: Molten
 * Copper (200mB)}), and a progress bar. Reads the looked-at bloomery's synced block-entity state
 * directly — no extra polling payloads.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class BloomeryStateHudLayer implements LayeredDraw.Layer {
    public static final BloomeryStateHudLayer INSTANCE = new BloomeryStateHudLayer();

    private static final int GREEN = 0xFF55FF55;
    private static final int YELLOW = 0xFFFFE255;
    private static final int RED = 0xFFFF5555;
    private static final int GRAY = 0xFFB0B0B0;
    private static final int WHITE = 0xFFFFFFFF;

    private BloomeryStateHudLayer() {}

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() == HitResult.Type.MISS) return;
        BlockPos pos = hit.getBlockPos();
        if (!(mc.level.getBlockState(pos).getBlock() instanceof BloomeryBlock)) return;
        BloomeryBlockEntity be = BloomeryBlock.getController(mc.level, pos);
        if (be == null) return;

        boolean fireLit = be.isLit();
        float temp = be.temperatureC();
        ItemStack held = be.getHeldItem();

        // Resolve the active band + the line-2 verb from the contents (the two independent axes).
        int low = 0, high = 0;
        boolean hasBand = false;
        Component line2;
        float total = 0;
        if (held.is(BannerboundAntiquity.CRUCIBLE.get())) {
            CrucibleContents c = held.get(BannerboundAntiquity.CRUCIBLE_CONTENTS.get());
            if (c == null || c.isEmpty()) {
                line2 = Component.translatable("bannerboundantiquity.hud.bloomery.empty")
                    .withStyle(s -> s.withColor(GRAY));
            } else if (c.molten()) {
                line2 = Component.translatable("bannerboundantiquity.hud.bloomery.molten",
                        cap(c.dominantMetal()), c.totalMb())
                    .withStyle(s -> s.withColor(WHITE));
            } else {
                // Pre-melt charge: resolve what it will become to show the target band.
                var resolved = com.bannerbound.antiquity.metalworking.MetalworkingItems
                    .resolveCharge(c.charge());
                if (resolved == null) {
                    line2 = Component.translatable("bannerboundantiquity.hud.bloomery.empty")
                        .withStyle(s -> s.withColor(GRAY));
                } else {
                    int[] band = BloomeryHeat.meltBand(resolved.metalId());
                    low = band[0]; high = band[1]; hasBand = true;
                    total = BloomeryBlockEntity.CRUCIBLE_MELT_TICKS;
                    line2 = Component.translatable("bannerboundantiquity.hud.bloomery.melt",
                            cap(resolved.metalId()), resolved.mb())
                        .withStyle(s -> s.withColor(WHITE));
                }
            }
        } else if (held.isEmpty()) {
            line2 = Component.translatable("bannerboundantiquity.hud.bloomery.empty")
                .withStyle(s -> s.withColor(GRAY));
        } else {
            BloomeryRecipe recipe = BloomeryRecipeManager.find(held);
            if (recipe == null) {
                line2 = Component.translatable("bannerboundantiquity.hud.bloomery.invalid_recipe")
                    .withStyle(s -> s.withColor(GRAY));
            } else {
                low = recipe.bandLow(); high = recipe.bandHigh(); hasBand = true;
                total = Math.round(recipe.ticks() * (1.0F + (held.getCount() - 1) * 0.5F));
                line2 = Component.translatable("bannerboundantiquity.hud.bloomery.output",
                        recipe.result().getHoverName())
                    .withStyle(s -> s.withColor(WHITE));
            }
        }

        // Line 1: temperature, judged against the band.
        BloomeryHeat.Band band = BloomeryHeat.classify(fireLit, hasBand, temp, low, high);
        String labelKey = switch (band) {
            case GOOD -> "bannerboundantiquity.hud.bloomery.band.good";
            case OKAY -> "bannerboundantiquity.hud.bloomery.band.okay";
            case TOO_LOW -> "bannerboundantiquity.hud.bloomery.band.too_low";
            case TOO_HIGH -> "bannerboundantiquity.hud.bloomery.band.too_high";
            case NO_FIRE -> "bannerboundantiquity.hud.bloomery.band.no_fire";
            case NONE -> null;
        };
        int tempColor = switch (band) {
            case GOOD -> GREEN;
            case OKAY -> YELLOW;
            case TOO_LOW, TOO_HIGH -> RED;
            default -> GRAY;
        };
        int tempC = Math.round(temp);
        Component line1 = (labelKey == null
                ? Component.translatable("bannerboundantiquity.hud.bloomery.temp", tempC)
                : Component.translatable("bannerboundantiquity.hud.bloomery.temp_band", tempC,
                    Component.translatable(labelKey)))
            .withStyle(s -> s.withColor(tempColor).withBold(true));

        Font font = mc.font;
        int cx = graphics.guiWidth() / 2;
        int top = graphics.guiHeight() / 2 + 12;
        int barW = 110;
        int half = Math.max(Math.max(font.width(line1), font.width(line2)), barW) / 2;
        int boxBottom = top + 2 * font.lineHeight + 8;

        graphics.fill(cx - half - 4, top - 3, cx + half + 4, boxBottom, 0xC0000000);
        graphics.drawCenteredString(font, line1, cx, top, WHITE);
        graphics.drawCenteredString(font, line2, cx, top + font.lineHeight + 1, WHITE);

        // Progress bar along the bottom.
        if (total > 0) {
            float frac = Math.min(1.0F, be.getSmeltProgress() / total);
            int barTop = top + 2 * font.lineHeight + 3;
            int x0 = cx - barW / 2, x1 = cx + barW / 2;
            graphics.fill(x0, barTop, x1, barTop + 3, 0xFF333333);
            graphics.fill(x0, barTop, x0 + (int) ((x1 - x0) * frac), barTop + 3, 0xFFFFFFFF);
        }
    }

    private static String cap(String id) {
        return id.isEmpty() ? "Metal" : Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }
}
