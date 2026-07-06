package com.bannerbound.core.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.SettlementColor;
import com.bannerbound.core.api.settlement.SettlementManager;
import com.bannerbound.core.api.settlement.SiteWarning;
import com.bannerbound.core.network.SettleRequestPayload;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Two-page settlement founding screen. Page 1 (IDENTITY) picks a name + banner color and leads
 * to page 2 (STYLE), where the player picks the settlement's culture style. The style governs
 * which blocks the settlement finds appealing (see the chunk-beauty / culture system).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class SettleScreen extends PolishedScreen {
    private enum Page { IDENTITY, STYLE }

    private static final int PANEL_WIDTH = 320;
    private static final int IDENTITY_PANEL_HEIGHT = 160;
    private static final int BANNER_AREA_WIDTH = 100;
    private static final int SWATCH_SIZE = 20;
    private static final int SWATCH_GAP = 4;

    // ─ Culture-style picker (page 2): a scrollable grid of image cards ─
    private static final int STYLE_PANEL_WIDTH = 340;
    private static final int STYLE_HEADER_H = 30;   // title + divider band
    private static final int STYLE_FOOTER_H = 40;   // back/confirm button band
    private static final int STYLE_PAD = 14;        // inner horizontal padding (card area inset)
    private static final int STYLE_COLS = 2;
    private static final int STYLE_COL_GAP = 12;
    private static final int STYLE_ROW_GAP = 10;
    private static final int STYLE_LABEL_H = 20;    // name bar beneath each card's image
    /** Cached [exists?1:0, width, height] per preview texture, read once from the resource pack. */
    private static final java.util.Map<net.minecraft.resources.ResourceLocation, int[]> IMG_SIZE =
        new java.util.HashMap<>();

    private Page page = Page.IDENTITY;

    // Persisted across page switches (widgets are rebuilt each time).
    private String nameText = "";
    private int selectedColor = 0;
    private String selectedStyleId = "";

    // Style-page scroll state + the current frame's card layout (rebuilt each init).
    private double styleScroll = 0;
    private int styleScrollMax = 0;
    private int styleViewLeft, styleViewTop, styleViewRight, styleViewBottom;
    private final java.util.List<StyleCard> styleCards = new java.util.ArrayList<>();

    private EditBox nameField;
    private Button confirmButton;

    /** Site warnings assessed server-side at the founding spot, shown on the identity page. */
    private final List<SiteWarning> siteWarnings;

    public SettleScreen(int siteWarningMask) {
        super(Component.translatable("bannerbound.settle.title"));
        this.siteWarnings = SiteWarning.fromMask(siteWarningMask);
    }

    @Override
    protected void init() {
        if (page == Page.IDENTITY) {
            initIdentityPage();
        } else {
            initStylePage();
        }
    }

    // ─── Page 1: name + color ───────────────────────────────────────────────────────────────

    private void initIdentityPage() {
        final int panelX = (this.width - PANEL_WIDTH) / 2;
        final int panelY = (this.height - IDENTITY_PANEL_HEIGHT) / 2;
        final int controlsX = panelX + BANNER_AREA_WIDTH;
        final int controlsWidth = PANEL_WIDTH - BANNER_AREA_WIDTH;

        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + IDENTITY_PANEL_HEIGHT, 0xFF101010);
            graphics.renderOutline(panelX, panelY, PANEL_WIDTH, IDENTITY_PANEL_HEIGHT, 0xFF606060);
            graphics.fill(panelX + BANNER_AREA_WIDTH, panelY + 8,
                panelX + BANNER_AREA_WIDTH + 1, panelY + IDENTITY_PANEL_HEIGHT - 8, 0xFF2A2A2A);
        });

        int nameY = panelY + 36;
        this.nameField = new EditBox(this.font, controlsX + 12, nameY, controlsWidth - 24, 20,
            Component.translatable("bannerbound.settle.name_label"));
        this.nameField.setMaxLength(SettlementManager.MAX_NAME_LENGTH);
        this.nameField.setValue(nameText);
        this.nameField.setResponder(s -> {
            nameText = s;
            updateConfirmButton();
        });
        this.addRenderableWidget(this.nameField);
        this.setInitialFocus(this.nameField);

        // Colors are unique per server: a color already flown by another settlement is greyed
        // out and unselectable. Derived from the claims the client already mirrors (each claim
        // carries its settlement's color), so no extra packet is needed. If the player's current
        // pick is taken (or this is the first build), snap to the first still-available color.
        java.util.Set<Integer> takenColors = takenColorIndices();
        if (takenColors.contains(selectedColor)) {
            for (int i = 0; i < SettlementColor.count(); i++) {
                if (!takenColors.contains(i)) {
                    selectedColor = i;
                    break;
                }
            }
        }

        int swatchRowWidth = SettlementColor.count() * SWATCH_SIZE + (SettlementColor.count() - 1) * SWATCH_GAP;
        int swatchStartX = controlsX + (controlsWidth - swatchRowWidth) / 2;
        int swatchY = panelY + 84;
        for (int i = 0; i < SettlementColor.count(); i++) {
            final int colorIndex = i;
            final boolean taken = takenColors.contains(i);
            int x = swatchStartX + i * (SWATCH_SIZE + SWATCH_GAP);
            this.addRenderableWidget(new ColorSwatchButton(x, swatchY, SettlementColor.byIndex(i),
                () -> this.selectedColor = colorIndex,
                () -> this.selectedColor == colorIndex,
                taken));
        }

        int buttonY = panelY + IDENTITY_PANEL_HEIGHT - 28;
        int buttonWidth = (controlsWidth - 36) / 2;
        this.confirmButton = PolishButton.polished(
            Component.translatable("bannerbound.settle.next"),
            btn -> {
                page = Page.STYLE;
                this.rebuildWidgets();
            })
            .bounds(controlsX + 12, buttonY, buttonWidth, 20)
            .build();
        this.addRenderableWidget(this.confirmButton);

        this.addRenderableWidget(PolishButton.polished(
            Component.translatable("gui.cancel"),
            btn -> this.onClose())
            .bounds(controlsX + controlsWidth - 12 - buttonWidth, buttonY, buttonWidth, 20)
            .build());

        final int bannerCenterX = panelX + BANNER_AREA_WIDTH / 2;
        final int bannerCenterY = panelY + IDENTITY_PANEL_HEIGHT / 2;
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            ItemStack stack = new ItemStack(bannerItemFor(SettlementColor.byIndex(selectedColor)));
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(bannerCenterX, bannerCenterY, 0.0f);
            pose.scale(4.0f, 4.0f, 1.0f);
            graphics.renderItem(stack, -8, -8);
            pose.popPose();
        });

        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            graphics.drawCenteredString(this.font, this.title,
                controlsX + controlsWidth / 2, panelY + 10, 0xFFFFFFFF);
            graphics.drawString(this.font, Component.translatable("bannerbound.settle.name_label"),
                controlsX + 12, panelY + 24, 0xFFCCCCCC);
            graphics.drawString(this.font, Component.translatable("bannerbound.settle.color_label"),
                controlsX + 12, panelY + 72, 0xFFCCCCCC);
        });

        // Site assessment, drawn just below the panel: a green all-clear, or one warning line per
        // poor-terrain finding so the player isn't surprised by a starved settlement later.
        final int reportCenterX = panelX + PANEL_WIDTH / 2;
        final int reportTop = panelY + IDENTITY_PANEL_HEIGHT + 8;
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            if (siteWarnings.isEmpty()) {
                graphics.drawCenteredString(this.font,
                    Component.translatable("bannerbound.settle.site.good"),
                    reportCenterX, reportTop, 0xFF66CC66);
                return;
            }
            graphics.drawCenteredString(this.font,
                Component.translatable("bannerbound.settle.site.header"),
                reportCenterX, reportTop, 0xFFFFCC44);
            int y = reportTop + 12;
            for (SiteWarning warning : siteWarnings) {
                graphics.drawCenteredString(this.font,
                    Component.translatable(warning.translationKey()),
                    reportCenterX, y, 0xFFE0A030);
                y += 11;
            }
        });

        updateConfirmButton();
    }

    // ─── Page 2: culture style ──────────────────────────────────────────────────────────────

    private void initStylePage() {
        List<ClientCultureStyleState.Entry> styles = ClientCultureStyleState.styles();
        if (selectedStyleId.isEmpty() && !styles.isEmpty()) {
            selectedStyleId = styles.get(0).id();
        }

        final int panelW = STYLE_PANEL_WIDTH;
        final int cardW = (panelW - 2 * STYLE_PAD - (STYLE_COLS - 1) * STYLE_COL_GAP) / STYLE_COLS;
        final int imageH = Math.round(cardW * 9f / 16f);   // 16:9 preview thumbnails
        final int cardH = imageH + STYLE_LABEL_H;
        final int rowStride = cardH + STYLE_ROW_GAP;
        final int rowCount = Math.max(1, (styles.size() + STYLE_COLS - 1) / STYLE_COLS);
        final int contentH = rowCount * rowStride - STYLE_ROW_GAP;   // no trailing gap

        // Viewport prefers to show ~3 rows, but the whole panel is clamped to the window so a small
        // window / high GUI scale shrinks the scroll area instead of overflowing the screen.
        int viewH = Math.min(contentH, 3 * rowStride - STYLE_ROW_GAP);
        int maxPanelH = this.height - 20;
        if (STYLE_HEADER_H + viewH + STYLE_FOOTER_H > maxPanelH) {
            viewH = Math.max(cardH, maxPanelH - STYLE_HEADER_H - STYLE_FOOTER_H);
        }
        final int panelH = STYLE_HEADER_H + viewH + STYLE_FOOTER_H;
        final int panelX = (this.width - panelW) / 2;
        final int panelY = Math.max(10, (this.height - panelH) / 2);

        styleViewLeft = panelX + STYLE_PAD;
        styleViewRight = panelX + panelW - STYLE_PAD;
        styleViewTop = panelY + STYLE_HEADER_H;
        styleViewBottom = styleViewTop + viewH;
        styleScrollMax = Math.max(0, contentH - viewH);
        styleScroll = Math.max(0, Math.min(styleScroll, styleScrollMax));

        // Card layout — x is screen-space (no horizontal scroll); contentY is relative to the top
        // of the scroll content, with the frame's scroll offset applied at draw/hit-test time.
        styleCards.clear();
        for (int i = 0; i < styles.size(); i++) {
            ClientCultureStyleState.Entry entry = styles.get(i);
            int col = i % STYLE_COLS;
            int row = i / STYLE_COLS;
            int x = styleViewLeft + col * (cardW + STYLE_COL_GAP);
            int cy = row * rowStride;
            net.minecraft.resources.ResourceLocation img =
                net.minecraft.resources.ResourceLocation.tryParse(entry.image());
            styleCards.add(new StyleCard(entry.id(), entry.nameKey(), img, x, cy, cardW, cardH, imageH));
        }

        // Panel chrome + header (drawn behind the cards).
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            drawIdentityPanel(graphics, panelX, panelY, panelW, panelH, identityAccents);
            graphics.drawCenteredString(this.font,
                Component.translatable("bannerbound.settle.style_title"),
                panelX + panelW / 2, panelY + 10, 0xFFFFFFFF);
            drawIdentityDivider(graphics, panelX + STYLE_PAD, panelY + STYLE_HEADER_H - 5,
                panelW - 2 * STYLE_PAD, identityAccents);
            if (styles.isEmpty()) {
                graphics.drawCenteredString(this.font,
                    Component.translatable("bannerbound.settle.style_none"),
                    panelX + panelW / 2, styleViewTop + 8, 0xFFCC6666);
            }
        });

        // Scrollable card grid, clipped to the viewport, plus a scrollbar when it overflows.
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            graphics.enableScissor(styleViewLeft, styleViewTop, styleViewRight, styleViewBottom);
            int scroll = (int) Math.round(styleScroll);
            for (StyleCard card : styleCards) {
                int cardY = styleViewTop + card.contentY() - scroll;
                if (cardY + card.h() < styleViewTop || cardY > styleViewBottom) continue;
                renderStyleCard(graphics, card, cardY, mouseX, mouseY);
            }
            graphics.disableScissor();
            if (styleScrollMax > 0) {
                int trackX = styleViewRight + 4;
                graphics.fill(trackX, styleViewTop, trackX + 3, styleViewBottom, 0xFF2A2A2A);
                int trackH = styleViewBottom - styleViewTop;   // == viewport height
                int thumbH = Math.max(16, trackH * trackH / (trackH + styleScrollMax));
                int thumbY = styleViewTop + (int) ((trackH - thumbH) * (styleScroll / styleScrollMax));
                graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFF8A8A8A);
            }
        });

        int buttonY = panelY + panelH - 28;
        int buttonWidth = (panelW - 36) / 2;
        this.addRenderableWidget(PolishButton.polished(
            Component.translatable("bannerbound.settle.back"),
            btn -> {
                page = Page.IDENTITY;
                this.rebuildWidgets();
            })
            .bounds(panelX + 12, buttonY, buttonWidth, 20)
            .build());
        this.addRenderableWidget(PolishButton.polished(
            Component.translatable("bannerbound.settle.confirm"),
            btn -> submit())
            .bounds(panelX + panelW - 12 - buttonWidth, buttonY, buttonWidth, 20)
            .build());
    }

    /** Draws one culture card: 16:9 preview image with a name bar beneath it. The selection accent
     *  is the banner color chosen on page 1, so the two founding steps read as one identity. */
    private void renderStyleCard(GuiGraphics graphics, StyleCard card, int cardY,
                                 int mouseX, int mouseY) {
        int x = card.x();
        int w = card.w();
        int imageH = card.imageH();
        int h = card.h();
        boolean selected = card.id().equals(selectedStyleId);
        boolean hovered = mouseX >= x && mouseX < x + w
            && mouseY >= Math.max(cardY, styleViewTop) && mouseY < Math.min(cardY + h, styleViewBottom);
        int accent = 0xFF000000 | (SettlementColor.byIndex(selectedColor).rgb() & 0x00FFFFFF);

        // Preview image (or a dark "?" placeholder when the texture is absent).
        int[] size = card.image() == null ? new int[]{0, 16, 9} : imageSize(card.image());
        graphics.fill(x, cardY, x + w, cardY + imageH, 0xFF181818);
        if (size[0] == 1) {
            graphics.blit(card.image(), x, cardY, w, imageH, 0f, 0f, size[1], size[2], size[1], size[2]);
        } else {
            graphics.drawCenteredString(this.font, "?", x + w / 2, cardY + imageH / 2 - 4, 0xFF666666);
        }

        // Name bar beneath the image (the sketch's grey label strip).
        int barTop = cardY + imageH;
        int barColor = selected ? blendArgb(0xFF202020, accent, 0.45f)
            : (hovered ? 0xFF3A3A3A : 0xFF262626);
        graphics.fill(x, barTop, x + w, barTop + STYLE_LABEL_H, barColor);
        graphics.drawCenteredString(this.font, Component.literal(card.name()),
            x + w / 2, barTop + (STYLE_LABEL_H - this.font.lineHeight) / 2 + 1, 0xFFFFFFFF);

        // Border: banner accent when selected, brightened on hover, subtle otherwise.
        int border = selected ? accent : (hovered ? 0xFFCCCCCC : 0xFF000000);
        graphics.renderOutline(x, cardY, w, h, border);
        if (selected) {
            graphics.renderOutline(x - 1, cardY - 1, w + 2, h + 2, accent);
        }
    }

    /** [exists?1:0, width, height] for a preview texture, read once and cached. Lets the blit use
     *  the true texture dimensions so any correctly-sized replacement image still fills the card. */
    private static int[] imageSize(net.minecraft.resources.ResourceLocation tex) {
        return IMG_SIZE.computeIfAbsent(tex, t -> {
            try {
                net.minecraft.server.packs.resources.Resource res = net.minecraft.client.Minecraft
                    .getInstance().getResourceManager().getResource(t).orElse(null);
                if (res == null) return new int[]{0, 16, 9};
                try (com.mojang.blaze3d.platform.NativeImage img =
                         com.mojang.blaze3d.platform.NativeImage.read(res.open())) {
                    return new int[]{1, Math.max(1, img.getWidth()), Math.max(1, img.getHeight())};
                }
            } catch (Exception ex) {
                return new int[]{0, 16, 9};
            }
        });
    }

    private boolean withinStyleViewport(double mx, double my) {
        return mx >= styleViewLeft && mx < styleViewRight && my >= styleViewTop && my < styleViewBottom;
    }

    /** A laid-out culture card. {@code x} is screen-space; {@code contentY} is scroll-content space. */
    private record StyleCard(String id, String name, net.minecraft.resources.ResourceLocation image,
                             int x, int contentY, int w, int h, int imageH) {}

    /** Color indices already claimed by an existing settlement, derived from the client's mirror
     *  of the claim map (each claimed chunk carries its settlement's color). These are greyed out
     *  and unselectable in the picker — colors are unique per server. */
    private static java.util.Set<Integer> takenColorIndices() {
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        for (com.bannerbound.core.network.ClaimEntry e : ClientClaimState.all().values()) {
            taken.add(e.colorIndex());
        }
        return taken;
    }

    private void updateConfirmButton() {
        if (this.confirmButton != null) {
            this.confirmButton.active = SettlementManager.isNameValid(nameText.trim());
        }
    }

    private void submit() {
        String name = nameText.trim();
        if (!SettlementManager.isNameValid(name)) {
            page = Page.IDENTITY;
            this.rebuildWidgets();
            return;
        }
        PacketDistributor.sendToServer(new SettleRequestPayload(name, this.selectedColor, this.selectedStyleId));
        this.onClose();
    }

    private static Item bannerItemFor(SettlementColor color) {
        return switch (color) {
            case WHITE -> Items.WHITE_BANNER;
            case RED -> Items.RED_BANNER;
            case GOLD -> Items.ORANGE_BANNER;
            case YELLOW -> Items.YELLOW_BANNER;
            case GREEN -> Items.LIME_BANNER;
            case AQUA -> Items.LIGHT_BLUE_BANNER;
            case BLUE -> Items.BLUE_BANNER;
            case LIGHT_PURPLE -> Items.MAGENTA_BANNER;
        };
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Culture cards are custom-drawn (not widgets), so hit-test them here. Selection is a pure
        // repaint — the render pass reads selectedStyleId live, so no widget rebuild is needed
        // (which also keeps the current scroll position).
        if (page == Page.STYLE && button == 0 && withinStyleViewport(mouseX, mouseY)) {
            int scroll = (int) Math.round(styleScroll);
            for (StyleCard card : styleCards) {
                int cardY = styleViewTop + card.contentY() - scroll;
                if (mouseX >= card.x() && mouseX < card.x() + card.w()
                    && mouseY >= cardY && mouseY < cardY + card.h()) {
                    selectedStyleId = card.id();
                    return true;
                }
            }
            return true;   // swallow clicks landing in the viewport gutter
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (page == Page.STYLE && styleScrollMax > 0 && withinStyleViewport(mouseX, mouseY)) {
            styleScroll = Math.max(0, Math.min(styleScrollMax, styleScroll - scrollY * 24));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class ColorSwatchButton extends Button {
        private final SettlementColor color;
        private final java.util.function.BooleanSupplier isSelected;
        private final boolean taken;

        ColorSwatchButton(int x, int y, SettlementColor color, Runnable onClick,
                          java.util.function.BooleanSupplier isSelected, boolean taken) {
            super(x, y, SWATCH_SIZE, SWATCH_SIZE, color.displayName(),
                btn -> onClick.run(), DEFAULT_NARRATION);
            this.color = color;
            this.isSelected = isSelected;
            this.taken = taken;
            // A taken color can't be picked — disabling the button also blocks its click handler.
            this.active = !taken;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();
            int h = this.getHeight();

            if (taken) {
                // Dim the swatch and draw an X so it reads clearly as "already claimed".
                int dim = 0xFF000000 | (((color.rgb() & 0xFEFEFE) >> 1));
                graphics.fill(x, y, x + w, y + h, dim);
                graphics.renderOutline(x, y, w, h, 0xFF222222);
                graphics.fill(x + 2, y + h / 2, x + w - 2, y + h / 2 + 1, 0xFF000000);
                return;
            }

            int fill = 0xFF000000 | (color.rgb() & 0x00FFFFFF);
            graphics.fill(x, y, x + w, y + h, fill);

            int borderColor = isSelected.getAsBoolean() ? 0xFFFFFFFF
                : (this.isHovered() ? 0xFFCCCCCC : 0xFF333333);
            graphics.renderOutline(x, y, w, h, borderColor);
            if (isSelected.getAsBoolean()) {
                graphics.renderOutline(x - 1, y - 1, w + 2, h + 2, 0xFFFFFFFF);
            }
        }
    }
}
