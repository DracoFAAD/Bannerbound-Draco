package com.bannerbound.core.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.network.WallScreenPayloads;
import com.mojang.math.Axis;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The wall REFINEMENT view (WALLS_PLAN.md Phase 5.5) — the editor's "step 2". A rotating
 * axonometric god-view of the whole planned wall: every piece drawn as a 3D box at its true
 * elevation over a terrain baseline, so height decisions are finally VISIBLE. The auto
 * top-alignment chain is just the first draft; here the player selects pieces and raises /
 * lowers their tops (per-slot overrides, gate-anchor stability rules), places gates, and
 * commits. Blender edit-mode for your wall.
 *
 * <p>Controls: LMB select piece · Raise/Lower buttons or +/- keys · Reset returns a piece to
 * the auto height · MMB-drag orbit, Shift+MMB pan, scroll zoom, A/D/W/S orbit.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class WallRefineScreen extends PolishedScreen {

    private WallScreenPayloads.OpenWallPreview payload;
    private List<WallScreenPayloads.PieceLite> pieces;
    /** Selected piece identity — slot-start anchor, stable across refreshes. */
    private long selectedAnchor = Long.MIN_VALUE;

    private double centerX;
    private double centerY;
    private double centerZ;
    private float yaw = 45f;
    private float pitch = 35f;
    private float zoom = 5f;
    private float panX;
    private float panY;
    private boolean orbiting;
    private boolean panning;

    /** The WallPreviewScreen that opened this view — Escape/Close returns to it (with the
     *  latest payload) instead of dumping to the world. Null when opened via command. */
    @org.jetbrains.annotations.Nullable
    private WallPreviewScreen parentPreview;

    public WallRefineScreen(WallScreenPayloads.OpenWallPreview payload) {
        super(Component.translatable("bannerbound.wall_refine.title"));
        accept(payload);
    }

    public void setParentPreview(@org.jetbrains.annotations.Nullable WallPreviewScreen parent) {
        this.parentPreview = parent;
    }

    @Override
    public void onClose() {
        if (parentPreview != null && this.minecraft != null) {
            // Hand the freshest data back — refine actions changed it while we were open.
            parentPreview.refreshWalls(payload);
            this.minecraft.setScreen(parentPreview);
        } else {
            super.onClose();
        }
    }

    /** Every design the pieces reference (payload order) — each piece carries its INDEX, so
     *  per-piece variant overrides render their REAL player-authored blocks. */
    private List<com.bannerbound.core.api.walls.WallDesign> designList = List.of();
    /** False = design data missing or the wall is huge — fall back to the old solid boxes. */
    private boolean blocksMode;
    /** Screen-depth compression for the block pass (same trick as the designer viewport). */
    private static final float DEPTH_SQUASH = 0.12f;

    private void accept(WallScreenPayloads.OpenWallPreview newPayload) {
        this.payload = newPayload;
        this.pieces = newPayload.pieces();
        double sx = 0, sy = 0, sz = 0;
        int n = 0;
        for (WallScreenPayloads.PieceLite p : pieces) {
            sx += p.startX();
            sy += (p.baseY() + p.topY()) / 2.0;
            sz += p.startZ();
            n++;
        }
        if (n > 0) {
            centerX = sx / n;
            centerY = sy / n;
            centerZ = sz / n;
        }
        this.designList = newPayload.designs();
        long cells = 0;
        for (WallScreenPayloads.PieceLite p : pieces) {
            com.bannerbound.core.api.walls.WallDesign d = designFor(p);
            if (d != null) cells += d.blockCount();
        }
        blocksMode = !designList.isEmpty() && cells > 0 && cells <= 12000;
    }

    /** The design a piece renders with (its payload index), or null. */
    @Nullable
    private com.bannerbound.core.api.walls.WallDesign designFor(WallScreenPayloads.PieceLite p) {
        int index = p.designIndex();
        return index >= 0 && index < designList.size() ? designList.get(index) : null;
    }

    /** Consumes a refreshed payload after a refine/gate/construct action. */
    public void refreshWalls(WallScreenPayloads.OpenWallPreview newPayload) {
        long keep = selectedAnchor;
        accept(newPayload);
        selectedAnchor = keep; // anchors are stable across recomputes
    }

    /** Right tool-panel metrics — backdrop drawn in renderPolishedBackdrop, group labels too. */
    private static final int PANEL_W = 124;

    /** File · Edit · Go — clicks checked first, dropdown drawn last. */
    @org.jetbrains.annotations.Nullable
    private WallMenuBar menuBar;

    @Override
    protected void init() {
        menuBar = new WallMenuBar(this.font, 8, 4, List.of(
            new WallMenuBar.Menu("File", List.of(
                WallMenuBar.Item.of("Construct", () -> PacketDistributor.sendToServer(
                    new WallScreenPayloads.ConstructWalls())))),
            new WallMenuBar.Menu("Edit", List.of(
                new WallMenuBar.Item("Raise +1", () -> sendRefine(1),
                    () -> selectedAnchor != Long.MIN_VALUE),
                new WallMenuBar.Item("Lower -1", () -> sendRefine(-1),
                    () -> selectedAnchor != Long.MIN_VALUE),
                new WallMenuBar.Item("Reset Height", () -> sendRefine(0),
                    () -> selectedAnchor != Long.MIN_VALUE),
                new WallMenuBar.Item("Toggle Gate", () -> {
                    WallScreenPayloads.PieceLite sel = selectedPiece();
                    if (sel != null && sel.kindOrdinal() != 1) {
                        PacketDistributor.sendToServer(
                            new WallScreenPayloads.ToggleWallGate(sel.anchor()));
                    }
                    // Corners can't hold gates — runs are clipped around them.
                }, () -> selectedPiece() != null && selectedPiece().kindOrdinal() != 1))),
            new WallMenuBar.Menu("Go", List.of(
                WallMenuBar.Item.of("Flat Preview", () -> {
                    if (parentPreview != null) {
                        onClose();
                    } else if (this.minecraft != null) {
                        this.minecraft.setScreen(new WallPreviewScreen(payload));
                    }
                }),
                WallMenuBar.Item.of("Wall Designer", () -> PacketDistributor.sendToServer(
                    new WallScreenPayloads.RequestWallDesigner())),
                WallMenuBar.Item.of("Back  (Esc)", this::onClose)))));
        int x = this.width - PANEL_W + 8;
        int w = PANEL_W - 16;
        addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.wall_refine.raise"), b -> sendRefine(1))
            .bounds(x, 44, w, 18).accent(primaryAccent()).build());
        addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.wall_refine.lower"), b -> sendRefine(-1))
            .bounds(x, 66, w, 18).accent(primaryAccent()).build());
        addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.wall_refine.reset_height"), b -> sendRefine(0))
            .bounds(x, 88, w, 18)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.wall_refine.reset_height.tooltip")))
            .accent(primaryAccent())
            .build());
        // PIECE group: per-piece variant + foundation refinements (playtest 2026-06-12).
        addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.wall_refine.variant_cycle"), b -> {
            WallScreenPayloads.PieceLite sel = selectedPiece();
            if (sel == null) return;
            if (sel.kindOrdinal() == 2) {
                ClientWallStatus.set("Gates have no variants.", true);
            } else {
                PacketDistributor.sendToServer(
                    new WallScreenPayloads.CycleWallVariant(sel.refineAnchor()));
            }
        }).bounds(x, 122, w, 18)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.wall_refine.variant_cycle.tooltip")))
            .accent(primaryAccent())
            .build());
        addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.wall_refine.foundation_toggle"), b -> {
            WallScreenPayloads.PieceLite sel = selectedPiece();
            if (sel != null) {
                PacketDistributor.sendToServer(
                    new WallScreenPayloads.ToggleWallFoundation(sel.refineAnchor()));
            }
        }).bounds(x, 144, w, 18)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.wall_refine.foundation_toggle.tooltip")))
            .accent(primaryAccent())
            .build());
        addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.wall_refine.toggle_gate"), b -> {
            WallScreenPayloads.PieceLite sel = selectedPiece();
            // Corners can't hold gates — runs are clipped around them (playtest 2026-06-12).
            if (sel != null && sel.kindOrdinal() != 1) {
                // Gates key on the SLOT anchor (y=0), not the kind-aware refine key.
                PacketDistributor.sendToServer(new WallScreenPayloads.ToggleWallGate(sel.anchor()));
            } else if (sel != null) {
                ClientWallStatus.set("Corners can't hold gates — pick a wall segment.", true);
            }
        }).bounds(x, 184, w, 18).accent(primaryAccent()).build());
        addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.wall_refine.construct"),
                b -> PacketDistributor.sendToServer(new WallScreenPayloads.ConstructWalls()))
            .bounds(x, 224, w, 18)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.wall_refine.construct.tooltip")))
            .accent(primaryAccent())
            .build());
        addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.wall_refine.flat_preview"), b -> {
                // Prefer the live parent (keeps ITS Town Hall back-link); fall back to a
                // fresh preview when opened via command.
                if (parentPreview != null) {
                    onClose();
                } else if (this.minecraft != null) {
                    this.minecraft.setScreen(new WallPreviewScreen(payload));
                }
            })
            .bounds(x, 264, w, 18).accent(primaryAccent()).build());
        addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.wall_refine.close"), b -> onClose())
            .bounds(x, 286, w, 18).accent(primaryAccent()).build());
    }

    private void sendRefine(int delta) {
        if (selectedAnchor != Long.MIN_VALUE) {
            PacketDistributor.sendToServer(new WallScreenPayloads.RefineWallTop(selectedAnchor, delta));
        }
    }

    // ─── Projection (axonometric, world coords relative to the wall's centroid) ─────────────

    /** Returns {screenX, screenY, viewDepth} — depth backs the painter's-algorithm sort. */
    private double[] project(double wx, double wy, double wz) {
        double px = wx - centerX;
        double py = wy - centerY;
        double pz = wz - centerZ;
        double b = Math.toRadians(-yaw);
        double x1 = px * Math.cos(b) + pz * Math.sin(b);
        double z1 = -px * Math.sin(b) + pz * Math.cos(b);
        double a = Math.toRadians(pitch);
        double y2 = py * Math.cos(a) - z1 * Math.sin(a);
        double z2 = py * Math.sin(a) + z1 * Math.cos(a);
        return new double[]{this.width / 2.0 + panX + zoom * x1,
            this.height / 2.0 + panY - zoom * y2, z2};
    }

    /** Filled (possibly non-rectangular) projected quad — both windings, no culling games. */
    private void quad(GuiGraphics g, double[] a, double[] b, double[] c, double[] d, int argb) {
        org.joml.Matrix4f mat = g.pose().last().pose();
        com.mojang.blaze3d.vertex.VertexConsumer vc = net.minecraft.client.Minecraft.getInstance()
            .renderBuffers().bufferSource()
            .getBuffer(net.minecraft.client.renderer.RenderType.gui());
        vc.addVertex(mat, (float) a[0], (float) a[1], 0).setColor(argb);
        vc.addVertex(mat, (float) b[0], (float) b[1], 0).setColor(argb);
        vc.addVertex(mat, (float) c[0], (float) c[1], 0).setColor(argb);
        vc.addVertex(mat, (float) d[0], (float) d[1], 0).setColor(argb);
        vc.addVertex(mat, (float) d[0], (float) d[1], 0).setColor(argb);
        vc.addVertex(mat, (float) c[0], (float) c[1], 0).setColor(argb);
        vc.addVertex(mat, (float) b[0], (float) b[1], 0).setColor(argb);
        vc.addVertex(mat, (float) a[0], (float) a[1], 0).setColor(argb);
    }

    /** Lambert factor for a fixed sun from the north-west, above — consistent face shading
     *  from every orbit angle. */
    private static float faceLight(int nx, int ny, int nz) {
        // Normalized light direction ≈ (-0.458, 0.815, -0.356).
        double dot = nx * -0.458 + ny * 0.815 + nz * -0.356;
        return (float) (0.55 + 0.5 * Math.max(0, dot));
    }

    private static int shade(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((argb & 0xFF) * factor));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static void seg(GuiGraphics g, double[] a, double[] b, int color, float px) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double len = Math.hypot(dx, dy);
        if (len < 0.5) return;
        g.pose().pushPose();
        g.pose().translate(a[0], a[1], 0);
        g.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(dy, dx)));
        int half = Math.max(1, Math.round(px / 2f));
        g.fill(0, -half, (int) Math.ceil(len), half, color);
        g.pose().popPose();
    }

    /** The piece's world-space box corners: start column → along × length, inward × depth. */
    private double[][] pieceCorners(WallScreenPayloads.PieceLite p) {
        Direction outward = Direction.from2DDataValue(p.outward2d());
        Direction along = outward.getClockWise();
        Direction inward = outward.getOpposite();
        double x0 = p.startX();
        double z0 = p.startZ();
        double x1 = x0 + along.getStepX() * p.length() + inward.getStepX() * p.depth();
        double z1 = z0 + along.getStepZ() * p.length() + inward.getStepZ() * p.depth();
        double minX = Math.min(x0, x1);
        double maxX = Math.max(x0, x1) + (along.getStepX() == 0 && inward.getStepX() == 0 ? 1 : 0);
        double minZ = Math.min(z0, z1);
        double maxZ = Math.max(z0, z1) + (along.getStepZ() == 0 && inward.getStepZ() == 0 ? 1 : 0);
        double y0 = p.baseY();
        double y1 = p.topY() + 1;
        return new double[][]{
            {minX, y0, minZ}, {maxX, y0, minZ}, {minX, y0, maxZ}, {maxX, y0, maxZ},
            {minX, y1, minZ}, {maxX, y1, minZ}, {minX, y1, maxZ}, {maxX, y1, maxZ}};
    }

    /** Terrain plate + earth skirt for one piece — the strip of land the wall stands on. */
    private void drawPieceGround(GuiGraphics g, WallScreenPayloads.PieceLite p) {
        double[][] c = pieceCorners(p);
        double groundY = p.maxGround() + 1;
        quad(g, project(c[0][0], groundY, c[0][2]), project(c[1][0], groundY, c[1][2]),
            project(c[3][0], groundY, c[3][2]), project(c[2][0], groundY, c[2][2]), 0x8044B05C);
        double skirtBot = groundY - 2.5;
        double[][] gt = new double[4][];
        double[][] gb = new double[4][];
        for (int i = 0; i < 4; i++) {
            gt[i] = project(c[i][0], groundY, c[i][2]);
            gb[i] = project(c[i][0], skirtBot, c[i][2]);
        }
        int earth = 0x80564233;
        quad(g, gt[0], gt[1], gb[1], gb[0], earth);
        quad(g, gt[2], gt[3], gb[3], gb[2], earth);
        quad(g, gt[0], gt[2], gb[2], gb[0], earth);
        quad(g, gt[1], gt[3], gb[3], gb[1], earth);
    }

    /** Kind-colored top outline (thick yellow when selected) — piece identification now that
     *  the body renders as real blocks. */
    private void drawTopOutline(GuiGraphics g, WallScreenPayloads.PieceLite p, boolean selected) {
        double[][] c = pieceCorners(p);
        double[] s4 = project(c[4][0], c[4][1], c[4][2]);
        double[] s5 = project(c[5][0], c[5][1], c[5][2]);
        double[] s6 = project(c[6][0], c[6][1], c[6][2]);
        double[] s7 = project(c[7][0], c[7][1], c[7][2]);
        int edge = selected ? 0xFFFFE040 : switch (p.kindOrdinal()) {
            case 1 -> 0xFFE08A28; // corner
            case 2 -> 0xFF35C060; // gate
            default -> 0x80AAAAB8; // segment — subtle
        };
        float px = selected ? 2.5f : 1f;
        seg(g, s4, s5, edge, px);
        seg(g, s5, s7, edge, px);
        seg(g, s7, s6, edge, px);
        seg(g, s6, s4, edge, px);
    }

    /**
     * FALLBACK shaded box (used when design data is missing or the wall is enormous).
     * Painter's-algorithm ordering happens in the caller.
     */
    private void drawPieceSolid(GuiGraphics g, WallScreenPayloads.PieceLite p, int base,
                                boolean selected) {
        double[][] c = pieceCorners(p);
        double[][] s = new double[8][];
        for (int i = 0; i < 8; i++) {
            s[i] = project(c[i][0], c[i][1], c[i][2]);
        }
        drawPieceGround(g, p);
        // Foundation stem: base floating above the lowest ground = visible dip cost.
        if (p.baseY() > p.minGround() + 1) {
            double midX = (c[0][0] + c[3][0]) / 2.0;
            double midZ = (c[0][2] + c[3][2]) / 2.0;
            seg(g, project(midX, p.minGround() + 1, midZ), project(midX, p.baseY(), midZ),
                0xFF5577AA, 2f);
        }
        // Sun-direction shading (light from NW-above) so every box reads consistently from
        // any orbit angle — fixed per-axis factors looked "inverted" from some sides.
        quad(g, s[0], s[2], s[6], s[4], shade(base, faceLight(-1, 0, 0)));  // x-min (west)
        quad(g, s[1], s[3], s[7], s[5], shade(base, faceLight(1, 0, 0)));   // x-max (east)
        quad(g, s[0], s[1], s[5], s[4], shade(base, faceLight(0, 0, -1))); // z-min (north)
        quad(g, s[2], s[3], s[7], s[6], shade(base, faceLight(0, 0, 1)));  // z-max (south)
        quad(g, s[4], s[5], s[7], s[6], shade(base, faceLight(0, 1, 0)));  // top
        drawTopOutline(g, p, selected);
    }

    /**
     * The REAL wall: every piece's design voxels rendered as depth-tested blocks through the
     * same pose convention as {@link #project} (so 2D plates/outlines line up exactly), plus
     * TRANSLUCENT foundation extensions — the bottom course continuing down to the deepest
     * ground ("use the actual designs here, and show the foundation extensions", playtest
     * 2026-06-12). Depth testing also retires the painter's-algorithm face inversions.
     */
    private void renderDesignBlocks(GuiGraphics g) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        com.mojang.blaze3d.vertex.PoseStack pose = g.pose();
        // Clip below the menu bar; clamped so the rect can't invert on very short/narrow windows.
        g.enableScissor(0, Math.min(25, Math.max(0, this.height - 1)),
            Math.max(1, this.width - PANEL_W), this.height);
        pose.pushPose();
        pose.translate(this.width / 2.0 + panX, this.height / 2.0 + panY, 400);
        pose.last().pose().scale(1f, 1f, DEPTH_SQUASH); // position matrix only — see designer
        pose.scale(zoom, -zoom, zoom);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
        pose.translate(-centerX, -centerY, -centerZ);
        net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers =
            mc.renderBuffers().bufferSource();
        net.minecraft.client.renderer.MultiBufferSource solid =
            type -> buffers.getBuffer(noCullBlockType(type, false));
        net.minecraft.client.renderer.MultiBufferSource ghost =
            type -> new GhostTint(buffers.getBuffer(noCullBlockType(type, true)));
        com.mojang.blaze3d.platform.Lighting.setupForEntityInInventory();
        for (WallScreenPayloads.PieceLite p : pieces) {
            if (p.waterGap()) continue;
            // Per-piece design (variant overrides included) by payload index.
            com.bannerbound.core.api.walls.WallDesign design = designFor(p);
            if (design == null) continue;
            Direction outward = Direction.from2DDataValue(p.outward2d());
            Direction along = outward.getClockWise();
            Direction inward = outward.getOpposite();
            net.minecraft.world.level.block.Rotation rot = switch (outward) {
                case EAST -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
                case SOUTH -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
                case WEST -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
                default -> net.minecraft.world.level.block.Rotation.NONE;
            };
            int len = Math.min(p.length(), design.length());
            int dep = Math.min(p.depth(), design.depth());
            for (int l = 0; l < len; l++) {
                for (int d = 0; d < dep; d++) {
                    int x = p.startX() + along.getStepX() * l + inward.getStepX() * d;
                    int z = p.startZ() + along.getStepZ() * l + inward.getStepZ() * d;
                    for (int h = 0; h < design.height(); h++) {
                        net.minecraft.world.level.block.state.BlockState state =
                            design.stateAt(l, d, h);
                        if (state == null) continue;
                        pose.pushPose();
                        pose.translate(x, p.baseY() + h, z);
                        mc.getBlockRenderer().renderSingleBlock(state.rotate(rot), pose, solid,
                            net.minecraft.client.renderer.LightTexture.FULL_BRIGHT,
                            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
                        pose.popPose();
                    }
                    // Foundation extension: the BOTTOM course block continues down to the
                    // deepest ground under this piece — translucent (below-grade). Mirrors
                    // forEachBlock: per-piece suppression + never continue openables.
                    net.minecraft.world.level.block.state.BlockState bottom =
                        design.stateAt(l, d, 0);
                    boolean openable = bottom != null
                        && (bottom.is(net.minecraft.tags.BlockTags.DOORS)
                            || bottom.is(net.minecraft.tags.BlockTags.FENCE_GATES)
                            || bottom.is(net.minecraft.tags.BlockTags.TRAPDOORS));
                    if (!p.noFoundation() && !openable
                        && bottom != null && p.baseY() > p.minGround() + 1) {
                        net.minecraft.world.level.block.state.BlockState continued =
                            bottom.rotate(rot);
                        for (int y = p.minGround() + 1; y < p.baseY(); y++) {
                            pose.pushPose();
                            pose.translate(x, y, z);
                            mc.getBlockRenderer().renderSingleBlock(continued, pose, ghost,
                                net.minecraft.client.renderer.LightTexture.FULL_BRIGHT,
                                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
                            pose.popPose();
                        }
                    }
                }
            }
        }
        buffers.endBatch();
        com.mojang.blaze3d.platform.Lighting.setupForFlatItems();
        pose.popPose();
        g.disableScissor();
    }

    /** GUI no-cull remap — same rationale as the designer viewport. */
    private static net.minecraft.client.renderer.RenderType noCullBlockType(
            net.minecraft.client.renderer.RenderType requested, boolean forceTranslucent) {
        boolean translucent = forceTranslucent
            || requested == net.minecraft.client.renderer.RenderType.translucent()
            || requested == net.minecraft.client.renderer.Sheets.translucentCullBlockSheet()
            || requested == net.minecraft.client.renderer.Sheets.translucentItemSheet();
        return translucent
            ? net.minecraft.client.renderer.RenderType.entityTranslucent(
                net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
            : net.minecraft.client.renderer.RenderType.entityCutoutNoCull(
                net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS);
    }

    /** Alpha-scaling consumer for the below-grade foundation ghosts. */
    private record GhostTint(com.mojang.blaze3d.vertex.VertexConsumer delegate)
        implements com.mojang.blaze3d.vertex.VertexConsumer {
        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) {
            delegate.setColor(r, g, b, a * 120 / 255);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }

    @Override
    protected void renderPolishedBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xFF101014);
        // Painter's algorithm: draw far pieces first (project()'s view depth — higher z is
        // closer to the viewer in this space).
        java.util.List<WallScreenPayloads.PieceLite> sorted = new java.util.ArrayList<>(pieces);
        // Tie-break: corners/gates draw after segments at equal depth, mirroring the
        // blueprint's corner > gate > segment precedence.
        sorted.sort(java.util.Comparator.comparingDouble(p ->
            project(p.startX() + p.length() / 2.0, (p.baseY() + p.topY()) / 2.0,
                p.startZ() + p.depth() / 2.0)[2] + p.kindOrdinal() * 0.001));
        for (WallScreenPayloads.PieceLite p : sorted) {
            if (p.waterGap()) continue;
            if (blocksMode) {
                drawPieceGround(g, p); // blocks render the body; ground stays a 2D plate
            } else {
                boolean selected = p.refineAnchor() == selectedAnchor;
                int color = switch (p.kindOrdinal()) {
                    case 1 -> 0xFFE08A28; // corner
                    case 2 -> 0xFF35C060; // gate
                    default -> 0xFF9A9AA8; // segment
                };
                drawPieceSolid(g, p, selected ? 0xFFE0C040 : color, selected);
            }
        }
        if (blocksMode) {
            renderDesignBlocks(g);
            // Identification outlines + selection ABOVE the blocks' depth range.
            g.pose().pushPose();
            g.pose().translate(0, 0, 700);
            for (WallScreenPayloads.PieceLite p : pieces) {
                if (p.waterGap()) continue;
                drawTopOutline(g, p, p.refineAnchor() == selectedAnchor);
            }
            g.pose().popPose();
        }
        // Settlement anchor: a red marker box at the town hall's chunk center so the view has
        // a "where is home" reference. Raised above the blocks' depth range.
        g.pose().pushPose();
        g.pose().translate(0, 0, 700);
        net.minecraft.core.BlockPos th = payload.base().townHallChunkOrigin();
        double thx = th.getX() + 8;
        double thz = th.getZ() + 8;
        double thy = centerY;
        quad(g, project(thx - 2, thy, thz - 2), project(thx + 2, thy, thz - 2),
            project(thx + 2, thy, thz + 2), project(thx - 2, thy, thz + 2), 0xFFC04040);
        double[] thLabel = project(thx, thy + 2, thz);
        g.drawCenteredString(this.font, "Town Hall", (int) thLabel[0], (int) thLabel[1], 0xFFE08080);
        g.pose().popPose();

        // ─── Chrome: header strip, right tool panel, selection card ────────────────────────
        // (The bare floating buttons "looked very placeholder", playtest 2026-06-12.)
        // Fills stay translucent over the 3D god-view; the banner identity rides the 1px
        // edge lines instead of the panels themselves.
        g.fill(0, 0, this.width, 24, 0xC8101016);
        drawIdentityDivider(g, 0, 24, this.width, identityAccents);
        g.drawCenteredString(this.font, "Wall Refinement", this.width / 2, 8, 0xFFFFFFFF);

        int px = this.width - PANEL_W;
        g.fill(px, 25, this.width, this.height, 0xC8101016);
        identityEdge(g, px, 25, this.height);
        g.drawString(this.font, "HEIGHT", px + 8, 32, 0xFF6F6F78);
        g.drawString(this.font, "PIECE", px + 8, 112, 0xFF6F6F78);
        g.drawString(this.font, "GATE", px + 8, 172, 0xFF6F6F78);
        g.drawString(this.font, "PLAN", px + 8, 212, 0xFF6F6F78);
        g.drawString(this.font, "VIEW", px + 8, 252, 0xFF6F6F78);

        // Selection card (top-left, under the header).
        WallScreenPayloads.PieceLite sel = selectedPiece();
        if (sel != null) {
            String kind = sel.kindOrdinal() == 1 ? "Corner" : sel.kindOrdinal() == 2 ? "Gate" : "Segment";
            String line1 = kind + " @ (" + sel.startX() + ", " + sel.startZ() + ")";
            String line2 = "Top Y " + sel.topY() + " · ground " + sel.minGround()
                + ".." + sel.maxGround();
            com.bannerbound.core.api.walls.WallDesign selDesign = designFor(sel);
            String line3 = "Design: " + (selDesign == null ? "?" : selDesign.name())
                + (sel.noFoundation() ? " · foundation OFF" : "");
            int cw = Math.max(this.font.width(line3),
                Math.max(this.font.width(line1), this.font.width(line2))) + 16;
            g.fill(6, 31, 6 + cw, 75, 0xC8101016);
            drawIdentityDivider(g, 6, 31, cw, identityAccents);
            g.drawString(this.font, line1, 14, 38, 0xFFFFD080);
            g.drawString(this.font, line2, 14, 50, 0xFFC0C0C0);
            g.drawString(this.font, line3, 14, 62, 0xFFA8C8E0);
        } else {
            g.drawString(this.font, "Select a piece, then Raise / Lower (+/-)", 10, 34, 0x90A0A0A8);
        }
        if (payload.hasPlan()) {
            // The committed wall may be an OLDER design than the layout shown here — say so
            // instead of implying the current design is built (playtest 2026-06-12).
            if (payload.planCurrent()) {
                g.drawString(this.font, "Committed plan: " + payload.completenessPercent()
                    + "% built", 10, this.height - 16, 0xFF90E090);
            } else {
                g.drawString(this.font, "An OLDER design is built (" + payload.completenessPercent()
                    + "%) — Construct applies this layout", 10, this.height - 16, 0xFFFFC44D);
            }
        }
    }

    /** 1px vertical identity edge along a panel border (neutral line when no identity) — same
     *  chrome as {@code WallDesignerScreen}'s edge, which this screen's panel copied. */
    private void identityEdge(GuiGraphics g, int x, int y0, int y1) {
        if (identityAccents.isEmpty()) {
            g.fill(x, y0, x + 1, y1, GuiPalette.PANEL_BORDER);
        } else {
            drawIdentityBorder(g, x, y0, 1, y1 - y0, identityAccents);
        }
    }

    @Override
    protected void renderPolishedExtras(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.drawCenteredString(this.font,
            "LMB select · +/- raise/lower · MMB orbit · Shift+MMB pan · scroll zoom · A,D,W,S",
            this.width / 2, this.height - 28, 0x909090);
        ClientWallStatus.render(g, this.font, this.width / 2, 34);
        if (menuBar != null) menuBar.render(g, mouseX, mouseY);
    }

    @Nullable
    private WallScreenPayloads.PieceLite selectedPiece() {
        for (WallScreenPayloads.PieceLite p : pieces) {
            if (p.refineAnchor() == selectedAnchor) return p;
        }
        return null;
    }

    // ─── Input ───────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Menu bar first — its dropdown overlays the scene and must win the click.
        if (menuBar != null && menuBar.mouseClicked(mouseX, mouseY, button)) return true;
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button == 2) {
            if (hasShiftDown()) panning = true;
            else orbiting = true;
            return true;
        }
        if (button == 0) {
            // Nearest piece by projected center, generous radius.
            long best = Long.MIN_VALUE;
            double bestDist = 18.0;
            for (WallScreenPayloads.PieceLite p : pieces) {
                if (p.waterGap()) continue;
                double[][] c = pieceCorners(p);
                double[] s = project((c[0][0] + c[3][0]) / 2.0, (p.baseY() + p.topY() + 1) / 2.0,
                    (c[0][2] + c[3][2]) / 2.0);
                double dist = Math.hypot(mouseX - s[0], mouseY - s[1]);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = p.refineAnchor();
                }
            }
            selectedAnchor = best;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 2) {
            orbiting = false;
            panning = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (button == 2) {
            if (panning) {
                panX -= (float) dx;
                panY += (float) dy;
                return true;
            }
            if (orbiting) {
                yaw += (float) dx * 0.5f;
                pitch = net.minecraft.util.Mth.clamp(pitch + (float) dy * 0.5f, 10f, 80f);
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        zoom = net.minecraft.util.Mth.clamp(zoom + (float) scrollY, 1.5f, 14f);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case 65 -> { yaw -= 15f; return true; }
            case 68 -> { yaw += 15f; return true; }
            case 87 -> { pitch = Math.min(80f, pitch + 10f); return true; }
            case 83 -> { pitch = Math.max(10f, pitch - 10f); return true; }
            case 61, 334 -> { sendRefine(1); return true; }   // = / numpad+
            case 45, 333 -> { sendRefine(-1); return true; }  // - / numpad-
            default -> { }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
