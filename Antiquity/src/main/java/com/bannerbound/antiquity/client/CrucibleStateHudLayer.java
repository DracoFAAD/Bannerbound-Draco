package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.CrucibleBlockEntity;
import com.bannerbound.antiquity.item.CrucibleContents;
import com.bannerbound.antiquity.metalworking.MetalworkingItems;

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

/** Crosshair readout for a placed crucible: its charge (or molten metal + mB) and what it resolves to. */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class CrucibleStateHudLayer implements LayeredDraw.Layer {
    public static final CrucibleStateHudLayer INSTANCE = new CrucibleStateHudLayer();

    private CrucibleStateHudLayer() {}

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() == HitResult.Type.MISS) return;
        if (!(mc.level.getBlockEntity(hit.getBlockPos()) instanceof CrucibleBlockEntity be)) return;
        CrucibleContents c = be.contents();
        if (c.isEmpty()) return;

        String line;
        if (c.molten()) {
            line = "Molten " + cap(c.metalId()) + " — " + c.totalMb() + " mB";
        } else {
            MetalworkingItems.MeltValue r = MetalworkingItems.resolveCharge(c.charge());
            line = r == null
                ? c.charge().size() + " item(s)"
                : c.charge().size() + " item(s) → " + cap(r.metalId()) + " " + r.mb() + " mB";
        }

        Font font = mc.font;
        Component text = Component.literal(line);
        int cx = graphics.guiWidth() / 2;
        int top = graphics.guiHeight() / 2 + 12;
        int half = font.width(text) / 2;
        graphics.fill(cx - half - 4, top - 3, cx + half + 4, top + font.lineHeight + 3, 0xC0000000);
        graphics.drawCenteredString(font, text, cx, top, 0xFFFFD27F);
    }

    private static String cap(String id) {
        return id == null || id.isEmpty() ? "Metal" : Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }
}
