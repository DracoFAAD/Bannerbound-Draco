package com.bannerbound.core.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.blaze3d.systems.RenderSystem;

import com.bannerbound.core.network.JournalSyncPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Full journal view. This is generic: crises, quests, and tutorials all render through it. */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class JournalScreen extends PolishedScreen {
    private static final ResourceLocation CHECKBOX =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/checkbox.png");
    private static final ResourceLocation CHECKBOX_CHECKED =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/checkbox_checked.png");
    private static final ResourceLocation CHECKBOX_X =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/checkbox_x.png");

    private int scroll;

    public JournalScreen() {
        super(Component.translatable("bannerbound.journal.title"));
    }

    @Override
    protected void renderPolishedBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int w = panelWidth();
        int h = panelHeight();
        int x = (this.width - w) / 2;
        int y = (this.height - h) / 2;
        scroll = net.minecraft.util.Mth.clamp(scroll, 0, maxScroll());
        g.fill(x, y, x + w, y + h, 0xEE101014);
        g.renderOutline(x, y, w, h, 0xFFB88A3A);
        g.drawString(this.font, Component.translatable("bannerbound.journal.title")
            .withStyle(ChatFormatting.GOLD), x + 14, y + 12, 0xFFFFD77A, false);

        int contentTop = y + 34;
        int contentBottom = y + h - 12;
        int rowY = contentTop - scroll;
        List<JournalSyncPayload.Entry> entries = ClientJournalState.entries();
        if (entries.isEmpty()) {
            g.drawString(this.font, Component.translatable("bannerbound.journal.empty")
                .withStyle(ChatFormatting.GRAY), x + 14, rowY, 0xFFAAAAAA, false);
            return;
        }
        g.enableScissor(x + 8, contentTop, x + w - 8, contentBottom);
        for (JournalSyncPayload.Entry entry : entries) {
            int left = x + 12;
            int right = x + w - 12;
            int blockH = entryBlockHeight(entry, right - left);
            if (rowY + blockH >= contentTop && rowY <= contentBottom) {
                int bg = entry.resolved()
                    ? (entry.failed() ? 0x803A1818 : 0x80203220)
                    : ("CRISIS".equals(entry.type()) ? 0x80402D12 : 0x80202028);
                g.fill(left, rowY, right, rowY + blockH - 4, bg);
                g.drawString(this.font, Component.literal(section(entry.type())).withStyle(ChatFormatting.GRAY),
                    left + 6, rowY + 5, 0xFFAAAAAA, false);
                int titleColor = entry.failed() ? 0xFFFF7777 : (entry.resolved() ? 0xFF9FE09F : 0xFFFFFFFF);
                int titleX = left + 80;
                int headerBottom = PolishedScreen.drawWrapped(g, this.font, Component.literal(entry.title()),
                    titleX, rowY + 5, Math.max(40, right - titleX - 6), titleColor);
                int oy = Math.max(rowY + 19, headerBottom + 5);
                drawObjectives(g, entry, left + 14, oy, right - left - 28);
            }
            rowY += blockH;
        }
        g.disableScissor();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = net.minecraft.util.Mth.clamp(scroll - (int) Math.round(scrollY * 18), 0, maxScroll());
        return true;
    }

    private void drawObjectives(GuiGraphics g, JournalSyncPayload.Entry entry, int x, int y, int width) {
        boolean expanded = false;
        for (JournalSyncPayload.Objective objective : entry.objectives()) {
            int color = objective.complete() ? 0xFF79D279 : 0xFFDDDDDD;
            drawCheckbox(g, objective.complete(), entry.failed(), x + 2, y + 1, 10, 230);
            y = PolishedScreen.drawWrapped(g, this.font, Component.literal(objectiveText(objective)),
                x + 24, y, Math.max(1, width - 24), color);
            y += 3;
            if (!objective.complete() && !expanded && !objective.subSteps().isEmpty()) {
                for (String subStep : objective.subSteps()) {
                    if (subStep.isBlank()) continue;
                    g.drawString(this.font, "-", x + 34, y, 0xFFD4AF37, false);
                    y = PolishedScreen.drawWrapped(g, this.font, Component.literal(subStep),
                        x + 48, y, Math.max(1, width - 48), 0xFFCFC7B8);
                    y += 2;
                }
                expanded = true;
            }
        }
    }

    private int entryBlockHeight(JournalSyncPayload.Entry entry, int width) {
        int titleX = 80;
        int titleW = Math.max(40, width - titleX - 6);
        int headerH = Math.max(this.font.lineHeight + 1,
            PolishedScreen.wrappedLineCount(this.font, Component.literal(entry.title()), titleW)
                * (this.font.lineHeight + 1));
        int h = 10 + headerH + 5;
        int objectiveW = Math.max(1, width - 28);
        boolean expanded = false;
        for (JournalSyncPayload.Objective objective : entry.objectives()) {
            h += objectiveHeight(objectiveText(objective), objectiveW - 24) + 3;
            if (!objective.complete() && !expanded && !objective.subSteps().isEmpty()) {
                for (String subStep : objective.subSteps()) {
                    if (!subStep.isBlank()) h += objectiveHeight(subStep, objectiveW - 48) + 2;
                }
                expanded = true;
            }
        }
        return h + 8;
    }

    private int objectiveHeight(String text, int width) {
        return PolishedScreen.wrappedLineCount(this.font, Component.literal(text), Math.max(1, width))
            * (this.font.lineHeight + 1);
    }

    private int contentHeight() {
        int w = panelWidth();
        int blockW = w - 24;
        int total = 0;
        for (JournalSyncPayload.Entry entry : ClientJournalState.entries()) {
            total += entryBlockHeight(entry, blockW);
        }
        return total;
    }

    private int maxScroll() {
        int viewportH = panelHeight() - 46;
        return Math.max(0, contentHeight() - viewportH);
    }

    private int panelWidth() {
        return Math.max(220, Math.min(520, this.width - 40));
    }

    private int panelHeight() {
        return Math.max(180, Math.min(320, this.height - 36));
    }

    private static String objectiveText(JournalSyncPayload.Objective objective) {
        if (objective.progressText().isBlank()
                || (objective.complete() && objective.progressText().equalsIgnoreCase("Completed"))) {
            return objective.label();
        }
        return objective.label() + " - " + objective.progressText();
    }

    private static String section(String type) {
        if ("CRISIS".equals(type)) return "Crisis";
        if ("TUTORIAL".equals(type)) return "Tutorial";
        return "Quest";
    }

    private static void drawCheckbox(GuiGraphics graphics, boolean complete, boolean failed, int x, int y,
                                     int size, int alpha) {
        ResourceLocation texture = failed && !complete ? CHECKBOX_X : complete ? CHECKBOX_CHECKED : CHECKBOX;
        int color = failed && !complete ? 0xFFFF7777 : complete ? 0xFF7BFF79 : 0xFFDDDDDD;
        float a = Math.max(0f, Math.min(1f, alpha / 255f));
        RenderSystem.setShaderColor(((color >> 16) & 0xFF) / 255f,
            ((color >> 8) & 0xFF) / 255f,
            (color & 0xFF) / 255f, a);
        graphics.blit(texture, x, y, size, size, 0f, 0f, 16, 16, 16, 16);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }
}
