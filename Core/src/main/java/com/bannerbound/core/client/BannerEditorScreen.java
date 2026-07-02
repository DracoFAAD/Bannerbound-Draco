package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.bannerbound.core.api.settlement.FactionBanner;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementColor;
import com.bannerbound.core.network.OpenBannerEditorPayload;
import com.bannerbound.core.network.RequestBannerCopyPayload;
import com.bannerbound.core.network.SaveBannerDesignPayload;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Heraldry banner editor (Heraldry culture research → town hall "Banner" button).
 * Up to six pattern layers over the faction base color; each layer occupies one earned
 * Heraldry point while it exists (removing a layer frees the point).
 *
 * <p>Layout, left to right: the layer list (▲▼ reorder, ✕ remove, click to select) with the
 * Add button and the Heraldry point pips; the live preview — a real waving banner (vanilla
 * world-banner sway formula) in a framed inset, with the design's LIVE identity colors below
 * it; the paged pattern grid (mini flags drawn in the selected layer's color) and the 16-dye
 * palette. Flag rendering reuses the vanilla loom's technique —
 * {@code BannerRenderer.renderPatterns} on the baked BANNER "flag" model part.
 *
 * <p>The identity swatches are the design's consequence made visible: the banner's dominant
 * dye BECOMES the settlement's primary color on save, the runner-up its accent
 * ({@link FactionBanner#identityDyes} — same math the server runs). Don't fight the design;
 * become it.
 *
 * <p>Server state arrives via {@link OpenBannerEditorPayload}; edits happen on a local
 * working copy and only Save sends a {@link SaveBannerDesignPayload}. The server re-validates
 * everything and answers with a fresh snapshot ({@link #applyServerState}).
 */
public class BannerEditorScreen extends PolishedScreen {

    private static final int MAX_LAYERS = 6;
    private static final int PANEL_W = 330;
    private static final int PANEL_H = 244;
    private static final int GRID_COLS = 6;
    private static final int GRID_ROWS = 4;
    private static final int GRID_CELL_W = 18;
    private static final int GRID_CELL_H = 30;

    private static final int COL_BG = 0xFF101010;
    private static final int COL_HEADER = 0xFF181818;
    private static final int COL_INSET = 0xFF0A0A0A;
    private static final int COL_LINE = 0xFF2A2A2A;
    private static final int COL_ROW = 0xFF1A1A1A;
    private static final int COL_ROW_HOVER = 0xFF242424;

    /** One working layer (mutable while editing; becomes a Settlement.BannerLayer on save). */
    private static final class WorkingLayer {
        Holder<BannerPattern> pattern;
        DyeColor color;
        WorkingLayer(Holder<BannerPattern> pattern, DyeColor color) {
            this.pattern = pattern;
            this.color = color;
        }
    }

    private final SettlementColor baseColor;
    private final DyeColor baseDye;
    private int pointsEarned;
    private final List<WorkingLayer> working = new ArrayList<>();
    private int selected = -1;

    private List<Holder.Reference<BannerPattern>> allPatterns = List.of();
    private int patternPage;
    private ModelPart flagPart;

    // Geometry computed in init(), shared by drawPanel + mouse hit tests.
    private int panelX, panelY;
    private int listX, listY, listW, rowH;
    private int frameX, frameY, frameW, frameH;
    private int swatchY;
    private int gridX, gridY;
    private int paletteX, paletteY;

    private Button addLayerButton;

    public BannerEditorScreen(OpenBannerEditorPayload payload) {
        super(Component.translatable("bannerbound.banner.editor.title"));
        this.baseColor = SettlementColor.byIndex(payload.baseColorOrdinal());
        this.baseDye = FactionBanner.dyeFor(baseColor);
        this.pointsEarned = payload.pointsEarned();
        loadLayers(payload);
    }

    /** Server refresh after a save (or a re-request): authoritative design + points. */
    public void applyServerState(OpenBannerEditorPayload payload) {
        this.pointsEarned = payload.pointsEarned();
        loadLayers(payload);
        refreshButtons();
    }

    private void loadLayers(OpenBannerEditorPayload payload) {
        working.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var reg = mc.level.registryAccess().registryOrThrow(Registries.BANNER_PATTERN);
        for (int i = 0; i < payload.patterns().size() && i < payload.colors().size(); i++) {
            ResourceLocation rl = ResourceLocation.tryParse(payload.patterns().get(i));
            if (rl == null) continue;
            var holder = reg.getHolder(net.minecraft.resources.ResourceKey.create(
                Registries.BANNER_PATTERN, rl));
            if (holder.isEmpty()) continue;
            working.add(new WorkingLayer(holder.get(), DyeColor.byId(payload.colors().get(i))));
        }
        selected = working.isEmpty() ? -1 : working.size() - 1;
    }

    private int pointsAvailable() {
        return Math.max(0, pointsEarned - working.size());
    }

    /** The identity colors this WORKING design would earn on save — same math as the server.
     *  As many entries as the design has colors holding ≥5% of the cloth. */
    private List<DyeColor> liveIdentity() {
        if (working.isEmpty()) return List.of(baseDye);
        List<Settlement.BannerLayer> layers = new ArrayList<>();
        for (WorkingLayer layer : working) {
            layers.add(new Settlement.BannerLayer(
                layer.pattern.unwrapKey().map(k -> k.location().toString()).orElse(""),
                layer.color.getId()));
        }
        return FactionBanner.identityDyes(baseDye, layers);
    }

    private static int argb(DyeColor dye) {
        return 0xFF000000 | dye.getTextureDiffuseColor();
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            allPatterns = mc.level.registryAccess().registryOrThrow(Registries.BANNER_PATTERN)
                .holders()
                .sorted(Comparator.comparing(h -> h.key().location().toString()))
                .toList();
        }
        flagPart = mc.getEntityModels().bakeLayer(ModelLayers.BANNER).getChild("flag");

        panelX = (this.width - PANEL_W) / 2;
        panelY = (this.height - PANEL_H) / 2;

        listX = panelX + 12;
        listY = panelY + 42;
        listW = 112;
        rowH = 15;

        frameX = panelX + 132;
        frameY = panelY + 36;
        frameW = 66;
        frameH = 94;
        swatchY = frameY + frameH + 18;

        gridX = panelX + 208;
        gridY = panelY + 40;

        paletteX = gridX;
        paletteY = panelY + 188;

        this.addRenderableOnly(this::drawPanel);

        addLayerButton = PolishButton.polished(
            Component.translatable("bannerbound.banner.editor.add_layer"),
            btn -> addLayer())
            .bounds(listX, listY + MAX_LAYERS * rowH + 6, listW, 18)
            .build();
        this.addRenderableWidget(addLayerButton);

        int btnY = panelY + PANEL_H - 26;
        this.addRenderableWidget(PolishButton.polished(
            Component.translatable("bannerbound.banner.editor.save"),
            btn -> save())
            .bounds(panelX + PANEL_W - 80, btnY, 68, 18)
            .build());
        this.addRenderableWidget(PolishButton.polished(
            Component.translatable("gui.cancel"),
            btn -> this.onClose())
            .bounds(panelX + 12, btnY, 68, 18)
            .build());
        // Hands out a cosmetic copy of the saved faction banner — place as many as you like
        // for decoration (they only register as THE banner when the main one is down). Paid
        // for from settlement storage: one dye per color in the design.
        Button takeCopyButton = PolishButton.polished(
            Component.translatable("bannerbound.banner.editor.take_copy"),
            btn -> PacketDistributor.sendToServer(RequestBannerCopyPayload.INSTANCE))
            .bounds(panelX + PANEL_W / 2 - 50, btnY, 100, 18)
            .build();
        takeCopyButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
            Component.translatable("bannerbound.banner.editor.take_copy.tip")));
        this.addRenderableWidget(takeCopyButton);

        this.addRenderableWidget(PolishButton.polished(Component.literal("◀"),
            btn -> { if (patternPage > 0) patternPage--; })
            .bounds(gridX, paletteY - 16, 18, 12)
            .build());
        this.addRenderableWidget(PolishButton.polished(Component.literal("▶"),
            btn -> { if ((patternPage + 1) * GRID_COLS * GRID_ROWS < allPatterns.size()) patternPage++; })
            .bounds(gridX + GRID_COLS * GRID_CELL_W - 18, paletteY - 16, 18, 12)
            .build());

        refreshButtons();
    }

    private void refreshButtons() {
        if (addLayerButton != null) {
            addLayerButton.active = working.size() < MAX_LAYERS && pointsAvailable() > 0
                && !allPatterns.isEmpty();
        }
    }

    private void addLayer() {
        if (working.size() >= MAX_LAYERS || pointsAvailable() <= 0 || allPatterns.isEmpty()) return;
        // Contrast default so a fresh layer is visible immediately on any base color.
        DyeColor start = baseDye == DyeColor.WHITE ? DyeColor.BLACK : DyeColor.WHITE;
        working.add(new WorkingLayer(allPatterns.get(0), start));
        selected = working.size() - 1;
        refreshButtons();
    }

    private void removeLayer(int index) {
        if (index < 0 || index >= working.size()) return;
        working.remove(index);
        if (selected >= working.size()) selected = working.size() - 1;
        refreshButtons();
    }

    /** Swaps a layer with its neighbor; the selection follows the moved layer. */
    private void moveLayer(int index, int dir) {
        int target = index + dir;
        if (index < 0 || index >= working.size() || target < 0 || target >= working.size()) return;
        java.util.Collections.swap(working, index, target);
        if (selected == index) selected = target;
        else if (selected == target) selected = index;
    }

    private void save() {
        List<String> patterns = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (WorkingLayer layer : working) {
            patterns.add(layer.pattern.unwrapKey().map(k -> k.location().toString()).orElse(""));
            colors.add(layer.color.getId());
        }
        PacketDistributor.sendToServer(new SaveBannerDesignPayload(patterns, colors));
    }

    private BannerPatternLayers workingPatterns() {
        if (working.isEmpty()) return BannerPatternLayers.EMPTY;
        List<BannerPatternLayers.Layer> layers = new ArrayList<>();
        for (WorkingLayer layer : working) {
            layers.add(new BannerPatternLayers.Layer(layer.pattern, layer.color));
        }
        return new BannerPatternLayers(List.copyOf(layers));
    }

    private static Component layerName(WorkingLayer layer) {
        return Component.translatable(
            layer.pattern.value().translationKey() + "." + layer.color.getName());
    }

    private void drawPanel(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        List<DyeColor> identity = liveIdentity();
        List<Integer> accents = new ArrayList<>(identity.size());
        for (DyeColor dye : identity) accents.add(argb(dye));
        int accentPrimary = accents.get(0);

        // ── Chrome: panel, header band, identity-gradient border + divider ──
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, COL_BG);
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + 22, COL_HEADER);
        drawIdentityBorder(graphics, panelX, panelY, PANEL_W, PANEL_H, accents);
        drawIdentityGradient(graphics, panelX + 1, panelY + 22, PANEL_W - 2, 1, accents);
        graphics.drawCenteredString(this.font, this.getTitle(),
            panelX + PANEL_W / 2, panelY + 7, 0xFFFFFFFF);

        // ── Layer list ──
        graphics.drawString(this.font,
            Component.translatable("bannerbound.banner.editor.layers"), listX, listY - 10, 0xFFAAAAAA);
        for (int i = 0; i < MAX_LAYERS; i++) {
            int rowY = listY + i * rowH;
            boolean rowHovered = mouseX >= listX && mouseX < listX + listW
                && mouseY >= rowY && mouseY < rowY + rowH - 2;
            if (i < working.size()) {
                WorkingLayer layer = working.get(i);
                graphics.fill(listX, rowY, listX + listW, rowY + rowH - 2,
                    rowHovered ? COL_ROW_HOVER : COL_ROW);
                if (i == selected) {
                    graphics.renderOutline(listX, rowY, listW, rowH - 2,
                        blendArgb(COL_LINE, accentPrimary, 0.7f));
                }
                graphics.fill(listX + 3, rowY + 3, listX + 11, rowY + 11, argb(layer.color));
                graphics.renderOutline(listX + 3, rowY + 3, 8, 8, 0xFF000000);
                String clipped = this.font.plainSubstrByWidth(
                    layerName(layer).getString(), listW - 50);
                graphics.drawString(this.font, clipped, listX + 15, rowY + 3, 0xFFE0E0E0);
                graphics.drawString(this.font, "▲", listX + listW - 32, rowY + 3,
                    i > 0 ? 0xFFAAAAAA : 0xFF404040);
                graphics.drawString(this.font, "▼", listX + listW - 21, rowY + 3,
                    i < working.size() - 1 ? 0xFFAAAAAA : 0xFF404040);
                graphics.drawString(this.font, "✕", listX + listW - 10, rowY + 3, 0xFFCC6666);
            } else {
                graphics.fill(listX, rowY, listX + listW, rowY + rowH - 2, 0xFF141414);
                graphics.drawString(this.font, "—", listX + 4, rowY + 3, 0xFF383838);
            }
        }

        // ── Heraldry point pips: bright = free, dim = held by a layer ──
        int pipsY = listY + MAX_LAYERS * rowH + 30;
        graphics.drawString(this.font,
            Component.translatable("bannerbound.banner.editor.points_label"),
            listX, pipsY, 0xFFAAAAAA);
        int pipX = listX + this.font.width(
            Component.translatable("bannerbound.banner.editor.points_label")) + 6;
        int shownPips = Math.min(pointsEarned, 10);
        for (int i = 0; i < shownPips; i++) {
            boolean free = i >= working.size();
            int px = pipX + i * 8;
            graphics.fill(px, pipsY + 1, px + 6, pipsY + 7, free ? 0xFFFFD27D : 0xFF4A3D22);
            graphics.renderOutline(px, pipsY + 1, 6, 6, free ? 0xFFFFE7B0 : 0xFF2A2A2A);
        }
        if (pointsAvailable() == 0 && working.size() < MAX_LAYERS) {
            drawWrapped(graphics, this.font,
                Component.translatable("bannerbound.banner.editor.points_hint"),
                listX, pipsY + 12, listW, 0xFF707070);
        }

        // ── Preview: framed, waving, true to the world banner ──
        graphics.fill(frameX, frameY, frameX + frameW, frameY + frameH, COL_INSET);
        graphics.renderOutline(frameX, frameY, frameW, frameH, COL_LINE);
        renderFlag(graphics, frameX + (frameW - 35) / 2, frameY + 12, 28f,
            baseDye, workingPatterns(), true, partialTick);

        // ── Live identity: what this design makes you — one swatch per identity color,
        // however many the design earns (1..n). First = primary, second = accent, the rest
        // are trim. The row recenters as colors come and go. ──
        int colCenter = frameX + frameW / 2;
        graphics.drawCenteredString(this.font,
            Component.translatable("bannerbound.banner.editor.identity"),
            colCenter, swatchY - 12, 0xFFAAAAAA);
        final int swatchSize = 10, swatchGap = 4;
        int rowWidth = accents.size() * swatchSize + (accents.size() - 1) * swatchGap;
        int swatchX = colCenter - rowWidth / 2;
        Component identityTip = null;
        for (int i = 0; i < accents.size(); i++) {
            int sx = swatchX + i * (swatchSize + swatchGap);
            graphics.fill(sx, swatchY, sx + swatchSize, swatchY + swatchSize, accents.get(i));
            graphics.renderOutline(sx, swatchY, swatchSize, swatchSize, 0xFF000000);
            if (mouseY >= swatchY && mouseY < swatchY + swatchSize
                    && mouseX >= sx && mouseX < sx + swatchSize) {
                String roleKey = i == 0 ? "bannerbound.banner.editor.identity_primary"
                    : i == 1 ? "bannerbound.banner.editor.identity_secondary"
                    : "bannerbound.banner.editor.identity_tertiary";
                identityTip = Component.translatable(roleKey,
                    Component.translatable("color.minecraft." + identity.get(i).getName()));
            }
        }
        drawIdentityGradient(graphics, frameX + 8, swatchY + 14, frameW - 16, 2, accents);
        if (identityTip != null) {
            graphics.renderTooltip(this.font, identityTip, mouseX, mouseY);
        }

        // ── Pattern grid (inset; mini previews in the selected layer's color) ──
        graphics.fill(gridX - 4, gridY - 4, gridX + GRID_COLS * GRID_CELL_W + 4,
            gridY + GRID_ROWS * GRID_CELL_H + 4, COL_INSET);
        graphics.renderOutline(gridX - 4, gridY - 4, GRID_COLS * GRID_CELL_W + 8,
            GRID_ROWS * GRID_CELL_H + 8, COL_LINE);
        DyeColor previewColor = selected >= 0 ? working.get(selected).color
            : (baseDye == DyeColor.WHITE ? DyeColor.BLACK : DyeColor.WHITE);
        int perPage = GRID_COLS * GRID_ROWS;
        int start = patternPage * perPage;
        Holder<BannerPattern> selectedPattern = selected >= 0 ? working.get(selected).pattern : null;
        Component hoveredTip = null;
        for (int i = 0; i < perPage && start + i < allPatterns.size(); i++) {
            Holder.Reference<BannerPattern> pattern = allPatterns.get(start + i);
            int cx = gridX + (i % GRID_COLS) * GRID_CELL_W;
            int cy = gridY + (i / GRID_COLS) * GRID_CELL_H;
            boolean isSelected = selectedPattern != null
                && pattern.key().equals(selectedPattern.unwrapKey().orElse(null));
            boolean hovered = mouseX >= cx && mouseX < cx + GRID_CELL_W
                && mouseY >= cy && mouseY < cy + GRID_CELL_H;
            if (hovered) graphics.fill(cx, cy, cx + GRID_CELL_W, cy + GRID_CELL_H, 0xFF1E1E1E);
            renderFlag(graphics, cx + 4, cy + 4, 8f, baseDye, new BannerPatternLayers(List.of(
                new BannerPatternLayers.Layer(pattern, previewColor))), false, partialTick);
            if (isSelected) {
                graphics.renderOutline(cx, cy, GRID_CELL_W, GRID_CELL_H,
                    blendArgb(COL_LINE, accentPrimary, 0.7f));
            } else if (hovered) {
                graphics.renderOutline(cx, cy, GRID_CELL_W, GRID_CELL_H, 0xFF707070);
            }
            if (hovered) {
                hoveredTip = Component.translatable(
                    pattern.value().translationKey() + "." + previewColor.getName());
            }
        }

        // Page indicator centered between the ◀ ▶ buttons.
        int pageCount = Math.max(1, (allPatterns.size() + perPage - 1) / perPage);
        graphics.drawCenteredString(this.font, (patternPage + 1) + "/" + pageCount,
            gridX + GRID_COLS * GRID_CELL_W / 2, paletteY - 14, 0xFF808080);

        // ── Dye palette ──
        for (DyeColor dye : DyeColor.values()) {
            int sx = paletteX + (dye.getId() % 8) * 14;
            int sy = paletteY + (dye.getId() / 8) * 14;
            boolean isCurrent = selected >= 0 && working.get(selected).color == dye;
            boolean hovered = mouseX >= sx && mouseX < sx + 12 && mouseY >= sy && mouseY < sy + 12;
            graphics.fill(sx, sy, sx + 12, sy + 12, argb(dye));
            graphics.renderOutline(sx, sy, 12, 12,
                isCurrent ? 0xFFFFFFFF : (hovered ? 0xFF909090 : 0xFF000000));
            if (hovered && hoveredTip == null) {
                hoveredTip = Component.translatable("color.minecraft." + dye.getName());
            }
        }
        // Tooltips drawn LAST so they layer over everything in the panel.
        if (hoveredTip != null) {
            graphics.renderTooltip(this.font, hoveredTip, mouseX, mouseY);
        }
    }

    /**
     * Vanilla loom technique: the baked BANNER "flag" model part rendered straight-on through
     * {@code BannerRenderer.renderPatterns}. {@code x,y} is the cloth's top-left on screen;
     * one model unit = scale/16 px, the cloth is 20×40 units (so scale 28 → 35×70 px).
     * Derived from the full transform chain (scale → ±0.5 recenter → unflip → the flag part's
     * own -32/16 offset → cube x:-10..10, y:0..40): the cloth's top-left lands at
     * translate + (-0.125·s, -2.5·s). {@code wave} applies the world banner's sway formula —
     * the editor preview breathes like the real flag in the plaza.
     */
    private void renderFlag(GuiGraphics graphics, int x, int y, float scale,
                            DyeColor base, BannerPatternLayers patterns,
                            boolean wave, float partialTick) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x + scale * 0.125f, y + scale * 2.5f, 100);
        pose.scale(scale, -scale, 1f);
        pose.translate(0.5f, 0.5f, 0.5f);
        pose.scale(1f, -1f, -1f);
        Lighting.setupForFlatItems();
        Minecraft mc = Minecraft.getInstance();
        if (wave && mc.level != null) {
            float t = ((float) (mc.level.getGameTime() % 100L) + partialTick) / 100f;
            flagPart.xRot = (-0.0125f + 0.01f * Mth.cos((float) (Math.PI * 2) * t)) * (float) Math.PI;
        } else {
            flagPart.xRot = 0f;
        }
        flagPart.y = -32f;
        BannerRenderer.renderPatterns(pose, graphics.bufferSource(), 15728880,
            OverlayTexture.NO_OVERLAY, flagPart, ModelBakery.BANNER_BASE, true, base, patterns);
        graphics.flush();
        Lighting.setupFor3DItems();
        pose.popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Layer rows: ▲ ▼ reorder, ✕ remove, anywhere else selects.
            for (int i = 0; i < working.size(); i++) {
                int rowY = listY + i * rowH;
                if (mouseY >= rowY && mouseY < rowY + rowH - 2
                        && mouseX >= listX && mouseX < listX + listW) {
                    if (mouseX >= listX + listW - 12) {
                        removeLayer(i);
                    } else if (mouseX >= listX + listW - 23) {
                        moveLayer(i, 1);
                    } else if (mouseX >= listX + listW - 34) {
                        moveLayer(i, -1);
                    } else {
                        selected = i;
                    }
                    return true;
                }
            }
            // Pattern grid → set the selected layer's pattern.
            if (selected >= 0) {
                int perPage = GRID_COLS * GRID_ROWS;
                int start = patternPage * perPage;
                for (int i = 0; i < perPage && start + i < allPatterns.size(); i++) {
                    int cx = gridX + (i % GRID_COLS) * GRID_CELL_W;
                    int cy = gridY + (i / GRID_COLS) * GRID_CELL_H;
                    if (mouseX >= cx && mouseX < cx + GRID_CELL_W
                            && mouseY >= cy && mouseY < cy + GRID_CELL_H) {
                        working.get(selected).pattern = allPatterns.get(start + i);
                        return true;
                    }
                }
                // Dye palette → set the selected layer's color.
                for (DyeColor dye : DyeColor.values()) {
                    int sx = paletteX + (dye.getId() % 8) * 14;
                    int sy = paletteY + (dye.getId() / 8) * 14;
                    if (mouseX >= sx && mouseX < sx + 12 && mouseY >= sy && mouseY < sy + 12) {
                        working.get(selected).color = dye;
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
