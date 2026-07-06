package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.ClaimEntry;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class SettlementIndicatorLayer implements LayeredDraw.Layer {
    public static final SettlementIndicatorLayer INSTANCE = new SettlementIndicatorLayer();

    private SettlementIndicatorLayer() {
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) {
            return;
        }
        Player player = mc.player;
        if (player == null) {
            return;
        }

        ChunkPos cp = new ChunkPos(player.blockPosition());
        ClaimEntry entry = ClientClaimState.getEntry(cp.toLong());
        if (entry == null) {
            return;
        }

        Component label = Component.translatable("bannerbound.hud.currently_in").withStyle(net.minecraft.ChatFormatting.GRAY);
        Component name = Component.literal(entry.settlementName())
            .withColor(ClientIdentityState.primaryRgb(entry.colorIndex()));
        Component combined = Component.empty().append(label).append(" ").append(name);

        // Top-LEFT, tucked directly BELOW the era/year banner (EraYearHudLayer ends ~y=32) so the
        // two read as one stacked cluster and neither sits in the center sightline.
        int textWidth = mc.font.width(combined);
        int x = 8;
        int y = 36;

        // Shrink in lockstep with the era/year banner above and the journal below so the whole
        // top-left cluster keeps the same on-screen fraction at any GUI scale (see HudScale).
        float uiScale = HudScale.factor(mc);
        graphics.pose().pushPose();
        graphics.pose().scale(uiScale, uiScale, 1f);
        graphics.fill(x - 3, y - 2, x + textWidth + 3, y + 11, 0x66000000);
        graphics.drawString(mc.font, combined, x, y, 0xFFFFFFFF);
        graphics.pose().popPose();
    }
}
