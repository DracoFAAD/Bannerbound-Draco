package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Beauty-debug overlay. While {@link ClientBeautyDebug#isEnabled()}, draws the name and resolved
 * appeal of whatever block the player is looking at, just below the crosshair.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class BeautyDebugHudLayer implements LayeredDraw.Layer {
    public static final BeautyDebugHudLayer INSTANCE = new BeautyDebugHudLayer();

    private BeautyDebugHudLayer() {
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        if (!ClientBeautyDebug.isEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() == HitResult.Type.MISS) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return;

        Block block = state.getBlock();
        Component name = block.getName();

        // The appeal AFTER diminishing returns for THIS specific block comes from the server,
        // already resolved against the block's OWNING settlement's culture styles — so it's the
        // same for every viewer (the local ClientBlockAppealState is culture-relative to YOUR own
        // settlement and would desync between players). The local value is only a placeholder
        // shown for the instant before the server reply for this position arrives. The reply also
        // flips the label to "Home appeal" so the player can see which scope drove the value.
        float shown = ClientBlockAppealState.appealOf(block);
        boolean inHouse = false;
        if (ClientBeautyDebug.hasResultFor(pos)) {
            inHouse = ClientBeautyDebug.resultInHouse();
            shown = ClientBeautyDebug.resultAppeal();
        }
        String appealKey = inHouse
            ? "bannerbound.tooltip.home_appeal"
            : "bannerbound.tooltip.appeal";
        Component appealLine = Component.translatable(appealKey,
            String.format("%.3f", shown)).withStyle(ChatFormatting.LIGHT_PURPLE);

        Font font = mc.font;
        int cx = graphics.guiWidth() / 2;
        int top = graphics.guiHeight() / 2 + 12;
        int half = Math.max(font.width(name), font.width(appealLine)) / 2;

        graphics.fill(cx - half - 4, top - 3,
            cx + half + 4, top + 2 * font.lineHeight + 3, 0xC0000000);
        graphics.drawCenteredString(font, name, cx, top, 0xFFFFFFFF);
        graphics.drawCenteredString(font, appealLine, cx, top + font.lineHeight + 1, 0xFFFFFFFF);
    }
}
