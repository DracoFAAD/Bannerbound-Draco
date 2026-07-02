package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.SettlementColor;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.building.PenEnclosure;
import com.bannerbound.core.entity.HerderWorkGoal;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws Create-style colored wireframes while the local player holds a Foreman's Rod:
 * <ul>
 *   <li><b>Committed selections</b> — one bounding-box wireframe per active
 *       {@link BlockSelection} in {@link ClientSelectionState}, tinted to the owning settlement
 *       color, with a flicker alpha.</li>
 *   <li><b>Tentative preview</b> — if the held rod has Point A set but not yet committed, a
 *       white wireframe is drawn from A to whatever block the player's crosshair is currently
 *       on (within reach). Updates every frame so the player can frame the box before
 *       right-clicking again to lock in Point B.</li>
 * </ul>
 * Uses vanilla {@link RenderType#lines()} + {@link LevelRenderer#renderLineBox} so lines are
 * depth-tested (occluded by intervening solid blocks) — same look as F3+B entity hitboxes.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class SelectionRenderer {
    private static final float ALPHA_BASE = 0.85f;
    private static final float ALPHA_SWING = 0.15f;
    private static final float FLICKER_HZ = 1.4f;
    /** Preview flickers a bit faster so it reads as "in progress" vs the stable committed boxes. */
    private static final float PREVIEW_FLICKER_HZ = 2.4f;
    /** Per-cube translucent settlement-color overlay alpha. Low enough that block textures still
     *  read clearly through it — same intent as {@link BirdseyeOverlayRenderer}'s slab alphas. */
    private static final float TINT_ALPHA = 0.18f;

    private SelectionRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        ItemStack foremanRod = findHeldRod(player, BannerboundCore.FOREMANS_ROD.get());
        ItemStack homeRod = findHeldRod(player, BannerboundCore.HOUSING_ORDERS.get());

        // HOME-kind visibility gates on holding a Housing Orders rod; while held, EVERY home renders
        // (the bound one full strength, the rest dimmed), grouped by home id — the home twin of the
        // Workshop Orders survey. WORKSTATION selections still gate on holding the Foreman's Rod.
        java.util.UUID boundHomeId = homeRod != null
            ? com.bannerbound.core.item.HousingOrdersItem.boundHomeId(homeRod) : null;
        BlockPos foremanPreviewA = foremanRod != null
            ? foremanRod.get(BannerboundCore.FOREMAN_POINT_A.get()) : null;
        BlockPos homePreviewA = homeRod != null
            ? homeRod.get(BannerboundCore.MARKER_POINT_A.get()) : null;
        BlockPos foremanPreviewB = previewBHover(mc, foremanPreviewA);
        BlockPos homePreviewB = previewBHover(mc, homePreviewA);

        // WORKSHOP-kind visibility gates on a Workshop Orders rod bound to the selection's
        // workshop id (the id rides in the homeId slot — workshops have no anchor block).
        ItemStack workshopRod = findHeldRod(player, BannerboundCore.WORKSHOP_ROD.get());
        java.util.UUID boundWorkshopId = workshopRod != null
            ? com.bannerbound.core.item.WorkshopRodItem.boundWorkshopId(workshopRod) : null;
        BlockPos workshopPreviewA = workshopRod != null
            ? workshopRod.get(BannerboundCore.MARKER_POINT_A.get()) : null;
        BlockPos workshopPreviewB = previewBHover(mc, workshopPreviewA);

        String foremanType = foremanRod != null
            ? foremanRod.get(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get()) : null;
        boolean herderRod = foremanRod != null && HerderWorkGoal.SELECTION_TYPE.equals(foremanType);
        boolean hasWorkstationCommitted = foremanRod != null && !ClientSelectionState.getAll().isEmpty();
        boolean hasHomeSelections = false;
        if (homeRod != null) {
            for (BlockSelection s : ClientSelectionState.getAll()) {
                if (s.kind() == BlockSelection.Kind.HOME) { hasHomeSelections = true; break; }
            }
        }
        boolean stockpileDebug = StockpileDebugState.isActive(mc.level.getGameTime());
        if (!hasHomeSelections && !hasWorkstationCommitted
            && foremanPreviewA == null && homePreviewA == null
            && workshopRod == null
            && !stockpileDebug && !herderRod) return;

        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double time = mc.level.getGameTime() + partial;
        float alpha = ALPHA_BASE + ALPHA_SWING * (float) Math.sin(time * (Math.PI * 2.0 / 20.0) * FLICKER_HZ);

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        var consumer = buffer.getBuffer(RenderType.lines());

        // ── WORKSTATION selections: per-box AABB wireframe (unchanged). ──────────────────────
        if (foremanRod != null) {
            for (BlockSelection sel : ClientSelectionState.getAll()) {
                if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
                if (sel.completed()) continue;
                // Herder pens draw as a flood-filled pen silhouette below, not a 1×1 box.
                if (HerderWorkGoal.SELECTION_TYPE.equals(sel.workstationType())) continue;
                if (foremanType != null && !foremanType.isEmpty()
                    && !foremanType.equals(sel.workstationType())) continue;
                int rgb = ClientIdentityState.primaryRgb(sel.colorIndex());
                float r = ((rgb >> 16) & 0xFF) / 255f;
                float g = ((rgb >> 8) & 0xFF) / 255f;
                float b = (rgb & 0xFF) / 255f;
                LevelRenderer.renderLineBox(pose, consumer,
                    sel.minX(), sel.minY(), sel.minZ(),
                    sel.maxX() + 1, sel.maxY() + 1, sel.maxZ() + 1,
                    r, g, b, alpha);
            }
        }

        // ── HOME selections: while the Housing Orders rod is held, EVERY home renders (the bound one
        //    at full strength, the rest dimmed) so the player can survey all their homes at a glance —
        //    grouped by home id (it rides in the homeId slot; homes have no anchor block). Same
        //    settlement-coloured silhouette + face-culled tint as the workshop survey.
        //
        //    The silhouette (lines pass): for each solid cube, draw the 4 edges of any face whose
        //    neighbour cube is NOT marked, so edges interior to a flat surface are skipped — just
        //    the outer outline, no per-block grid. The tint (filled pass) MUST run after all line
        //    work is done: getBuffer(debugQuads) ends the in-flight lines build, so a later lines
        //    call would crash with "Not building!". Collect here, draw silhouettes now, tint at the
        //    bottom once every line caller has finished. ──────────────────────────────────────────
        java.util.List<java.util.Map.Entry<java.util.Set<BlockPos>, float[]>> homeRenders =
            new java.util.ArrayList<>();
        if (homeRod != null) {
            java.util.Map<java.util.UUID, java.util.Set<BlockPos>> homeGroups = new java.util.HashMap<>();
            java.util.Map<java.util.UUID, float[]> homeColors = new java.util.HashMap<>();
            for (BlockSelection sel : ClientSelectionState.getAll()) {
                if (sel.kind() != BlockSelection.Kind.HOME) continue;
                java.util.UUID hid = sel.homeId();
                homeColors.computeIfAbsent(hid, k -> {
                    int rgb = ClientIdentityState.primaryRgb(sel.colorIndex());
                    float a = hid.equals(boundHomeId) ? alpha : alpha * 0.45f;
                    return new float[]{
                        ((rgb >> 16) & 0xFF) / 255f,
                        ((rgb >> 8) & 0xFF) / 255f,
                        (rgb & 0xFF) / 255f,
                        a
                    };
                });
                java.util.Set<BlockPos> marked =
                    homeGroups.computeIfAbsent(hid, k -> new java.util.HashSet<>());
                for (int x = sel.minX(); x <= sel.maxX(); x++) {
                    for (int y = sel.minY(); y <= sel.maxY(); y++) {
                        for (int z = sel.minZ(); z <= sel.maxZ(); z++) {
                            BlockPos p = new BlockPos(x, y, z);
                            if (marked.contains(p)) continue;
                            if (mc.level.getBlockState(p).isAir()) continue; // don't outline empty space
                            marked.add(p);
                        }
                    }
                }
            }
            for (java.util.Map.Entry<java.util.UUID, java.util.Set<BlockPos>> e : homeGroups.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                float[] c = homeColors.get(e.getKey());
                drawSilhouette(pose, consumer, e.getValue(), c[0], c[1], c[2], c[3]);
                homeRenders.add(java.util.Map.entry(e.getValue(), c));
            }
        }

        // ── WORKSHOP selections: while the rod is held, EVERY workshop renders (the bound one at
        //    full strength, the rest dimmed) so the player can survey all their workplaces at a
        //    glance. Grouped by workshop id (it rides in the homeId slot); the groups also feed
        //    the floating overview labels drawn at the end of this method. ──────────────────────
        java.util.Map<java.util.UUID, java.util.Set<BlockPos>> workshopGroups = new java.util.HashMap<>();
        if (workshopRod != null) {
            java.util.Map<java.util.UUID, float[]> workshopColors = new java.util.HashMap<>();
            for (BlockSelection sel : ClientSelectionState.getAll()) {
                if (sel.kind() != BlockSelection.Kind.WORKSHOP) continue;
                java.util.UUID wid = sel.homeId();
                workshopColors.computeIfAbsent(wid, k -> {
                    int rgb = ClientIdentityState.primaryRgb(sel.colorIndex());
                    float a = wid.equals(boundWorkshopId) ? alpha : alpha * 0.45f;
                    return new float[]{
                        ((rgb >> 16) & 0xFF) / 255f,
                        ((rgb >> 8) & 0xFF) / 255f,
                        (rgb & 0xFF) / 255f,
                        a
                    };
                });
                java.util.Set<BlockPos> marked =
                    workshopGroups.computeIfAbsent(wid, k -> new java.util.HashSet<>());
                for (int x = sel.minX(); x <= sel.maxX(); x++) {
                    for (int y = sel.minY(); y <= sel.maxY(); y++) {
                        for (int z = sel.minZ(); z <= sel.maxZ(); z++) {
                            BlockPos p = new BlockPos(x, y, z);
                            if (marked.contains(p)) continue;
                            if (mc.level.getBlockState(p).isAir()) continue;
                            marked.add(p);
                        }
                    }
                }
            }
            for (java.util.Map.Entry<java.util.UUID, java.util.Set<BlockPos>> e
                    : workshopGroups.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                float[] c = workshopColors.get(e.getKey());
                drawSilhouette(pose, consumer, e.getValue(), c[0], c[1], c[2], c[3]);
                homeRenders.add(java.util.Map.entry(e.getValue(), c));
            }
        }

        // ── HERDER pens: flood-fill the enclosure CLIENT-SIDE and draw it as a pen silhouette (not a
        //    1×1 marker), only while holding a herder-mode rod. Committed pens in settlement colour; the
        //    pen under the crosshair previews in green (valid) so you can frame it before marking. ─────
        if (herderRod) {
            for (BlockSelection sel : ClientSelectionState.getAll()) {
                if (sel.kind() != BlockSelection.Kind.WORKSTATION || sel.completed()) continue;
                if (!HerderWorkGoal.SELECTION_TYPE.equals(sel.workstationType())) continue;
                PenEnclosure.Result pr = PenEnclosure.scan(mc.level,
                    new BlockPos(sel.minX(), sel.minY(), sel.minZ()));
                if (!pr.valid()) continue;
                int rgb = ClientIdentityState.primaryRgb(sel.colorIndex());
                float[] c = { ((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, alpha };
                drawSilhouette(pose, consumer, pr.interior(), c[0], c[1], c[2], c[3]);
                homeRenders.add(java.util.Map.entry(pr.interior(), c));
            }
            if (mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
                PenEnclosure.Result pr = PenEnclosure.scan(mc.level, bh.getBlockPos());
                if (pr.valid()) {
                    drawSilhouette(pose, consumer, pr.interior(), 0.2f, 1.0f, 0.2f, alpha);
                    homeRenders.add(java.util.Map.entry(pr.interior(), new float[]{0.2f, 1.0f, 0.2f, alpha}));
                }
            }
        }

        // ── Stockpile debug wireframe: green interior-floor silhouette (+ tint via homeRenders),
        //    blue boxes on connected containers, a red box on the exact scan-failure spot. Reuses the
        //    same silhouette/box primitives as the home render above. ──────────────────────────────
        if (stockpileDebug) {
            java.util.Set<BlockPos> spInterior = StockpileDebugState.interior();
            if (!spInterior.isEmpty()) {
                drawSilhouette(pose, consumer, spInterior, 0.2f, 1.0f, 0.2f, alpha);
                homeRenders.add(java.util.Map.entry(spInterior, new float[]{0.2f, 1.0f, 0.2f, alpha}));
            }
            for (BlockPos c : StockpileDebugState.containers()) {
                LevelRenderer.renderLineBox(pose, consumer,
                    c.getX(), c.getY(), c.getZ(), c.getX() + 1, c.getY() + 1, c.getZ() + 1,
                    0.2f, 0.5f, 1.0f, alpha);
            }
            BlockPos spFail = StockpileDebugState.failPos();
            if (spFail != null) {
                LevelRenderer.renderLineBox(pose, consumer,
                    spFail.getX(), spFail.getY(), spFail.getZ(),
                    spFail.getX() + 1, spFail.getY() + 1, spFail.getZ() + 1,
                    1.0f, 0.15f, 0.15f, 1.0f);
            }
        }

        // Tentative previews — drawn in WHITE for both rods so they read as "in progress" vs the
        // settled coloured boxes. Foreman's preview only when its rod is held; Home preview only
        // when the marker rod is held.
        if (foremanRod != null && foremanPreviewA != null && foremanPreviewB != null) {
            drawPreviewBox(pose, consumer, foremanPreviewA, foremanPreviewB, time);
        }
        if (homeRod != null && homePreviewA != null && homePreviewB != null) {
            drawPreviewBox(pose, consumer, homePreviewA, homePreviewB, time);
        }
        if (workshopRod != null && workshopPreviewA != null && workshopPreviewB != null) {
            drawPreviewBox(pose, consumer, workshopPreviewA, workshopPreviewB, time);
        }

        // End lines BEFORE the tint pass — see the comment above the home collection block. Any
        // line-consumer caller after this point would crash on the now-ended lines buffer.
        buffer.endBatch(RenderType.lines());

        if (!homeRenders.isEmpty()) {
            // Settlement-colour translucent tint, FACE-CULLED: only faces of a marked cube whose
            // neighbour is NOT marked are emitted, so the faces shared between adjacent cubes — the
            // ones that stacked and multiplied the opacity into dark bands — are gone. Each viewing
            // ray now crosses at most the shell's near + far faces, so the tint reads evenly. Quads
            // go through debugQuads (POSITION_COLOR / translucent / depth-tested) with a hair of
            // outset so they don't z-fight the block surfaces they hug.
            var quads = buffer.getBuffer(RenderType.debugQuads());
            for (var e : homeRenders) {
                float[] c = e.getValue();
                drawCulledFaces(pose, quads, e.getKey(), c[0], c[1], c[2], TINT_ALPHA);
            }
            buffer.endBatch(RenderType.debugQuads());
        }

        // ── Workshop overview labels: floating icon + name + status above each workshop while the
        //    rod is held, so the player can survey what's operating / needs workers / is broken
        //    without opening menus. Drawn last (text/item use their own render types). ───────────
        if (workshopRod != null && !workshopGroups.isEmpty()) {
            for (java.util.Map.Entry<java.util.UUID, java.util.Set<BlockPos>> e
                    : workshopGroups.entrySet()) {
                if (!e.getValue().isEmpty()) {
                    drawWorkshopLabel(pose, mc, camera, e.getKey(), e.getValue());
                }
            }
            mc.renderBuffers().bufferSource().endBatch();
        }

        pose.popPose();
    }

    /** One floating billboard label: type-icon item above, name line, status line ("Operating" /
     *  "Needs workers n/cap" / "Invalid"), centered over the workshop's marked bbox. Distance-
     *  culled at {@link #LABEL_RANGE} blocks. */
    private static void drawWorkshopLabel(PoseStack pose, Minecraft mc, Camera camera,
                                          java.util.UUID workshopId, java.util.Set<BlockPos> marked) {
        com.bannerbound.core.client.ClientWorkshopSummaries.Summary summary =
            com.bannerbound.core.client.ClientWorkshopSummaries.get(workshopId);
        if (summary == null) return;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : marked) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        double cx = (minX + maxX + 1) / 2.0;
        double cy = maxY + 1.8;
        double cz = (minZ + maxZ + 1) / 2.0;
        if (camera.getPosition().distanceToSqr(cx, cy, cz) > LABEL_RANGE * LABEL_RANGE) return;

        com.bannerbound.core.api.settlement.Workshop.Status status =
            com.bannerbound.core.api.settlement.Workshop.Status
                .fromOrdinalOrDefault(summary.statusOrdinal());
        boolean valid = status == com.bannerbound.core.api.settlement.Workshop.Status.VALID;
        boolean operating = valid && summary.workerCount() > 0;
        // Anti-cheat: a station whose craft the settlement hasn't researched (e.g. a pottery slab
        // left on the ruins of an old town) shows as "Unknown Workshop" with a "?" icon instead of
        // its real type/icon, so the overview label can't reveal/operate the craft early. A
        // player-set custom name still shows — it reveals nothing about the unresearched craft.
        boolean known = ClientResearchState.isWorkshopTypeKnown(summary.typeId());
        net.minecraft.network.chat.Component name = !summary.customName().isEmpty()
            ? net.minecraft.network.chat.Component.literal(summary.customName())
            : known
                ? net.minecraft.network.chat.Component.translatable(
                    com.bannerbound.core.api.workshop.WorkBlockRegistry.displayKey(summary.typeId()))
                : net.minecraft.network.chat.Component.translatable(
                    "bannerbound.workshop.type_unknown");
        net.minecraft.network.chat.Component statusLine = net.minecraft.network.chat.Component
            .translatable(operating ? "bannerbound.workshop.overview.operating"
                : valid ? "bannerbound.workshop.overview.needs_workers"
                : "bannerbound.workshop.overview.invalid",
                summary.workerCount(), summary.capacity())
            .withStyle(operating ? net.minecraft.ChatFormatting.GREEN
                : valid ? net.minecraft.ChatFormatting.YELLOW
                : net.minecraft.ChatFormatting.RED);
        // Workplace appeal tier (3rd line) — only once scored, and only on valid workshops so
        // the invalid label stays focused on what's broken.
        net.minecraft.network.chat.Component appealLine = null;
        if (valid && summary.appealOrdinal() >= 0) {
            com.bannerbound.core.api.settlement.ChunkBeauty beauty =
                com.bannerbound.core.api.settlement.ChunkBeauty.byNetworkId(summary.appealOrdinal());
            appealLine = net.minecraft.network.chat.Component.translatable(beauty.langKey())
                .withStyle(beauty.tierIndex() > 0 ? net.minecraft.ChatFormatting.AQUA
                    : beauty.tierIndex() < 0 ? net.minecraft.ChatFormatting.RED
                    : net.minecraft.ChatFormatting.GRAY);
        }

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        net.minecraft.client.gui.Font font = mc.font;
        int bg = (int) (0.25F * 255.0F) << 24;

        // Text: nameplate-style billboard (flip Y, 1/40 block per pixel). Two passes like vanilla
        // name tags — a dim SEE_THROUGH pass (with the background slab, visible through blocks)
        // and a bright NORMAL pass on top. One see-through pass alone renders patchy where it
        // depth-fights the silhouette wireframes.
        pose.pushPose();
        pose.translate(cx, cy, cz);
        pose.mulPose(camera.rotation());
        pose.scale(0.025F, -0.025F, 0.025F);
        var matrix = pose.last().pose();
        font.drawInBatch(name, -font.width(name) / 2.0F, -12.0F, 0x20FFFFFF, false,
            matrix, buffer, net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH,
            bg, LightTexture.FULL_BRIGHT);
        font.drawInBatch(name, -font.width(name) / 2.0F, -12.0F, 0xFFFFFFFF, false,
            matrix, buffer, net.minecraft.client.gui.Font.DisplayMode.NORMAL,
            0, LightTexture.FULL_BRIGHT);
        font.drawInBatch(statusLine, -font.width(statusLine) / 2.0F, 0.0F, 0x20FFFFFF, false,
            matrix, buffer, net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH,
            bg, LightTexture.FULL_BRIGHT);
        font.drawInBatch(statusLine, -font.width(statusLine) / 2.0F, 0.0F, 0xFFFFFFFF, false,
            matrix, buffer, net.minecraft.client.gui.Font.DisplayMode.NORMAL,
            0, LightTexture.FULL_BRIGHT);
        if (appealLine != null) {
            font.drawInBatch(appealLine, -font.width(appealLine) / 2.0F, 12.0F, 0x20FFFFFF, false,
                matrix, buffer, net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH,
                bg, LightTexture.FULL_BRIGHT);
            font.drawInBatch(appealLine, -font.width(appealLine) / 2.0F, 12.0F, 0xFFFFFFFF, false,
                matrix, buffer, net.minecraft.client.gui.Font.DisplayMode.NORMAL,
                0, LightTexture.FULL_BRIGHT);
        }
        pose.popPose();

        // Type icon: a small billboarded item floating just above the text — or a "?" glyph when
        // the craft is unresearched (the icon would otherwise reveal the hidden workshop type).
        net.minecraft.world.item.Item icon = known
            ? com.bannerbound.core.api.workshop.WorkBlockRegistry.iconForType(summary.typeId())
            : null;
        if (!known) {
            net.minecraft.network.chat.Component q = net.minecraft.network.chat.Component.literal("?");
            pose.pushPose();
            pose.translate(cx, cy + 0.6, cz);
            pose.mulPose(camera.rotation());
            pose.scale(0.05F, -0.05F, 0.05F);
            var qm = pose.last().pose();
            font.drawInBatch(q, -font.width(q) / 2.0F, -4.0F, 0xFFFFD040, false, qm, buffer,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            pose.popPose();
        }
        if (icon != null) {
            pose.pushPose();
            pose.translate(cx, cy + 0.55, cz);
            pose.mulPose(camera.rotation());
            pose.scale(0.5F, 0.5F, 0.5F);
            mc.getItemRenderer().renderStatic(
                new net.minecraft.world.item.ItemStack(icon),
                net.minecraft.world.item.ItemDisplayContext.GUI,
                LightTexture.FULL_BRIGHT,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                pose, buffer, mc.level, 0);
            pose.popPose();
        }
    }

    /** Max distance (blocks) at which workshop overview labels draw. */
    private static final double LABEL_RANGE = 64.0;

    /** Silhouette renderer — draws only the edges that lie on the BOUNDARY of the union shape.
     *
     *  <p>For each cube in {@code marked}, enumerates its 12 axis-aligned edges and dedupes
     *  via a {@code Set<EdgeKey>}. Each unique edge is classified by the configuration of the
     *  4 cubes that share it (a 2×2 perpendicular to the edge):
     *  <ul>
     *    <li>0 or 4 marked → edge is fully outside or fully inside the union; skip.</li>
     *    <li>1 marked → edge is on a single cube's silhouette; draw.</li>
     *    <li>2 marked, side-by-side (sharing a face) → edge is interior to a flat surface
     *        between the two cubes; <b>skip</b>. This is the cure for the per-block grid look.</li>
     *    <li>2 marked, diagonal → edge is a pinch point of the shape; draw.</li>
     *    <li>3 marked → edge is a concave corner; draw.</li>
     *  </ul>
     *  Result: only edges on the outer silhouette of the merged volume are drawn; no grid
     *  lines along flat faces between adjacent solids.
     */
    private static void drawSilhouette(PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer consumer,
                                        java.util.Set<BlockPos> marked,
                                        float r, float g, float b, float a) {
        java.util.Set<EdgeKey> seen = new java.util.HashSet<>();
        for (BlockPos cube : marked) {
            int cx = cube.getX(), cy = cube.getY(), cz = cube.getZ();
            // X-axis edges of this cube — 4 of them at (yi, zi) ∈ {0,1}².
            for (int yi = 0; yi <= 1; yi++) for (int zi = 0; zi <= 1; zi++) {
                tryDrawEdge(0, cx, cy + yi, cz + zi, marked, seen, pose, consumer, r, g, b, a);
            }
            // Y-axis edges.
            for (int xi = 0; xi <= 1; xi++) for (int zi = 0; zi <= 1; zi++) {
                tryDrawEdge(1, cx + xi, cy, cz + zi, marked, seen, pose, consumer, r, g, b, a);
            }
            // Z-axis edges.
            for (int xi = 0; xi <= 1; xi++) for (int yi = 0; yi <= 1; yi++) {
                tryDrawEdge(2, cx + xi, cy + yi, cz, marked, seen, pose, consumer, r, g, b, a);
            }
        }
    }

    /** Edge identity key: axis (0=X, 1=Y, 2=Z) plus the edge's anchor corner. Records auto-
     *  provide value-equality so the {@code Set<EdgeKey>} dedupes correctly across cubes that
     *  share an edge. */
    private record EdgeKey(int axis, int x, int y, int z) {}

    /** Looks up the 4 cubes around the edge, runs the silhouette classification, and emits the
     *  line segment if the edge is on the silhouette. */
    private static void tryDrawEdge(int axis, int x, int y, int z,
                                     java.util.Set<BlockPos> marked, java.util.Set<EdgeKey> seen,
                                     PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer consumer,
                                     float r, float g, float b, float a) {
        EdgeKey key = new EdgeKey(axis, x, y, z);
        if (!seen.add(key)) return;
        boolean q00, q01, q10, q11;
        float x2, y2, z2;
        switch (axis) {
            case 0 -> { // X-axis edge from (x,y,z) to (x+1,y,z); 4 cubes in YZ around it.
                q00 = marked.contains(new BlockPos(x, y - 1, z - 1));
                q01 = marked.contains(new BlockPos(x, y - 1, z));
                q10 = marked.contains(new BlockPos(x, y,     z - 1));
                q11 = marked.contains(new BlockPos(x, y,     z));
                x2 = x + 1; y2 = y; z2 = z;
            }
            case 1 -> { // Y-axis edge from (x,y,z) to (x,y+1,z); 4 cubes in XZ around it.
                q00 = marked.contains(new BlockPos(x - 1, y, z - 1));
                q01 = marked.contains(new BlockPos(x - 1, y, z));
                q10 = marked.contains(new BlockPos(x,     y, z - 1));
                q11 = marked.contains(new BlockPos(x,     y, z));
                x2 = x; y2 = y + 1; z2 = z;
            }
            case 2 -> { // Z-axis edge from (x,y,z) to (x,y,z+1); 4 cubes in XY around it.
                q00 = marked.contains(new BlockPos(x - 1, y - 1, z));
                q01 = marked.contains(new BlockPos(x - 1, y,     z));
                q10 = marked.contains(new BlockPos(x,     y - 1, z));
                q11 = marked.contains(new BlockPos(x,     y,     z));
                x2 = x; y2 = y; z2 = z + 1;
            }
            default -> { return; }
        }
        if (!isSilhouetteEdge(q00, q01, q10, q11)) return;
        drawLine(pose, consumer, x, y, z, x2, y2, z2, r, g, b, a);
    }

    /** Edge-classification rule — see {@link #drawSilhouette} javadoc for the full table. */
    private static boolean isSilhouetteEdge(boolean q00, boolean q01, boolean q10, boolean q11) {
        int count = (q00 ? 1 : 0) + (q01 ? 1 : 0) + (q10 ? 1 : 0) + (q11 ? 1 : 0);
        if (count == 0 || count == 4) return false;
        if (count == 2) {
            // The 4 cubes form a 2x2. Two marked are "side-by-side" (share a face) if both
            // share the same row OR the same column. Diagonals don't share a face.
            boolean sideBySide =
                (q00 && q01) || (q10 && q11) ||    // share the "above-below" row split
                (q00 && q10) || (q01 && q11);      // share the "left-right" column split
            return !sideBySide;
        }
        return true; // 1 or 3 marked
    }

    /** Per-cube outset for the tint faces — pushes each quad a hair outside the block surface it
     *  hugs so the translucent fill doesn't z-fight that surface. */
    private static final float TINT_FACE_OUTSET = 0.005f;

    /** Face-culled translucent fill of a marked-cube set. For each cube, emits ONLY the faces whose
     *  neighbour cube is not in {@code marked}; a face shared between two marked cubes is skipped by
     *  both, so the interior faces that used to stack and multiply the overlay into dark bands are
     *  never drawn. Quads go into a {@link RenderType#debugQuads()} consumer (POSITION_COLOR). */
    private static void drawCulledFaces(PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer quads,
                                         java.util.Set<BlockPos> marked, float r, float g, float b, float a) {
        org.joml.Matrix4f mat = pose.last().pose();
        for (BlockPos cube : marked) {
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                if (marked.contains(cube.relative(dir))) continue;  // shared interior face → cull
                emitFace(mat, quads, cube, dir, r, g, b, a);
            }
        }
    }

    /** Emits one cube face as a quad (4 verts, POSITION_COLOR), nudged {@link #TINT_FACE_OUTSET}
     *  along its outward normal. */
    private static void emitFace(org.joml.Matrix4f mat, com.mojang.blaze3d.vertex.VertexConsumer quads,
                                  BlockPos pos, net.minecraft.core.Direction dir,
                                  float r, float g, float b, float a) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        float ox = dir.getStepX() * TINT_FACE_OUTSET;
        float oy = dir.getStepY() * TINT_FACE_OUTSET;
        float oz = dir.getStepZ() * TINT_FACE_OUTSET;
        // Four corners of the face.
        float x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3;
        switch (dir) {
            case UP -> {
                x0 = x;     y0 = y + 1; z0 = z;
                x1 = x + 1; y1 = y + 1; z1 = z;
                x2 = x + 1; y2 = y + 1; z2 = z + 1;
                x3 = x;     y3 = y + 1; z3 = z + 1;
            }
            case DOWN -> {
                x0 = x;     y0 = y; z0 = z;
                x1 = x + 1; y1 = y; z1 = z;
                x2 = x + 1; y2 = y; z2 = z + 1;
                x3 = x;     y3 = y; z3 = z + 1;
            }
            case NORTH -> {
                x0 = x;     y0 = y;     z0 = z;
                x1 = x + 1; y1 = y;     z1 = z;
                x2 = x + 1; y2 = y + 1; z2 = z;
                x3 = x;     y3 = y + 1; z3 = z;
            }
            case SOUTH -> {
                x0 = x;     y0 = y;     z0 = z + 1;
                x1 = x + 1; y1 = y;     z1 = z + 1;
                x2 = x + 1; y2 = y + 1; z2 = z + 1;
                x3 = x;     y3 = y + 1; z3 = z + 1;
            }
            case WEST -> {
                x0 = x; y0 = y;     z0 = z;
                x1 = x; y1 = y + 1; z1 = z;
                x2 = x; y2 = y + 1; z2 = z + 1;
                x3 = x; y3 = y;     z3 = z + 1;
            }
            case EAST -> {
                x0 = x + 1; y0 = y;     z0 = z;
                x1 = x + 1; y1 = y + 1; z1 = z;
                x2 = x + 1; y2 = y + 1; z2 = z + 1;
                x3 = x + 1; y3 = y;     z3 = z + 1;
            }
            default -> { return; }
        }
        quads.addVertex(mat, x0 + ox, y0 + oy, z0 + oz).setColor(r, g, b, a);
        quads.addVertex(mat, x1 + ox, y1 + oy, z1 + oz).setColor(r, g, b, a);
        quads.addVertex(mat, x2 + ox, y2 + oy, z2 + oz).setColor(r, g, b, a);
        quads.addVertex(mat, x3 + ox, y3 + oy, z3 + oz).setColor(r, g, b, a);
    }

    /** Emits one line segment for {@link RenderType#lines()}. Lines render type needs a normal
     *  per vertex (vanilla uses the segment's direction); the per-vertex pose-stack normal is
     *  what {@code LevelRenderer.renderLineBox} uses too, so we mirror the pattern. */
    private static void drawLine(PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer consumer,
                                  float x1, float y1, float z1, float x2, float y2, float z2,
                                  float r, float g, float b, float a) {
        org.joml.Matrix4f mat = pose.last().pose();
        float nx = x2 - x1, ny = y2 - y1, nz = z2 - z1;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0f) { nx /= len; ny /= len; nz /= len; }
        consumer.addVertex(mat, x1, y1, z1).setColor(r, g, b, a)
            .setNormal(pose.last(), nx, ny, nz);
        consumer.addVertex(mat, x2, y2, z2).setColor(r, g, b, a)
            .setNormal(pose.last(), nx, ny, nz);
    }

    /** Draws a white preview box A→B with the faster preview flicker. */
    private static void drawPreviewBox(PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer consumer,
                                        BlockPos a, BlockPos b, double time) {
        float previewAlpha = ALPHA_BASE + ALPHA_SWING
            * (float) Math.sin(time * (Math.PI * 2.0 / 20.0) * PREVIEW_FLICKER_HZ);
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX());
        int maxY = Math.max(a.getY(), b.getY());
        int maxZ = Math.max(a.getZ(), b.getZ());
        LevelRenderer.renderLineBox(pose, consumer,
            minX, minY, minZ,
            maxX + 1, maxY + 1, maxZ + 1,
            1.0f, 1.0f, 1.0f, previewAlpha);
    }

    /** Returns the held stack of the given item (main or off), or null if neither hand holds it. */
    private static ItemStack findHeldRod(Player player, net.minecraft.world.item.Item item) {
        ItemStack main = player.getMainHandItem();
        if (main.is(item)) return main;
        ItemStack off = player.getOffhandItem();
        if (off.is(item)) return off;
        return null;
    }

    /** Resolves the "tentative B" block — whatever vanilla block the player's crosshair is on.
     *  Returns null if there's no rod-A to preview from or the crosshair isn't on a block. */
    private static BlockPos previewBHover(Minecraft mc, BlockPos previewA) {
        if (previewA == null) return null;
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult bhit)) return null;
        if (bhit.getType() != HitResult.Type.BLOCK) return null;
        return bhit.getBlockPos();
    }
}
