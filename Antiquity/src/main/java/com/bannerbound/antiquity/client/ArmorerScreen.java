package com.bannerbound.antiquity.client;

import java.util.EnumMap;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.core.client.PolishButton;
import com.bannerbound.core.client.PolishedScreen;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.math.Axis;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The Armorer's Workbench design screen — v1 of the player-designed-armor editor (ARMOR_PLAN.md).
 * Shows a live 3D render of the designed helmet that the player orbits by dragging, with each of the
 * four <b>zones</b> (dome / front / cheeks / neck) independently set to None, Leather, or Metal — the
 * {@code zones:{zone→material}} design schema from the plan. Crafting an item comes next; this
 * validates the render + per-zone design pipeline end-to-end.
 *
 * <p>The 3D draw follows vanilla's in-inventory model idiom: translate to the preview centre, scale
 * with a negated Z (GUI depth runs into the screen), then apply the orbit pitch/yaw. {@link #BASE_PITCH},
 * {@link #BASE_YAW}, {@link #MODEL_Y} and {@link #MODEL_SCALE} are the tuning knobs.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ArmorerScreen extends PolishedScreen {
    /** Per-zone material — one geometry, swapped skin (the "geometry once, skin per material" rule). */
    private enum Skin {
        NONE(null, "Off", ChatFormatting.GRAY),
        LEATHER("leather_helmet", "Leather", ChatFormatting.GOLD),
        METAL("metal_helmet", "Metal", ChatFormatting.AQUA);

        private final ResourceLocation texture;
        private final String label;
        private final ChatFormatting color;

        Skin(String file, String label, ChatFormatting color) {
            this.texture = file == null ? null
                : ResourceLocation.fromNamespaceAndPath(
                    BannerboundAntiquity.MODID, "textures/armor/" + file + ".png");
            this.label = label;
            this.color = color;
        }

        Skin next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** The four design zones, paired with the model bone they drive. */
    private enum Zone {
        DOME(HelmetModel.DOME, "Dome"),
        FRONT(HelmetModel.FRONT, "Front"),
        CHEEKS(HelmetModel.CHEEKS, "Cheeks"),
        NECK(HelmetModel.NECK, "Neck");

        private final String bone;
        private final String label;

        Zone(String bone, String label) {
            this.bone = bone;
            this.label = label;
        }
    }

    // ── Tuning knobs (nudge in-game if the render is off) ───────────────────────────────────────
    /** Base tilt so we look slightly down onto the dome; user orbit adds to this. */
    private static final float BASE_PITCH = 8.0F;
    /** Base spin so the cap reads three-quarter rather than dead-on (drag to find the face). */
    private static final float BASE_YAW = 30.0F;
    /** Small model-space vertical nudge to centre the cap on the anchor. */
    private static final float MODEL_Y = 0.0F;
    /** On-screen size. */
    private static final float MODEL_SCALE = 70.0F;

    private static final int PANEL_W = 280;
    private static final int PANEL_H = 250;
    private static final int COL_PANEL_BG = 0xF0140D08;
    private static final int COL_PANEL_BORDER = 0xFF6B4E2E;
    private static final int COL_TITLE = 0xFFE8D3A8;

    private final BlockPos pos;
    private final EnumMap<Zone, Skin> design = new EnumMap<>(Zone.class);
    private HelmetModel model;

    // Accumulated orbit from mouse drag.
    private float yaw = 0.0F;
    private float pitch = 0.0F;

    public ArmorerScreen(BlockPos pos) {
        super(Component.translatable("bannerboundantiquity.armorer.title"));
        this.pos = pos;
        for (Zone z : Zone.values()) design.put(z, Skin.LEATHER);
    }

    @Override
    protected void init() {
        this.model = new HelmetModel(
            Minecraft.getInstance().getEntityModels().bakeLayer(HelmetModel.LAYER));

        int cx = this.width / 2;
        int top = this.height / 2 - PANEL_H / 2;

        // One toggle button per zone, in a 2×2 grid along the bottom of the panel.
        int bw = 124;
        int bh = 20;
        int gridLeft = cx - bw - 4;
        int gridTop = top + PANEL_H - 78;
        Zone[] zones = Zone.values();
        for (int i = 0; i < zones.length; i++) {
            Zone z = zones[i];
            int bx = gridLeft + (i % 2) * (bw + 8);
            int by = gridTop + (i / 2) * (bh + 4);
            addRenderableWidget(PolishButton.polished(zoneLabel(z), b -> {
                design.put(z, design.get(z).next());
                b.setMessage(zoneLabel(z));
            }).bounds(bx, by, bw, bh).accent(primaryAccent()).build());
        }

        addRenderableWidget(PolishButton.polished(Component.translatable("gui.done"), b -> onClose())
            .bounds(cx - 50, top + PANEL_H - 26, 100, 20).accent(primaryAccent()).build());
    }

    private Component zoneLabel(Zone z) {
        Skin s = design.get(z);
        return Component.literal(z.label + ": ")
            .append(Component.literal(s.label).withStyle(s.color));
    }

    @Override
    protected void renderPolishedBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int cx = this.width / 2;
        int cy = this.height / 2;
        int left = cx - PANEL_W / 2;
        int top = cy - PANEL_H / 2;

        g.fill(left, top, left + PANEL_W, top + PANEL_H, COL_PANEL_BG);
        g.renderOutline(left, top, PANEL_W, PANEL_H, COL_PANEL_BORDER);
        g.drawCenteredString(this.font, this.title, cx, top + 8, COL_TITLE);

        // The live 3D helmet, orbiting under the title (sits above the button grid).
        renderHelmet(g, cx, top + 95);
    }

    /** Draws the enabled zones of the helmet in the GUI (vanilla in-inventory model idiom). */
    private void renderHelmet(GuiGraphics g, int cx, int cy) {
        if (model == null) return;
        g.pose().pushPose();
        g.pose().translate(cx, cy, 200.0F);
        g.pose().mulPose(Axis.XP.rotationDegrees(180.0F));     // outer upright flip (keeps it on-screen)
        g.pose().scale(MODEL_SCALE, MODEL_SCALE, -MODEL_SCALE); // negate Z: GUI depth runs into the screen
        g.pose().mulPose(Axis.ZP.rotationDegrees(180.0F));     // vanilla in-inventory base orientation
        g.pose().mulPose(Axis.XP.rotationDegrees(BASE_PITCH + pitch));
        g.pose().mulPose(Axis.YP.rotationDegrees(BASE_YAW + yaw));
        g.pose().translate(0.0F, MODEL_Y, 0.0F); // small model-space centring nudge

        Lighting.setupForEntityInInventory();
        MultiBufferSource.BufferSource buffers = g.bufferSource();
        for (Zone z : Zone.values()) {
            Skin s = design.get(z);
            if (s.texture == null) continue; // Off → not drawn
            model.renderZone(z.bone, g.pose(),
                buffers.getBuffer(RenderType.entityCutoutNoCull(s.texture)),
                0xF000F0, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        }
        g.flush();
        Lighting.setupFor3DItems();
        g.pose().popPose();
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0) {
            yaw += (float) dragX;
            pitch = Mth.clamp(pitch + (float) dragY, -80.0F, 80.0F);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
