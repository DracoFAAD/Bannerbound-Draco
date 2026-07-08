package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * HUD layer ({@link LayeredDraw.Layer}) that draws the top-right "New Chronicle entry" toast.
 * Each frame it peeks the current ToastEntry from {@link ClientChronicleState}, slides it in
 * (easeOutCubic over ~220ms) and fades it out after ~3.9s; multiple pending entries collapse
 * into a "N new Chronicle entries" count. Singleton via INSTANCE.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ChronicleToastLayer implements LayeredDraw.Layer {
    public static final ChronicleToastLayer INSTANCE = new ChronicleToastLayer();

    private static final int WIDTH = 230;
    private static final int HEIGHT = 44;

    private ChronicleToastLayer() {
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        ClientChronicleState.ToastEntry toast = ClientChronicleState.peekToast();
        if (toast == null) return;

        long ageMs = net.minecraft.Util.getMillis() - toast.startedAtMs();
        float t = Math.min(1f, ageMs / 220f);
        float out = ageMs > 3900L ? Math.max(0f, 1f - (ageMs - 3900L) / 600f) : 1f;
        float ease = PolishedScreen.easeOutCubic(t) * out;
        int x = mc.getWindow().getGuiScaledWidth() - 8 - Math.round(WIDTH * ease);
        int y = 16;
        int alpha = Math.round(0xDD * out);

        graphics.fill(x, y, x + WIDTH, y + HEIGHT, (alpha << 24) | 0x101820);
        graphics.renderOutline(x, y, WIDTH, HEIGHT, (Math.round(0xAA * out) << 24) | 0xD4AF37);
        graphics.fill(x, y, x + 3, y + HEIGHT, (Math.round(0xCC * out) << 24) | 0xD4AF37);

        // The toast fires the instant an entry unlocks - before the item is obtained - so the
        // icon must bypass the unknown-item question-mark swap or every toast reads as "?".
        UnknownItemHelper.setBypassUnknownSwap(true);
        try {
            graphics.renderItem(iconStack(toast.icon()), x + 11, y + 14);
        } finally {
            UnknownItemHelper.setBypassUnknownSwap(false);
        }

        Font font = mc.font;
        String title = toast.count() <= 1 ? "New Chronicle entry" : toast.count() + " new Chronicle entries";
        graphics.drawString(font, title, x + 36, y + 9, 0xFFFFD36A, false);
        String detail = toast.count() <= 1 ? toast.title() : "Press J to read";
        detail = trim(font, detail, WIDTH - 46);
        graphics.drawString(font, detail, x + 36, y + 23, 0xFFE6E6E6, false);
    }

    private static ItemStack iconStack(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id == null ? "" : id);
        Item item = rl == null ? Items.PAPER : BuiltInRegistries.ITEM.get(rl);
        if (item == Items.AIR) item = Items.PAPER;
        return new ItemStack(item);
    }

    private static String trim(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        String ellipsis = "...";
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width(ellipsis))) + ellipsis;
    }
}
