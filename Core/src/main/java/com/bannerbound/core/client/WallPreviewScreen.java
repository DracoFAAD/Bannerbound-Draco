package com.bannerbound.core.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.WallScreenPayloads;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Walls mode on the territory birdseye (WALLS_PLAN.md Phase 3, mockup images 2–3): reuses the
 * Expand Territory screen's synthetic camera, chunk slabs, pan/rotate and projection math, but
 * claiming is disabled — instead the wall layout is drawn as colored lines on the slab plane
 * and clicking a segment slot toggles a gate there. Construct / Cancel buttons drive
 * {@code WallService} via payloads; every server action replies with a refreshed payload that
 * {@link #refreshWalls} consumes in place.
 *
 * <p>Line drawing is screen-space: at the snapped cardinal yaws every border run projects to an
 * axis-aligned screen line, so simple filled rects suffice — no world-space geometry needed.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class WallPreviewScreen extends ExpandTerritoryScreen {

    private static final int COLOR_SEGMENT = 0xFFE04545;
    private static final int COLOR_CORNER = 0xFFFFA020;
    private static final int COLOR_GATE = 0xFF35E065;
    private static final int COLOR_WATER_GAP = 0x903AA0FF;
    private static final int COLOR_HOVER = 0xFFFFFFFF;
    /** Click/hover tolerance around a slot's line, in blocks. */
    private static final double PICK_RANGE = 2.5;

    private List<WallScreenPayloads.PieceLite> pieces;
    private boolean hasPlan;
    private int completeness;
    /** Active gate design's length — hover shows the true span a placed gate would occupy. */
    private int gateLength;
    /** Index into {@link #pieces} of the hovered gate-toggle candidate, or -1. */
    private int hoveredSlot = -1;

    /** Kept whole so the Refine (3D) view can be opened with the same data. */
    private WallScreenPayloads.OpenWallPreview lastPayload;

    // ─── Gate placement aids (playtest 2026-06-12) ─────────────────────────────────────────
    /** Snap radius around the side midpoint / parallel gate centers, in blocks. */
    private static final double SNAP_RANGE = 3.0;
    /** Gate add/remove flash duration. */
    private static final long FLASH_MS = 650;
    /** Snap grabbed by the current hover: 0 = free, 1 = side midpoint, 2 = parallel gate. */
    private int snapKind;
    /** The parallel-side gate the candidate aligned with (snapKind == 2). */
    @org.jetbrains.annotations.Nullable
    private WallScreenPayloads.PieceLite snapAlignedTo;

    private record GateFlash(WallScreenPayloads.PieceLite rect, long atMs, boolean added) {
    }

    private final List<GateFlash> gateFlashes = new java.util.ArrayList<>();

    /** False = the committed (built) wall is an OLDER design than the previewed layout. */
    private boolean planCurrent = true;

    public WallPreviewScreen(WallScreenPayloads.OpenWallPreview payload) {
        super(payload.base());
        this.lastPayload = payload;
        this.pieces = payload.pieces();
        this.hasPlan = payload.hasPlan();
        this.completeness = payload.completenessPercent();
        this.gateLength = payload.gateLength();
        this.planCurrent = payload.planCurrent();
    }

    /** Consumes a refreshed payload after a gate toggle / construct / cancel. */
    public void refreshWalls(WallScreenPayloads.OpenWallPreview payload) {
        List<WallScreenPayloads.PieceLite> before = this.pieces;
        this.lastPayload = payload;
        this.pieces = payload.pieces();
        this.hasPlan = payload.hasPlan();
        this.completeness = payload.completenessPercent();
        this.gateLength = payload.gateLength();
        this.planCurrent = payload.planCurrent();
        refreshData(payload.base());

        // Gate toggle feedback: diff the gate set, flash the changed spans (render pass) and
        // play a door sound — clicks felt unacknowledged (playtest 2026-06-12).
        if (before != null) {
            java.util.Map<Long, WallScreenPayloads.PieceLite> oldGates = new java.util.HashMap<>();
            java.util.Map<Long, WallScreenPayloads.PieceLite> newGates = new java.util.HashMap<>();
            for (WallScreenPayloads.PieceLite p : before) {
                if (p.kindOrdinal() == 2) oldGates.put(p.anchor(), p);
            }
            for (WallScreenPayloads.PieceLite p : pieces) {
                if (p.kindOrdinal() == 2) newGates.put(p.anchor(), p);
            }
            long now = net.minecraft.Util.getMillis();
            boolean added = false;
            boolean removed = false;
            for (java.util.Map.Entry<Long, WallScreenPayloads.PieceLite> e : newGates.entrySet()) {
                if (!oldGates.containsKey(e.getKey())) {
                    gateFlashes.add(new GateFlash(e.getValue(), now, true));
                    added = true;
                }
            }
            for (java.util.Map.Entry<Long, WallScreenPayloads.PieceLite> e : oldGates.entrySet()) {
                if (!newGates.containsKey(e.getKey())) {
                    gateFlashes.add(new GateFlash(e.getValue(), now, false));
                    removed = true;
                }
            }
            Minecraft mc = Minecraft.getInstance();
            if (added) {
                mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                    .forUI(net.minecraft.sounds.SoundEvents.WOODEN_DOOR_OPEN, 1.1f));
            } else if (removed) {
                mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                    .forUI(net.minecraft.sounds.SoundEvents.WOODEN_DOOR_CLOSE, 0.9f));
            }
        }
    }

    @Override
    protected boolean allowChunkClaiming() {
        return false;
    }

    @Override
    protected boolean drawsBaseHud() {
        return false; // we draw our own header/hint — both drew over each other
    }

    /** Whether the sender-only ghost preview is currently shown (client-side toggle state). */
    private boolean ghostPreviewShown = false;

    /** Toolbar strip height — the grouped button bar at the bottom (drawn in renderBackground
     *  so it sits UNDER the widgets but over the world). */
    private static final int TOOLBAR_H = 46;

    /** File · Edit · View · Go — clicks checked first, dropdown drawn last. */
    @org.jetbrains.annotations.Nullable
    private WallMenuBar menuBar;

    @Override
    protected void init() {
        super.init();
        menuBar = new WallMenuBar(this.font, 8, 4, List.of(
            new WallMenuBar.Menu("File", List.of(
                WallMenuBar.Item.of("Construct", () -> PacketDistributor.sendToServer(
                    new WallScreenPayloads.ConstructWalls())),
                new WallMenuBar.Item("Cancel Plan", () -> PacketDistributor.sendToServer(
                    new WallScreenPayloads.CancelWallPlan()), () -> hasPlan))),
            new WallMenuBar.Menu("View", List.of(
                WallMenuBar.Item.of("Toggle Ghost Preview", () -> {
                    ghostPreviewShown = !ghostPreviewShown;
                    PacketDistributor.sendToServer(
                        new WallScreenPayloads.PreviewWallGhosts(ghostPreviewShown));
                }))),
            new WallMenuBar.Menu("Go", List.of(
                WallMenuBar.Item.of("Refine (3D)", () -> {
                    if (this.minecraft != null) {
                        WallRefineScreen refine = new WallRefineScreen(lastPayload);
                        refine.setParentPreview(this);
                        this.minecraft.setScreen(refine);
                    }
                }),
                WallMenuBar.Item.of("Wall Designer", () -> PacketDistributor.sendToServer(
                    new WallScreenPayloads.RequestWallDesigner())),
                WallMenuBar.Item.of("Back  (Esc)", this::onClose)))));
        int midX = this.width / 2;
        int by = this.height - 26;
        // Grouped toolbar (View | Plan | Design) — "a bunch of buttons thrown together",
        // playtest 2026-06-12. Group labels + separators render in renderBackground.
        addRenderableWidget(PolishButton.polished(previewLabel(), b -> {
                ghostPreviewShown = !ghostPreviewShown;
                b.setMessage(previewLabel());
                PacketDistributor.sendToServer(
                    new WallScreenPayloads.PreviewWallGhosts(ghostPreviewShown));
            })
            .bounds(midX - 241, by, 100, 20)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.wall_preview.ghosts.tooltip")))
            .build());
        addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.wall_preview.construct"),
                b -> PacketDistributor.sendToServer(new WallScreenPayloads.ConstructWalls()))
            .bounds(midX - 129, by, 90, 20)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.wall_preview.construct.tooltip")))
            .build());
        addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.wall_preview.cancel_plan"),
                b -> PacketDistributor.sendToServer(new WallScreenPayloads.CancelWallPlan()))
            .bounds(midX - 35, by, 90, 20)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.wall_preview.cancel_plan.tooltip")))
            .build());
        addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.wall_preview.refine"), b -> {
                if (this.minecraft != null) {
                    WallRefineScreen refine = new WallRefineScreen(lastPayload);
                    refine.setParentPreview(this); // Escape comes back HERE, data intact
                    this.minecraft.setScreen(refine);
                }
            })
            .bounds(midX + 67, by, 90, 20)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.wall_preview.refine.tooltip")))
            .build());
        addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.wall_preview.designs"),
                b -> PacketDistributor.sendToServer(new WallScreenPayloads.RequestWallDesigner()))
            .bounds(midX + 161, by, 80, 20)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.wall_preview.designs.tooltip")))
            .build());
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        // Toolbar backdrop: under the widgets (renderBackground runs before them), over the
        // world. Groups: View | Plan | Design.
        int midX = this.width / 2;
        int top = this.height - TOOLBAR_H;
        g.fill(0, top, this.width, this.height, 0xB80E0E12);
        g.fill(0, top, this.width, top + 1, 0xFF2E2E36);
        int labelY = top + 5;
        g.drawCenteredString(this.font, "VIEW", midX - 191, labelY, 0xFF6F6F78);
        g.drawCenteredString(this.font, "PLAN", midX - 37, labelY, 0xFF6F6F78);
        g.drawCenteredString(this.font, "DESIGN", midX + 154, labelY, 0xFF6F6F78);
        int sepTop = top + 6;
        g.fill(midX - 135, sepTop, midX - 134, this.height - 6, 0xFF2E2E36);
        g.fill(midX + 61, sepTop, midX + 62, this.height - 6, 0xFF2E2E36);
    }

    private Component previewLabel() {
        return Component.translatable(ghostPreviewShown
            ? "bannerbound.wall_preview.ghosts.hide" : "bannerbound.wall_preview.ghosts.show");
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (phase != Phase.OPEN) return;

        hoveredSlot = pickSlot(mouseX, mouseY);
        // Two passes: corners draw LAST so their 2×2 squares sit on top of the depth-1
        // segment strips at the junctions (same precedence the blueprint uses) — drawing in
        // list order let segments clip the corner squares.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < pieces.size(); i++) {
                WallScreenPayloads.PieceLite piece = pieces.get(i);
                boolean corner = piece.kindOrdinal() == 1; // WallDesign.Kind.CORNER
                if (corner != (pass == 1)) continue;
                int color = piece.waterGap() ? COLOR_WATER_GAP : switch (piece.kindOrdinal()) {
                    case 1 -> COLOR_CORNER;
                    case 2 -> COLOR_GATE; // WallDesign.Kind.GATE
                    default -> COLOR_SEGMENT;
                };
                drawPieceRect(g, piece, piece.length(), i == hoveredSlot && piece.kindOrdinal() == 2
                    ? COLOR_HOVER : color);
            }
        }
        // Gate toggle feedback: short flash where a gate was just added (white pop over the
        // green) or removed (red fade-out) — "hard to tell when you removed/added another
        // gate", playtest 2026-06-12. Paired with a door-sound in refreshWalls.
        long now = net.minecraft.Util.getMillis();
        gateFlashes.removeIf(f -> now - f.atMs() > FLASH_MS);
        for (GateFlash flash : gateFlashes) {
            float t = (now - flash.atMs()) / (float) FLASH_MS;
            int a = (int) ((1f - t) * 210f);
            drawPieceRect(g, flash.rect(), flash.rect().length(),
                (a << 24) | (flash.added() ? 0xFFFFFF : 0xF24545));
        }

        // Hovering a SEGMENT previews the FULL gate span CENTERED on the mouse — placement is
        // 1-block granular along the run (slot-snapped gates could never sit centered on a
        // side, playtest 2026-06-12), so the hover shows exactly where a click would put it.
        // A tick marks the side's midpoint; the span SNAPS to it and to gates on parallel
        // sides, with a label + guide line saying which snap grabbed it.
        if (hoveredSlot >= 0 && pieces.get(hoveredSlot).kindOrdinal() == 0) {
            WallScreenPayloads.PieceLite p = pieces.get(hoveredSlot);
            long anchor = centeredGateAnchor(p, mouseX, mouseY);
            boolean alongIsX = Direction.from2DDataValue(p.outward2d()).getClockWise().getStepX() != 0;
            // Midpoint tick (2 blocks wide), bright when the candidate snapped onto it.
            double sideCenter = runCenter(p, alongIsX);
            drawPieceRect(g, synthetic(p, (int) Math.floor(sideCenter) - 1, 2, 0), 2,
                snapKind == 1 ? 0xE0FFE060 : 0x70FFFFFF);
            // The candidate span itself, gently pulsing.
            int pulse = 195 + (int) (60 * Math.sin(now / 150.0));
            WallScreenPayloads.PieceLite ghost = new WallScreenPayloads.PieceLite(
                BlockPos.getX(anchor), BlockPos.getZ(anchor), gateLength, p.depth(),
                p.outward2d(), 2, false, p.baseY(), p.designHeight(),
                p.minGround(), p.maxGround(), 0, false);
            drawPieceRect(g, ghost, gateLength,
                (pulse << 24) | (snapKind != 0 ? 0xFFE060 : 0xFFFFFF));
            // Alignment guide: a thin line across to the parallel gate this span mirrors.
            if (snapKind == 2 && snapAlignedTo != null) {
                drawAlignGuide(g, p, snapAlignedTo, alongIsX);
            }
            if (snapKind != 0) {
                g.drawString(this.font, snapKind == 1 ? "centered" : "aligned",
                    mouseX + 10, mouseY - 12, 0xFFFFE060);
            }
        }

        String header = !hasPlan ? "Wall Preview"
            : planCurrent ? "Wall Preview — plan committed, " + completeness + "% built"
            : "Wall Preview — an OLDER design is built (" + completeness
                + "%); Construct applies this layout";
        g.drawCenteredString(this.font, header, this.width / 2, 8,
            hasPlan && !planCurrent ? 0xFFFFC44D : 0xFFFFFF);
        g.drawCenteredString(this.font,
            "Click the wall line to place/remove a gate — the span snaps to the side's center "
            + "and to gates on other sides.",
            this.width / 2, 20, 0xC0C0C0);
        ClientWallStatus.render(g, this.font, this.width / 2, 34);
        if (menuBar != null) menuBar.render(g, mouseX, mouseY);
    }

    /** Thin perpendicular guide between the gate candidate and the parallel gate it aligns
     *  with — both share the snapped along-coordinate, so the line is axis-aligned. */
    private void drawAlignGuide(GuiGraphics g, WallScreenPayloads.PieceLite mine,
                                WallScreenPayloads.PieceLite other, boolean alongIsX) {
        double alongC = coveredCenter(other);
        double myCross = (alongIsX ? mine.startZ() : mine.startX()) + 0.5;
        double otherCross = (alongIsX ? other.startZ() : other.startX()) + 0.5;
        int[] a = alongIsX ? worldToScreen(alongC, myCross) : worldToScreen(myCross, alongC);
        int[] b = alongIsX ? worldToScreen(alongC, otherCross) : worldToScreen(otherCross, alongC);
        if (a == null || b == null) return;
        int x0 = Math.min(a[0], b[0]);
        int x1 = Math.max(a[0], b[0]);
        int y0 = Math.min(a[1], b[1]);
        int y1 = Math.max(a[1], b[1]);
        g.fill(x0, y0, Math.max(x1, x0 + 1), Math.max(y1, y0 + 1), 0x90FFE060);
    }

    /**
     * Fills a piece's FULL footprint (block-edge to block-edge, length × depth in world), with
     * {@code lengthOverride} allowing the gate-span hover to paint a longer run than the piece
     * itself. Edge-to-edge spans make adjacent pieces connect into a continuous border line
     * (center-to-center drawing rendered 2-long segments as disconnected dashes) and corners
     * show as proper N×N squares in every rotation.
     */
    private void drawPieceRect(GuiGraphics g, WallScreenPayloads.PieceLite piece,
                               int lengthOverride, int color) {
        Direction outward = Direction.from2DDataValue(piece.outward2d());
        Direction along = outward.getClockWise();
        Direction inward = outward.getOpposite();
        // Opposite footprint corner in block coords (start is always the (l=0,d=0) column).
        int endX = piece.startX() + along.getStepX() * (lengthOverride - 1)
                 + inward.getStepX() * (piece.depth() - 1);
        int endZ = piece.startZ() + along.getStepZ() * (lengthOverride - 1)
                 + inward.getStepZ() * (piece.depth() - 1);
        // Block-EDGE bounds: min corner of the min block, max corner of the max block (+1).
        double minWX = Math.min(piece.startX(), endX);
        double maxWX = Math.max(piece.startX(), endX) + 1;
        double minWZ = Math.min(piece.startZ(), endZ);
        double maxWZ = Math.max(piece.startZ(), endZ) + 1;
        int[] a = worldToScreen(minWX, minWZ);
        int[] b = worldToScreen(maxWX, maxWZ);
        if (a == null || b == null) return;
        int minX = Math.min(a[0], b[0]);
        int maxX = Math.max(a[0], b[0]);
        int minY = Math.min(a[1], b[1]);
        int maxY = Math.max(a[1], b[1]);
        // Depth-1 strips can project to <2 px at far zoom — keep the border readable.
        if (maxX - minX < 2) maxX = minX + 2;
        if (maxY - minY < 2) maxY = minY + 2;
        g.fill(minX, minY, maxX, maxY, color);
    }

    /** Mirror of the base screen's chunkToScreen, for arbitrary world (x, z) block centers. */
    private int[] worldToScreen(double worldX, double worldZ) {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.options == null || mc.getWindow() == null) return null;
        if (yawAnimStartMs >= 0) return null;
        double dWX = worldX - dragCamX;
        double dWZ = worldZ - dragCamZ;
        double screenRight;
        double screenDown;
        switch (snappedYawQuadrant()) {
            case 0   -> { screenRight = -dWX; screenDown = -dWZ; }
            case 90  -> { screenRight = -dWZ; screenDown =  dWX; }
            case 180 -> { screenRight =  dWX; screenDown =  dWZ; }
            case 270 -> { screenRight =  dWZ; screenDown = -dWX; }
            default  -> { screenRight = -dWX; screenDown = -dWZ; }
        }
        double fovRad = Math.toRadians(mc.options.fov().get());
        double visibleH = 2.0 * (cameraY - slabY) * Math.tan(fovRad / 2.0);
        double aspect = (double) mc.getWindow().getGuiScaledWidth()
                      / (double) mc.getWindow().getGuiScaledHeight();
        double visibleW = visibleH * aspect;
        int sx = (int) Math.round((screenRight / visibleW + 0.5) * this.width);
        int sy = (int) Math.round((screenDown / visibleH + 0.5) * this.height);
        return new int[]{sx, sy};
    }

    /** Inverse: mouse px → world (x, z) on the slab plane. Mirror of the base pickChunk. */
    private double[] mouseToWorld(int mouseX, int mouseY) {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.options == null || mc.getWindow() == null) return null;
        if (yawAnimStartMs >= 0) return null;
        double fovRad = Math.toRadians(mc.options.fov().get());
        double visibleH = 2.0 * (cameraY - slabY) * Math.tan(fovRad / 2.0);
        double aspect = (double) mc.getWindow().getGuiScaledWidth()
                      / (double) mc.getWindow().getGuiScaledHeight();
        double visibleW = visibleH * aspect;
        double nx = (double) mouseX / (double) this.width - 0.5;
        double ny = (double) mouseY / (double) this.height - 0.5;
        double[] offset = screenToWorldOffset(nx * visibleW, ny * visibleH);
        return new double[]{dragCamX + offset[0], dragCamZ + offset[1]};
    }

    /** Nearest SEGMENT/GATE slot whose line is within {@link #PICK_RANGE} of the mouse, or -1. */
    private int pickSlot(int mouseX, int mouseY) {
        double[] world = mouseToWorld(mouseX, mouseY);
        if (world == null) return -1;
        int best = -1;
        double bestDist = PICK_RANGE;
        for (int i = 0; i < pieces.size(); i++) {
            WallScreenPayloads.PieceLite piece = pieces.get(i);
            if (piece.waterGap()) continue;
            if (piece.kindOrdinal() != 0 && piece.kindOrdinal() != 2) continue; // SEGMENT or GATE
            Direction along = Direction.from2DDataValue(piece.outward2d()).getClockWise();
            double x0 = piece.startX() + 0.5;
            double z0 = piece.startZ() + 0.5;
            double x1 = x0 + along.getStepX() * (piece.length() - 1);
            double z1 = z0 + along.getStepZ() * (piece.length() - 1);
            double dist = pointToSegment(world[0], world[1], x0, z0, x1, z1);
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    private static double pointToSegment(double px, double pz, double x0, double z0,
                                         double x1, double z1) {
        double dx = x1 - x0;
        double dz = z1 - z0;
        double lenSq = dx * dx + dz * dz;
        double t = lenSq == 0 ? 0 : Math.max(0, Math.min(1,
            ((px - x0) * dx + (pz - z0) * dz) / lenSq));
        double cx = x0 + t * dx;
        double cz = z0 + t * dz;
        return Math.hypot(px - cx, pz - cz);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Menu bar first — its dropdown overlays the map and must win the click.
        if (phase == Phase.OPEN && menuBar != null
            && menuBar.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (phase != Phase.OPEN || button != 0) return false;
        int slot = pickSlot((int) mouseX, (int) mouseY);
        if (slot < 0) return false;
        WallScreenPayloads.PieceLite piece = pieces.get(slot);
        // Existing gate: toggle OFF by its exact anchor. Segment: place a gate CENTERED on
        // the click — 1-block granularity, matching the hover preview.
        long anchor = piece.kindOrdinal() == 2
            ? BlockPos.asLong(piece.startX(), 0, piece.startZ())
            : centeredGateAnchor(piece, (int) mouseX, (int) mouseY);
        PacketDistributor.sendToServer(new WallScreenPayloads.ToggleWallGate(anchor));
        return true;
    }

    /**
     * Gate anchor whose span is centered on the mouse's along-axis position, at 1-block
     * granularity (the layout engine truncates wall fillers into any gate start) — with
     * SNAPPING to the side's midpoint and to gates on parallel sides (mirrored layouts in
     * one click; "really hard to tell where it is centered", playtest 2026-06-12). Sets
     * {@link #snapKind}/{@link #snapAlignedTo} as a side effect; render and click both call
     * this, so what the hover shows is exactly what the click sends. The anchor is the
     * CANONICAL piece start: on descending runs (west/north) that's the covered interval's
     * MAX block. The cross coordinate rides the hovered run piece.
     */
    private long centeredGateAnchor(WallScreenPayloads.PieceLite piece, int mouseX, int mouseY) {
        snapKind = 0;
        snapAlignedTo = null;
        double[] world = mouseToWorld(mouseX, mouseY);
        if (world == null) return BlockPos.asLong(piece.startX(), 0, piece.startZ());
        Direction along = Direction.from2DDataValue(piece.outward2d()).getClockWise();
        boolean alongIsX = along.getStepX() != 0;
        double c = alongIsX ? world[0] : world[1];

        // Snap targets, nearest wins: the hovered side's midpoint, then every gate on a
        // PARALLEL side (same axis, different row) — its center along-coordinate.
        double best = c;
        double bestDist = SNAP_RANGE;
        double sideCenter = runCenter(piece, alongIsX);
        if (Math.abs(c - sideCenter) <= bestDist) {
            best = sideCenter;
            bestDist = Math.abs(c - sideCenter);
            snapKind = 1;
        }
        int myCross = alongIsX ? piece.startZ() : piece.startX();
        for (WallScreenPayloads.PieceLite p : pieces) {
            if (p.kindOrdinal() != 2) continue;
            boolean pAlongIsX =
                Direction.from2DDataValue(p.outward2d()).getClockWise().getStepX() != 0;
            if (pAlongIsX != alongIsX) continue;
            int cross = alongIsX ? p.startZ() : p.startX();
            if (cross == myCross) continue; // same run — clicking that gate removes it
            double center = coveredCenter(p);
            if (Math.abs(c - center) < bestDist) {
                best = center;
                bestDist = Math.abs(c - center);
                snapKind = 2;
                snapAlignedTo = p;
            }
        }

        // Covered world interval [coveredMin, coveredMin + len) centered on the (snapped) c,
        // CLAMPED so the whole span stays inside the run — gates must never eat into the
        // corners ("you shouldn't be able to place gates at corners", playtest 2026-06-12);
        // near a corner the ghost slides inward to the nearest legal spot instead.
        int coveredMin = (int) Math.floor(best - gateLength / 2.0 + 0.5);
        int[] run = runExtent(piece, alongIsX);
        if (run[1] - run[0] + 1 >= gateLength) {
            coveredMin = Math.max(run[0], Math.min(coveredMin, run[1] - gateLength + 1));
        }
        boolean ascending = along.getStepX() + along.getStepZ() > 0;
        int start = ascending ? coveredMin : coveredMin + gateLength - 1;
        return BlockPos.asLong(
            alongIsX ? start : piece.startX(), 0,
            alongIsX ? piece.startZ() : start);
    }

    /** The whole side's covered along-axis interval [min, max] (same pieces as runCenter). */
    private int[] runExtent(WallScreenPayloads.PieceLite piece, boolean alongIsX) {
        int myCross = alongIsX ? piece.startZ() : piece.startX();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (WallScreenPayloads.PieceLite p : pieces) {
            if (p.kindOrdinal() == 1) continue;
            if (p.outward2d() != piece.outward2d()) continue;
            int cross = alongIsX ? p.startZ() : p.startX();
            if (cross != myCross) continue;
            int[] iv = coveredInterval(p);
            min = Math.min(min, iv[0]);
            max = Math.max(max, iv[1]);
        }
        if (min > max) {
            int[] iv = coveredInterval(piece);
            return iv;
        }
        return new int[]{min, max};
    }

    /** The piece's covered along-axis world interval [min, max] (canonical start unwound). */
    private static int[] coveredInterval(WallScreenPayloads.PieceLite p) {
        Direction along = Direction.from2DDataValue(p.outward2d()).getClockWise();
        boolean alongIsX = along.getStepX() != 0;
        int step = along.getStepX() + along.getStepZ();
        int s = alongIsX ? p.startX() : p.startZ();
        return step > 0 ? new int[]{s, s + p.length() - 1} : new int[]{s - (p.length() - 1), s};
    }

    /** Block-edge center of the piece's covered interval. */
    private static double coveredCenter(WallScreenPayloads.PieceLite p) {
        int[] iv = coveredInterval(p);
        return (iv[0] + iv[1] + 1) / 2.0;
    }

    /** Block-edge midpoint of the WHOLE side the piece belongs to (every same-outward,
     *  same-row piece's covered interval, corners excluded). */
    private double runCenter(WallScreenPayloads.PieceLite piece, boolean alongIsX) {
        int myCross = alongIsX ? piece.startZ() : piece.startX();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (WallScreenPayloads.PieceLite p : pieces) {
            if (p.kindOrdinal() == 1) continue;
            if (p.outward2d() != piece.outward2d()) continue;
            int cross = alongIsX ? p.startZ() : p.startX();
            if (cross != myCross) continue;
            int[] iv = coveredInterval(p);
            min = Math.min(min, iv[0]);
            max = Math.max(max, iv[1]);
        }
        if (min > max) return coveredCenter(piece);
        return (min + max + 1) / 2.0;
    }

    /** Synthetic run-aligned rect for drawing aids: covered interval → canonical start. */
    private static WallScreenPayloads.PieceLite synthetic(WallScreenPayloads.PieceLite tmpl,
                                                          int coveredMin, int length,
                                                          int kindOrdinal) {
        Direction along = Direction.from2DDataValue(tmpl.outward2d()).getClockWise();
        boolean alongIsX = along.getStepX() != 0;
        boolean ascending = along.getStepX() + along.getStepZ() > 0;
        int start = ascending ? coveredMin : coveredMin + length - 1;
        return new WallScreenPayloads.PieceLite(
            alongIsX ? start : tmpl.startX(),
            alongIsX ? tmpl.startZ() : start,
            length, tmpl.depth(), tmpl.outward2d(), kindOrdinal, false,
            tmpl.baseY(), tmpl.designHeight(), tmpl.minGround(), tmpl.maxGround(), 0, false);
    }
}
