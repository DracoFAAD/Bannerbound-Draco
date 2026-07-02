package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.StoneCookingPotBlockEntity;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Crosshair readout for a placed stone cooking pot — a "what do I do next" hint so the fill-by-hand
 * flow (dip the pot in water → set it over a fire → add food) is discoverable in-world: "Right-click
 * with a water bucket to fill", "Place over a lit campfire", "Add food to cook a stew", "Cooking… 60%",
 * or the finished stew's name + servings left. Reads the client-synced block entity directly (no
 * payload), like {@link CrucibleStateHudLayer}.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class CookingPotStateHudLayer implements LayeredDraw.Layer {
    public static final CookingPotStateHudLayer INSTANCE = new CookingPotStateHudLayer();

    private CookingPotStateHudLayer() {}

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() == HitResult.Type.MISS) return;
        if (!(mc.level.getBlockEntity(hit.getBlockPos()) instanceof StoneCookingPotBlockEntity be)) return;

        Component text = hintFor(be);

        Font font = mc.font;
        int cx = graphics.guiWidth() / 2;
        int top = graphics.guiHeight() / 2 + 12;
        int half = font.width(text) / 2;
        graphics.fill(cx - half - 4, top - 3, cx + half + 4, top + font.lineHeight + 3, 0xC0000000);
        graphics.drawCenteredString(font, text, cx, top, 0xFFFFD27F);
    }

    private static Component hintFor(StoneCookingPotBlockEntity be) {
        if (be.hasStew()) {
            double per = Math.max(0.01, be.stew().foodPerServing());
            int servings = Math.max(1, (int) Math.round(be.remainingFoodValue() / per));
            return Component.translatable(be.stew().name())
                .append(Component.translatable(servings == 1
                    ? "bannerboundantiquity.hud.cooking_pot.servings_one"
                    : "bannerboundantiquity.hud.cooking_pot.servings_many", servings));
        }
        if (!be.hasWater()) {
            return Component.translatable("bannerboundantiquity.hud.cooking_pot.fill");
        }
        if (be.isCooking()) {
            // Name the stew it's becoming (the matched recipe, or "Stew" until the mix matches one).
            Component name = Component.translatable(be.previewName());
            return be.isHeated()
                ? name.copy().append(Component.translatable("bannerboundantiquity.hud.cooking_pot.cooking",
                    Math.round(be.cookFraction() * 100)))
                : name.copy().append(Component.translatable("bannerboundantiquity.hud.cooking_pot.needs_fire"));
        }
        // Water, no ingredients, no stew yet.
        return be.isHeated()
            ? Component.translatable("bannerboundantiquity.hud.cooking_pot.add_food")
            : Component.translatable("bannerboundantiquity.hud.cooking_pot.place_over_fire");
    }
}
