package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.SetPenKeepPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * "How big a herd to keep?" — set a pen's harvest threshold. Opened by {@code OpenPenKeepPayload} when the
 * player plain-right-clicks an existing pen with the Foreman's Rod. The herder breeds/collects up to full
 * pen capacity, then culls mature surplus ABOVE this adult threshold into meat for the larder. {@code keep
 * = 0} is Auto (keep up to full capacity → never harvest). The choice is sent via {@link SetPenKeepPayload}.
 *
 * <p>The panel makes the trade visual: a herd bar splits the pen's slots into Kept (green), Harvest-now
 * (red) and Empty/breeding-room (grey), with a draggable threshold tick, plus a plain-language line that
 * spells out what the current setting does.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class PenKeepScreen extends PolishedScreen {
    private static final int PANEL_WIDTH = 250;
    private static final int PANEL_HEIGHT = 214;
    private static final int ACCENT = 0xFFE2C065;   // warm gold — culling feeds the larder (food theme)

    // Herd bar geometry, relative to the panel origin.
    private static final int BAR_DX = 16;
    private static final int BAR_DY = 60;
    private static final int BAR_H = 14;

    private static final int KEPT_COLOR = 0xFF5FBF5A;   // green — adults the herder keeps
    private static final int CULL_COLOR = 0xFFCF5B4E;   // red   — surplus harvested now
    private static final int EMPTY_COLOR = 0xFF34343A;  // grey  — empty slots the herd breeds into

    private final BlockPos penPos;
    private final String animalId;
    private final int mature;
    private final int capacity;
    private final int kills;
    private int keep;

    private int panelX;
    private int panelY;
    private Component animalName = Component.empty();
    private ItemStack icon = ItemStack.EMPTY;

    public PenKeepScreen(BlockPos penPos, String animalId, int mature, int capacity, int kills, int keep) {
        super(Component.translatable("bannerbound.pen_keep.title"));
        this.penPos = penPos;
        this.animalId = animalId;
        this.mature = Math.max(0, mature);
        this.capacity = Math.max(1, capacity);
        this.kills = Math.max(0, kills);
        this.keep = Mth.clamp(keep, 0, this.capacity);
    }

    @Override
    protected void init() {
        this.panelX = (this.width - PANEL_WIDTH) / 2;
        this.panelY = (this.height - PANEL_HEIGHT) / 2;

        this.animalName = EntityType.byString(animalId)
            .<Component>map(EntityType::getDescription).orElse(Component.literal(animalId));
        // Icon = the pen's actual yield (real food/hide items), not a meta spawn egg — this is a harvest
        // screen, so the meat the cull produces reads naturally next to the "<Animal> pen" header.
        this.icon = new ItemStack(productItem(animalId));

        int rowY = panelY + 92;
        this.addRenderableWidget(PolishButton.polished(Component.literal("−"), b -> setKeep(keep - 1))
            .bounds(panelX + 18, rowY, 22, 20).accent(primaryAccent()).build());
        this.addRenderableWidget(PolishButton.polished(Component.literal("+"), b -> setKeep(keep + 1))
            .bounds(panelX + PANEL_WIDTH - 40, rowY, 22, 20).accent(primaryAccent()).build());

        this.addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.pen_keep.apply"), b -> {
                    PacketDistributor.sendToServer(new SetPenKeepPayload(penPos, keep));
                    this.onClose();
                }).bounds(panelX + 16, panelY + PANEL_HEIGHT - 50, PANEL_WIDTH - 32, 20)
                .accent(primaryAccent()).build());
        this.addRenderableWidget(PolishButton.polished(
                Component.translatable("gui.cancel"), b -> this.onClose())
            .bounds(panelX + 16, panelY + PANEL_HEIGHT - 26, PANEL_WIDTH - 32, 20)
            .accent(primaryAccent()).build());
    }

    @Override
    protected void renderPolishedBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Panel: settlement-identity chrome + a 1px inner dark line for depth.
        drawIdentityPanel(g, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, identityAccents);
        g.fill(panelX + 1, panelY + 1, panelX + PANEL_WIDTH - 1, panelY + 2, 0x33000000);

        // Header: animal icon + "<Animal> pen" title, then an accent divider.
        if (!icon.isEmpty()) g.renderItem(icon, panelX + 14, panelY + 11);
        g.drawString(this.font, Component.translatable("bannerbound.pen_keep.header", animalName),
            panelX + 36, panelY + 15, GuiPalette.TITLE, true);
        drawIdentityDivider(g, panelX + 14, panelY + 32, PANEL_WIDTH - 28, identityAccents);

        // Herd summary line.
        g.drawString(this.font, Component.translatable("bannerbound.pen_keep.herd", mature, capacity),
            panelX + 16, panelY + 42, 0xFFB8B8C0, false);

        // ─── The herd bar: Kept (green) | Harvest now (red) | Empty (grey), with a threshold tick ───
        int barX0 = panelX + BAR_DX;
        int barX1 = panelX + PANEL_WIDTH - BAR_DX;
        int barW = barX1 - barX0;
        int barY = panelY + BAR_DY;
        int effKeep = keep <= 0 ? capacity : keep;   // Auto keeps the whole pen
        int keptAdults = Math.min(mature, effKeep);
        int cullNow = Math.max(0, mature - effKeep);
        int greenEnd = barX0 + Math.round(barW * (keptAdults / (float) capacity));
        int redEnd = barX0 + Math.round(barW * ((keptAdults + cullNow) / (float) capacity));
        g.fill(barX0, barY, barX1, barY + BAR_H, EMPTY_COLOR);
        if (greenEnd > barX0) g.fill(barX0, barY, greenEnd, barY + BAR_H, KEPT_COLOR);
        if (redEnd > greenEnd) g.fill(greenEnd, barY, redEnd, barY + BAR_H, CULL_COLOR);
        g.renderOutline(barX0, barY, barW, BAR_H, 0xFF000000);
        // Threshold tick (where the herd settles). Hidden for Auto (it sits at the far edge = keep-all).
        if (keep > 0) {
            int tickX = barX0 + Math.round(barW * (effKeep / (float) capacity));
            tickX = Mth.clamp(tickX, barX0, barX1 - 1);
            g.fill(tickX - 1, barY - 3, tickX + 1, barY + BAR_H + 3, 0xFFFFFFFF);
        }

        // Legend (only the swatches that apply, so it stays honest).
        int legendY = panelY + 80;
        int lx = barX0;
        lx = legendSwatch(g, lx, legendY, KEPT_COLOR, "bannerbound.pen_keep.legend_keep");
        if (cullNow > 0) lx = legendSwatch(g, lx, legendY, CULL_COLOR, "bannerbound.pen_keep.legend_cull");
        legendSwatch(g, lx, legendY, EMPTY_COLOR, "bannerbound.pen_keep.legend_empty");

        // Threshold value, centered between the − / + buttons.
        Component value = keep <= 0
            ? Component.translatable("bannerbound.pen_keep.auto")
            : Component.translatable("bannerbound.pen_keep.keep_n", keep);
        int valColor = keep <= 0 ? ACCENT : 0xFF6FE06A;
        int vw = this.font.width(value);
        g.drawString(this.font, value, panelX + (PANEL_WIDTH - vw) / 2, panelY + 96, valColor, true);

        // Plain-language explanation of what this setting does.
        Component explain = keep <= 0
            ? Component.translatable("bannerbound.pen_keep.explain_auto", capacity)
            : cullNow > 0
                ? Component.translatable("bannerbound.pen_keep.explain_cull", cullNow, keep)
                : Component.translatable("bannerbound.pen_keep.explain_room", keep);
        PolishedScreen.drawWrapped(g, this.font, explain, panelX + 16, panelY + 118,
            PANEL_WIDTH - 32, 0xFFCFCFD6);

        // Lifetime harvest footnote.
        g.drawString(this.font, Component.translatable("bannerbound.pen_keep.harvested", kills),
            panelX + 16, panelY + PANEL_HEIGHT - 64, 0xFF8A8A92, false);
    }

    /** Draws a small colour swatch + label; returns the x AFTER the label (for the next swatch). */
    private int legendSwatch(GuiGraphics g, int x, int y, int color, String key) {
        g.fill(x, y + 1, x + 7, y + 8, color);
        g.renderOutline(x, y + 1, 7, 7, 0xFF000000);
        Component label = Component.translatable(key);
        g.drawString(this.font, label, x + 11, y, 0xFFA8A8B0, false);
        return x + 11 + this.font.width(label) + 12;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int barX0 = panelX + BAR_DX;
            int barX1 = panelX + PANEL_WIDTH - BAR_DX;
            int barY = panelY + BAR_DY;
            if (mouseX >= barX0 - 1 && mouseX <= barX1 + 1 && mouseY >= barY - 4 && mouseY <= barY + BAR_H + 4) {
                double frac = (mouseX - barX0) / (double) (barX1 - barX0);
                setKeep((int) Math.round(Mth.clamp(frac, 0.0, 1.0) * capacity));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0) {
            int barX0 = panelX + BAR_DX;
            int barX1 = panelX + PANEL_WIDTH - BAR_DX;
            int barY = panelY + BAR_DY;
            if (mouseY >= barY - 8 && mouseY <= barY + BAR_H + 8) {
                double frac = (mouseX - barX0) / (double) (barX1 - barX0);
                setKeep((int) Math.round(Mth.clamp(frac, 0.0, 1.0) * capacity));
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    /** The food/hide a pen of this animal yields when culled — used as the header icon (real items, no
     *  meta spawn egg). Non-meat stock (horses, llamas…) falls back to leather, the universal herd hide. */
    private static Item productItem(String animalId) {
        return switch (animalId) {
            case "minecraft:cow", "minecraft:mooshroom" -> Items.BEEF;
            case "minecraft:sheep" -> Items.MUTTON;
            case "minecraft:pig" -> Items.PORKCHOP;
            case "minecraft:chicken" -> Items.CHICKEN;
            case "minecraft:rabbit" -> Items.RABBIT;
            default -> Items.LEATHER;
        };
    }

    private void setKeep(int v) {
        this.keep = Mth.clamp(v, 0, capacity);
    }
}
