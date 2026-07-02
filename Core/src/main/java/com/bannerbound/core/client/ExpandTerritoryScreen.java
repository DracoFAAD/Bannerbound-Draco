package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bannerbound.core.api.settlement.ChunkBeauty;
import com.bannerbound.core.network.ExpandTerritoryClaimPayload;
import com.bannerbound.core.network.OpenExpandTerritoryScreenPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Birdseye chunk-claim picker. The world is the UI: the player's camera lifts to a fixed
 * altitude above the town hall (pitch=90°, north-up). The {@link BirdseyeOverlayRenderer}
 * paints chunks as filled translucent slabs; the player clicks chunks directly on the terrain.
 * <p>
 * Has three phases:
 * <ul>
 *   <li>ENTERING: smooth ease-in cubic from player eye Y to camera Y over ~500ms. Input
 *       suppressed until the camera arrives.</li>
 *   <li>OPEN: full interactivity — hover for tooltip, click to claim, left-mouse drag to pan
 *       (camera clamped to the bounding box of the settlement's claimed chunks so you can never
 *       lose your settlement off-screen).</li>
 *   <li>EXITING: same animation in reverse. ESC / claim sets this phase; super.onClose() is
 *       only called once the animation has finished and the camera is back at the player.</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class ExpandTerritoryScreen extends Screen {
    protected enum Phase { ENTERING, OPEN, EXITING }

    /** Search radius (chunks) around the town hall for purchasable adjacency. */
    private static final int RADIUS = 4;
    /** Slab is this far above the player's feet. */
    private static final int SLAB_OFFSET_ABOVE_PLAYER = 20;
    /** Camera is this far above the player. Below cloud height (Y=192) for typical terrain. */
    private static final int CAMERA_OFFSET_ABOVE_PLAYER = 100;
    /** Animation duration in ms for both ENTERING and EXITING. */
    private static final long ANIM_DURATION_MS = 500L;

    /** Mutable so {@link #refreshData} can swap in an updated payload mid-session (server
     *  pushes one after every successful claim). */
    private OpenExpandTerritoryScreenPayload data;
    private final Set<Long> claimed = new HashSet<>();
    private final Set<Long> foreign = new HashSet<>();
    private final Set<Long> purchasable = new HashSet<>();
    private final TransientClickFeedback feedback = new TransientClickFeedback();
    /** Per-chunk beauty tag for the view window, from the payload — drives the hover tooltip. */
    private final Map<Long, ChunkBeauty> beautyByChunk = new HashMap<>();
    /** Per-chunk adjacency bonus (sum of neighbours' base tier indices), from the payload. */
    private final Map<Long, Integer> adjacencyByChunk = new HashMap<>();
    /** Per-chunk effective beauty (base score + adjacency, re-thresholded), from the payload. */
    private final Map<Long, ChunkBeauty> effectiveByChunk = new HashMap<>();
    private final ChunkPos centerChunk;
    private Entity ghostCamera;
    private Entity originalCamera;
    protected int slabY;
    protected int cameraY;
    /** Camera position in world XZ — starts at town hall center, mutated by drag. */
    protected double dragCamX, dragCamZ;
    /** Starting Y for the animation (= player eye Y when the screen opened). */
    private double playerEyeY;

    private boolean prevHideGui;
    private int prevMenuBlur;
    private CloudStatus prevClouds;

    protected Phase phase = Phase.ENTERING;
    private long phaseStartMs;

    /** Screen to return to when the EXITING animation completes (the Town Hall when opened from
     *  its Expand Territory button); {@code null} closes to the world as before. Set by
     *  {@code ClientPayloadHandler.handleOpenExpandTerritoryScreen}; survives in-place
     *  {@link #refreshData} refreshes because those keep this instance. */
    @org.jetbrains.annotations.Nullable
    private Screen parent;

    public void setParent(@org.jetbrains.annotations.Nullable Screen parent) {
        this.parent = parent;
    }

    /** Camera yaw target in {0, 90, 180, 270}. Snaps to one of those values when settled.
     *  Animated independently of the in/out phase animation. */
    private int yawTarget = 0;
    /** Yaw at the start of the current rotation animation (decimal so animation can lerp). */
    private float yawAnimStart = 0.0f;
    /** Start time of the current rotation animation, or -1 if not animating. */
    protected long yawAnimStartMs = -1L;
    /** Duration of a single 90° rotation in ms. */
    private static final long YAW_ANIM_MS = 300L;

    public ExpandTerritoryScreen(OpenExpandTerritoryScreenPayload data) {
        super(Component.translatable("bannerbound.territory.title"));
        this.data = data;
        this.claimed.addAll(data.claimedChunks());
        this.foreign.addAll(data.foreignChunks());
        loadBeauty(data);
        this.centerChunk = new ChunkPos(data.townHallChunkPacked());
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                long packed = new ChunkPos(centerChunk.x + dx, centerChunk.z + dz).toLong();
                if (claimed.contains(packed) || foreign.contains(packed)) continue;
                if (isAdjacentToOwned(centerChunk.x + dx, centerChunk.z + dz)) {
                    purchasable.add(packed);
                }
            }
        }
    }

    private boolean isAdjacentToOwned(int cx, int cz) {
        return claimed.contains(new ChunkPos(cx - 1, cz).toLong())
            || claimed.contains(new ChunkPos(cx + 1, cz).toLong())
            || claimed.contains(new ChunkPos(cx, cz - 1).toLong())
            || claimed.contains(new ChunkPos(cx, cz + 1).toLong());
    }

    @Override
    protected void init() {
        super.init();
        Minecraft mc = this.minecraft;
        if (mc == null || mc.level == null || mc.player == null || mc.options == null) return;

        int playerY = (int) Math.floor(mc.player.getY());
        this.playerEyeY = mc.player.getEyeY();
        this.slabY = playerY + SLAB_OFFSET_ABOVE_PLAYER;
        this.cameraY = playerY + CAMERA_OFFSET_ABOVE_PLAYER;
        this.dragCamX = centerChunk.getMinBlockX() + 8.5;
        this.dragCamZ = centerChunk.getMinBlockZ() + 8.5;

        prevHideGui = mc.options.hideGui;
        prevMenuBlur = mc.options.menuBackgroundBlurriness().get();
        prevClouds = mc.options.cloudStatus().get();
        mc.options.hideGui = true;
        mc.options.menuBackgroundBlurriness().set(0);
        mc.options.cloudStatus().set(CloudStatus.OFF);

        // Marker entity — eye height 0 means Camera.eyeHeight interpolation can't oscillate.
        Marker marker = new Marker(EntityType.MARKER, mc.level);
        // yaw=0 → camera looks at -Z; combined with pitch=90 the "up" axis on screen becomes
        // -Z (north). Standard map orientation: north at the top, east to the right.
        marker.moveTo(dragCamX, playerEyeY, dragCamZ, 0.0F, 90.0F);
        marker.setNoGravity(true);
        this.ghostCamera = marker;
        this.originalCamera = mc.getCameraEntity();
        mc.setCameraEntity(marker);

        this.phase = Phase.ENTERING;
        this.phaseStartMs = System.currentTimeMillis();

        ClientBirdseyeState.enter(marker, originalCamera, claimed, foreign, purchasable,
            data.colorOrdinal(), data.townHallChunkPacked(), data.canAfford(),
            slabY, cameraY);
    }

    /** Current camera yaw, lerped between yawAnimStart and yawTarget while rotation animation
     *  is running. Snaps to yawTarget once {@link #YAW_ANIM_MS} elapses. */
    private float currentYaw() {
        if (yawAnimStartMs < 0) return (float) yawTarget;
        long elapsed = System.currentTimeMillis() - yawAnimStartMs;
        if (elapsed >= YAW_ANIM_MS) {
            yawAnimStartMs = -1L;
            yawAnimStart = (float) yawTarget;
            return (float) yawTarget;
        }
        double t = (double) elapsed / (double) YAW_ANIM_MS;
        double eased = easeInOut(t);
        return (float) (yawAnimStart + (yawTarget - yawAnimStart) * eased);
    }

    /** Kick off a 90° rotation of the camera. {@code delta} is +90 (right) or -90 (left).
     *  Stores yawTarget WITHOUT wrapping to [0, 360) — wrapping there made the lerp take the
     *  long way around (e.g., 270 + 90 → 0 produced a 270° spin instead of 90°). The snapped
     *  quadrant for picker math is computed via {@link #snappedYawQuadrant()}. */
    private void startRotation(int delta) {
        // Don't queue a second rotation mid-animation — let the current one finish first to
        // avoid the camera spinning unbounded.
        if (yawAnimStartMs >= 0) return;
        yawAnimStart = currentYaw();
        yawTarget += delta;
        yawAnimStartMs = System.currentTimeMillis();
    }

    /** Snaps the (possibly unbounded) yawTarget into the {0, 90, 180, 270} quadrant used by
     *  the picker / drag mapping. */
    protected int snappedYawQuadrant() {
        return ((yawTarget % 360) + 360) % 360;
    }

    /** Cubic ease-in-out from 0..1. Smooth start, smooth end. */
    private static double easeInOut(double t) {
        if (t < 0.0) return 0.0;
        if (t > 1.0) return 1.0;
        return t < 0.5 ? 4.0 * t * t * t : 1.0 - Math.pow(-2.0 * t + 2.0, 3.0) / 2.0;
    }

    /** Animation progress in [0, 1] for the current phase. OPEN always returns 1 (settled). */
    private double phaseProgress() {
        if (phase == Phase.OPEN) return 1.0;
        long elapsed = System.currentTimeMillis() - phaseStartMs;
        return Math.min(1.0, (double) elapsed / (double) ANIM_DURATION_MS);
    }

    @Override
    public void tick() {
        super.tick();
        // Phase transitions only — actual position update happens per-frame in render() so the
        // animation interpolates at frame rate (smooth) instead of tick rate (choppy at 20 fps).
        double progress = phaseProgress();
        if (phase == Phase.ENTERING && progress >= 1.0) {
            phase = Phase.OPEN;
        } else if (phase == Phase.EXITING && progress >= 1.0) {
            superClose();
        }
    }

    /** Computes the eased camera Y based on phase + elapsed time, reading the player's CURRENT
     *  eye Y (not a stale capture) so falling / moving / etc. doesn't cause the camera to clip
     *  through the ground at the end of the exit animation. */
    private double currentAnimatedCameraY() {
        Minecraft mc = this.minecraft;
        double liveEyeY = (mc != null && mc.player != null) ? mc.player.getEyeY() : playerEyeY;
        double progress = phaseProgress();
        double eased = easeInOut(progress);
        if (phase == Phase.ENTERING) return liveEyeY + (cameraY - liveEyeY) * eased;
        if (phase == Phase.EXITING) return cameraY + (liveEyeY - cameraY) * eased;
        return cameraY;
    }

    /** Per-frame camera position update. Forces xo/yo/zo + yRotO/xRotO to current values so
     *  Camera.setup's Mth.lerp(partialTicks, old, new) is identity — i.e. exactly the position
     *  we wrote this frame, no MC interp adding wobble on top of our own animation. */
    private void syncCameraPosition() {
        if (ghostCamera == null) return;
        double y = currentAnimatedCameraY();
        float yaw = currentYaw();
        ghostCamera.setPos(dragCamX, y, dragCamZ);
        ghostCamera.setYRot(yaw);
        ghostCamera.setXRot(90.0F);
        ghostCamera.xo = dragCamX;
        ghostCamera.yo = y;
        ghostCamera.zo = dragCamZ;
        ghostCamera.xOld = dragCamX;
        ghostCamera.yOld = y;
        ghostCamera.zOld = dragCamZ;
        ghostCamera.yRotO = yaw;
        ghostCamera.xRotO = 90.0F;
    }

    /** Calls the real Screen.onClose-equivalent: closes the screen. Bypasses our deferred path
     *  so EXITING can actually terminate the screen once the animation completes. QoL: returns
     *  to the Town Hall it was opened from when a parent is set (null = world, as before). */
    private void superClose() {
        Minecraft mc = this.minecraft;
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void removed() {
        Minecraft mc = this.minecraft;
        if (mc != null) {
            mc.setCameraEntity(mc.player);
            if (mc.options != null) {
                mc.options.hideGui = prevHideGui;
                mc.options.menuBackgroundBlurriness().set(prevMenuBlur);
                if (prevClouds != null) mc.options.cloudStatus().set(prevClouds);
            }
        }
        ClientBirdseyeState.exit();
        super.removed();
    }

    @Override
    public void onClose() {
        // Don't slam the screen shut — start the EXITING animation. tick() will call
        // setScreen(null) once the animation completes.
        if (phase == Phase.EXITING) return; // already exiting; ignore duplicate ESC
        phase = Phase.EXITING;
        // If we were mid-ENTERING, reverse from the position we'd reached so the exit feels
        // continuous instead of snapping back to cameraY.
        phaseStartMs = System.currentTimeMillis();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Per-frame: re-anchor camera at the eased Y for this exact frame. Doing it here (not
        // in tick) makes the animation visually smooth at frame rate, not capped at 20 ticks.
        syncCameraPosition();

        Long hovered = null;       // purchasable chunk under cursor — drives the claim highlight
        Long tooltipChunk = null;  // purchasable OR claimed chunk under cursor — drives the tooltip
        if (phase == Phase.OPEN) {
            Long under = pickChunk(mouseX, mouseY);
            if (under != null && purchasable.contains(under)) {
                hovered = under;
                tooltipChunk = under;
            } else if (under != null && claimed.contains(under)) {
                tooltipChunk = under;
            }
        }
        ClientBirdseyeState.setHoveredChunk(hovered);

        // Hide UI text during the in/out animation so the player just sees the world transition.
        if (phase == Phase.OPEN) {
            // Subclasses with their own header/footer (the wall preview) suppress the base
            // HUD text — both drew over each other (playtest 2026-06-12).
            if (drawsBaseHud()) {
                g.drawCenteredString(this.font,
                    Component.translatable("bannerbound.territory.title")
                        .copy().withStyle(ChatFormatting.WHITE),
                    this.width / 2, 10, 0xFFFFFFFF);
                g.drawCenteredString(this.font,
                    Component.translatable("bannerbound.territory.expansions",
                        data.expansionsInEra(), data.maxExpansions()),
                    this.width / 2, 22, 0xFFAAAAAA);
                if (data.expansionsInEra() >= data.maxExpansions()) {
                    g.drawCenteredString(this.font,
                        Component.translatable("bannerbound.territory.cap_reached")
                            .withStyle(ChatFormatting.YELLOW),
                        this.width / 2, 34, 0xFFFFAA00);
                }
            }
            if (tooltipChunk != null) drawTooltip(g, mouseX, mouseY, tooltipChunk);
            // Chunk markers — Council votes (yellow border, "(N/X)" text + voter faces) and
            // Chiefdom suggestions (green border, just the suggester faces). Drawn AFTER
            // the tooltip so the floating tooltip can overlap them without flickering.
            drawChunkMarkers(g);
            if (drawsBaseHud()) {
                g.drawCenteredString(this.font,
                    Component.translatable("bannerbound.territory.esc_hint")
                        .withStyle(ChatFormatting.DARK_GRAY),
                    this.width / 2, this.height - 14, 0xFF666677);
            }
        }
        super.render(g, mouseX, mouseY, partialTick);
        feedback.render(g);
    }

    /** Render every active vote / suggestion marker on top of its chunk. Votes get the
     *  "(N/X)" badge in yellow; suggestions get a "+N" badge in green. Both rows render up
     *  to {@link #MAX_FACES} player skin heads from the local PlayerInfo cache. */
    private void drawChunkMarkers(GuiGraphics g) {
        // Votes (Council). Skip entirely outside Council (server sends empty list).
        for (OpenExpandTerritoryScreenPayload.ChunkMarker m : data.votes()) {
            int[] xy = chunkToScreen(m.packedChunkPos());
            if (xy == null) continue;
            drawMarkerBadge(g, xy[0], xy[1], m.playerIds(),
                "(" + m.playerIds().size() + "/" + data.councilVoteThreshold() + ")",
                0xFFE0D055, 0xFFFFE066);
        }
        // Suggestions (Chiefdom).
        for (OpenExpandTerritoryScreenPayload.ChunkMarker m : data.suggestions()) {
            int[] xy = chunkToScreen(m.packedChunkPos());
            if (xy == null) continue;
            drawMarkerBadge(g, xy[0], xy[1], m.playerIds(),
                "+" + m.playerIds().size(),
                0xFF55E055, 0xFF66FF66);
        }
    }

    private static final int MAX_FACES = 3;

    /** Shared badge draw — text + up to N skin heads on a dark plate. {@code outlineColor}
     *  rims the plate; {@code textColor} colours the count label. */
    private void drawMarkerBadge(GuiGraphics g, int cx, int cy, java.util.List<java.util.UUID> players,
                                  String text, int outlineColor, int textColor) {
        int textW = this.font.width(text);
        int headSize = 8;
        int padding = 2;
        int shown = Math.min(MAX_FACES, players.size());
        int boxW = textW + 4 + shown * (headSize + padding) + 4;
        int boxH = Math.max(headSize, this.font.lineHeight) + 4;
        int boxX = cx - boxW / 2;
        int boxY = cy - boxH / 2;
        g.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xE0101010);
        g.renderOutline(boxX, boxY, boxW, boxH, outlineColor);
        int cursorX = boxX + 3;
        int textY = boxY + (boxH - this.font.lineHeight) / 2 + 1;
        g.drawString(this.font, text, cursorX, textY, textColor, false);
        cursorX += textW + 3;
        int faceY = boxY + (boxH - headSize) / 2;
        java.util.UUID localId = this.minecraft != null && this.minecraft.player != null
            ? this.minecraft.player.getUUID() : null;
        for (int i = 0; i < shown; i++) {
            java.util.UUID id = players.get(i);
            net.minecraft.resources.ResourceLocation skin = resolveSkin(id);
            if (skin != null) {
                net.minecraft.client.gui.components.PlayerFaceRenderer.draw(
                    g, new net.minecraft.client.resources.PlayerSkin(
                        skin, null, null, null,
                        net.minecraft.client.resources.PlayerSkin.Model.WIDE, true),
                    cursorX, faceY, headSize);
            } else {
                g.fill(cursorX, faceY, cursorX + headSize, faceY + headSize, outlineColor);
            }
            if (id.equals(localId)) {
                g.renderOutline(cursorX - 1, faceY - 1, headSize + 2, headSize + 2, 0xFFFFFFFF);
            }
            cursorX += headSize + padding;
        }
    }

    private net.minecraft.resources.ResourceLocation resolveSkin(java.util.UUID id) {
        if (this.minecraft == null || this.minecraft.getConnection() == null) return null;
        net.minecraft.client.multiplayer.PlayerInfo info =
            this.minecraft.getConnection().getPlayerInfo(id);
        return info == null ? null : info.getSkin().texture();
    }

    /** Loads the per-chunk beauty tags + adjacency bonuses + effective beauty from a payload. */
    private void loadBeauty(OpenExpandTerritoryScreenPayload payload) {
        beautyByChunk.clear();
        adjacencyByChunk.clear();
        effectiveByChunk.clear();
        List<Long> chunks = payload.beautyChunks();
        List<Integer> tags = payload.beautyTagIds();
        List<Integer> adjacency = payload.beautyAdjacency();
        List<Integer> effective = payload.beautyEffective();
        for (int i = 0; i < chunks.size() && i < tags.size(); i++) {
            beautyByChunk.put(chunks.get(i), ChunkBeauty.byNetworkId(tags.get(i)));
            if (i < adjacency.size()) {
                adjacencyByChunk.put(chunks.get(i), adjacency.get(i));
            }
            if (i < effective.size()) {
                effectiveByChunk.put(chunks.get(i), ChunkBeauty.byNetworkId(effective.get(i)));
            }
        }
    }

    private void drawTooltip(GuiGraphics g, int mouseX, int mouseY, long hoveredPacked) {
        List<Component> lines = new ArrayList<>();
        if (data.biome() != null && !data.biome().isEmpty()) {
            lines.add(Component.translatable("bannerbound.territory.biome", data.biome())
                .withStyle(ChatFormatting.GRAY));
        }
        ChunkBeauty beauty = beautyByChunk.get(hoveredPacked);
        if (beauty != null) {
            // Base beauty.
            lines.add(Component.translatable("bannerbound.territory.beauty",
                    Component.translatable(beauty.langKey()))
                .withStyle(ChatFormatting.LIGHT_PURPLE));

            // Adjacency bonus, with the resulting effective beauty in brackets.
            int adj = adjacencyByChunk.getOrDefault(hoveredPacked, 0);
            ChunkBeauty effective = effectiveByChunk.getOrDefault(hoveredPacked, beauty);
            String adjText = adj > 0 ? "+" + adj : String.valueOf(adj);
            lines.add(Component.translatable("bannerbound.territory.adjacency",
                    adjText, Component.translatable(effective.langKey()))
                .withStyle(ChatFormatting.LIGHT_PURPLE));

            // The culture/s this chunk generates — driven by the effective beauty.
            double cps = effective.culturePerSecond();
            ChatFormatting cultureColor = cps > 0 ? ChatFormatting.GREEN
                : cps < 0 ? ChatFormatting.RED : ChatFormatting.GRAY;
            lines.add(Component.translatable("bannerbound.territory.culture_line",
                    String.format("%+.2f", cps), Icons.culture())
                .withStyle(cultureColor));
        }

        // Already-claimed chunk: show ownership + its beauty, but not the claim-cost ladder.
        if (claimed.contains(hoveredPacked)) {
            lines.add(Component.translatable("bannerbound.territory.owned")
                .withStyle(ChatFormatting.GREEN));
            g.renderComponentTooltip(this.font, lines, mouseX, mouseY);
            return;
        }

        boolean popOk = data.currentPopulation() >= data.currentTierPopulation();
        lines.add(Component.translatable("bannerbound.territory.population_line",
            data.currentPopulation(), data.currentTierPopulation())
            .withStyle(popOk ? ChatFormatting.GREEN : ChatFormatting.RED));
        lines.add(Component.translatable("bannerbound.territory.items_header")
            .withStyle(ChatFormatting.GRAY));
        for (int i = 0; i < data.currentTierItemIds().size(); i++) {
            String id = data.currentTierItemIds().get(i);
            int need = data.currentTierItemCounts().get(i);
            int have = countInInventory(id);
            ChatFormatting col = have >= need ? ChatFormatting.GREEN : ChatFormatting.RED;
            lines.add(Component.literal("  " + have + " / " + need + "  " + idShortName(id))
                .withStyle(col));
        }
        lines.add(Component.literal(""));
        lines.add(data.canAfford()
            ? Component.translatable("bannerbound.territory.click_to_claim")
                .withStyle(ChatFormatting.AQUA)
            : Component.translatable("bannerbound.territory.cannot_afford")
                .withStyle(ChatFormatting.RED));

        g.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    /**
     * Inverse of the camera projection: given a mouse pixel, return the long-packed ChunkPos of
     * the world chunk under it. Signs are NEGATIVE because empirically the MC projection at
     * (yaw=0, pitch=90) renders the world inverted along both axes from the naive analysis —
     * mouse-right corresponds to world -X (west) on screen, mouse-down to world -Z (north).
     */
    protected Long pickChunk(int mouseX, int mouseY) {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.options == null || mc.getWindow() == null) return null;
        // Picker only works at the SNAPPED yaw (one of 0/90/180/270). When yawAnimStartMs >= 0
        // we're mid-rotation; just suppress picking so the player can't accidentally claim a
        // chunk that's spinning past the cursor.
        if (yawAnimStartMs >= 0) return null;

        double fovDeg = mc.options.fov().get();
        double fovRad = Math.toRadians(fovDeg);
        double distToSlab = cameraY - slabY;
        double visibleH = 2.0 * distToSlab * Math.tan(fovRad / 2.0);
        double aspect = (double) mc.getWindow().getGuiScaledWidth()
                      / (double) mc.getWindow().getGuiScaledHeight();
        double visibleW = visibleH * aspect;

        double nx = (double) mouseX / (double) this.width - 0.5;
        double ny = (double) mouseY / (double) this.height - 0.5;
        double[] offset = screenToWorldOffset(nx * visibleW, ny * visibleH);
        double worldX = dragCamX + offset[0];
        double worldZ = dragCamZ + offset[1];
        int cx = (int) Math.floor(worldX / 16.0);
        int cz = (int) Math.floor(worldZ / 16.0);
        return new ChunkPos(cx, cz).toLong();
    }

    /** Inverse of {@link #pickChunk} — given a chunk pos, return its on-screen pixel center
     *  (or {@code null} if the chunk is mid-rotation / off-screen). Used to anchor vote +
     *  suggestion markers on top of each chunk in the 3D birdseye scene. */
    private int[] chunkToScreen(long packedChunk) {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.options == null || mc.getWindow() == null) return null;
        if (yawAnimStartMs >= 0) return null;
        net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(packedChunk);
        double worldX = cp.x * 16.0 + 8.0;
        double worldZ = cp.z * 16.0 + 8.0;
        // Reverse screenToWorldOffset for the current yaw quadrant. World offset from camera:
        double dWX = worldX - dragCamX;
        double dWZ = worldZ - dragCamZ;
        double screenRight;
        double screenDown;
        // Inverse of screenToWorldOffset — 90/270 swapped to match its corrected rotation sense.
        switch (snappedYawQuadrant()) {
            case 0   -> { screenRight = -dWX; screenDown = -dWZ; }
            case 90  -> { screenRight = -dWZ; screenDown =  dWX; }
            case 180 -> { screenRight =  dWX; screenDown =  dWZ; }
            case 270 -> { screenRight =  dWZ; screenDown = -dWX; }
            default  -> { screenRight = -dWX; screenDown = -dWZ; }
        }
        // Camera visible-area dimensions in world units (mirror of pickChunk).
        double fovDeg = mc.options.fov().get();
        double fovRad = Math.toRadians(fovDeg);
        double distToSlab = cameraY - slabY;
        double visibleH = 2.0 * distToSlab * Math.tan(fovRad / 2.0);
        double aspect = (double) mc.getWindow().getGuiScaledWidth()
                      / (double) mc.getWindow().getGuiScaledHeight();
        double visibleW = visibleH * aspect;
        double nx = screenRight / visibleW;
        double ny = screenDown / visibleH;
        int px = (int) Math.round((nx + 0.5) * this.width);
        int py = (int) Math.round((ny + 0.5) * this.height);
        return new int[]{ px, py };
    }

    /**
     * Maps a (screen-right, screen-down) vector at the current snapped yaw to a world (dX, dZ)
     * offset. Empirically derived: at yaw=0 the mapping is (-1, -1) on both axes (mouse-right
     * → world -X, mouse-down → world -Z). Each 90° yaw step rotates that mapping. The camera
     * yaws clockwise-from-above as yaw increases, so the screen→world mapping rotates the
     * OPPOSITE way the 90/270 cases originally assumed — those two are the mirror of the naive
     * "rotate CW" guess. (The 0 and 180 cases are direction-invariant, which is why only
     * 90/270 ever looked wrong in-game while dragging / picking after a rotation.)
     */
    protected double[] screenToWorldOffset(double screenRightMag, double screenDownMag) {
        switch (snappedYawQuadrant()) {
            case 0:   return new double[]{ -screenRightMag, -screenDownMag };
            case 90:  return new double[]{ +screenDownMag,  -screenRightMag };
            case 180: return new double[]{ +screenRightMag, +screenDownMag };
            case 270: return new double[]{ -screenDownMag,  +screenRightMag };
            default:  return new double[]{ -screenRightMag, -screenDownMag };
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // GLFW key codes: A = 65, D = 68. User-stated mapping: A rotates the view 90° right,
        // D rotates it 90° left.
        if (phase == Phase.OPEN) {
            if (keyCode == 65) { startRotation(+90); return true; }
            if (keyCode == 68) { startRotation(-90); return true; }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** A click only needs the materials in inventory when it's a DIRECT claim (Chiefdom chief
     *  or anarchy). Council vote and Chiefdom non-chief suggestion go through the same
     *  packet but the server treats them as votes/suggestions — they shouldn't be gated on
     *  the clicker's resources. Resources are re-checked server-side when the vote actually
     *  passes (Council) or the chief eventually claims (Chiefdom). */
    private boolean clickRequiresResources() {
        int govOrd = com.bannerbound.core.client.ClientPopulationState.getGovernmentOrdinal();
        if (govOrd == com.bannerbound.core.api.settlement.Settlement.Government.COUNCIL.ordinal()) {
            return false;
        }
        if (govOrd == com.bannerbound.core.api.settlement.Settlement.Government.CHIEFDOM.ordinal()) {
            // Non-chief in a Chiefdom is suggesting, not claiming — no resource gate.
            return com.bannerbound.core.client.ClientPopulationState.isPlayerChief();
        }
        // NONE / anarchy / unknown — direct claim, gate as before.
        return true;
    }

    /** Subclass hook: the wall preview reuses this screen's camera but must not claim chunks. */
    protected boolean allowChunkClaiming() {
        return true;
    }

    /** Subclass hook: screens with their own header/footer (the wall preview) return false
     *  so the base title/expansions/esc-hint text doesn't draw over theirs. */
    protected boolean drawsBaseHud() {
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (phase != Phase.OPEN) return false;
        if (button == 0 && allowChunkClaiming()) {
            Long packed = pickChunk((int) mouseX, (int) mouseY);
            if (packed != null
                    && purchasable.contains(packed)
                    && data.expansionsInEra() < data.maxExpansions()
                    && (clickRequiresResources() ? data.canAfford() : true)) {
                PacketDistributor.sendToServer(new ExpandTerritoryClaimPayload(packed));
                // Stay on the screen — server will push a refreshed
                // OpenExpandTerritoryScreenPayload back which {@link #refreshData} consumes in
                // place. The player can keep clicking new chunks until they run out of
                // resources / expansions, or close manually with ESC.
                feedback.spawn((int) mouseX, (int) mouseY);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Reloads the screen's local state from a fresh server payload. Called when the server
     * pushes an updated {@link OpenExpandTerritoryScreenPayload} mid-session (e.g., after a
     * successful claim). Camera position, phase, and animation state are PRESERVED so the
     * player's view doesn't snap back to the town hall after every claim.
     */
    public void refreshData(OpenExpandTerritoryScreenPayload newData) {
        this.data = newData;
        this.claimed.clear();
        this.claimed.addAll(newData.claimedChunks());
        this.foreign.clear();
        this.foreign.addAll(newData.foreignChunks());
        loadBeauty(newData);
        this.purchasable.clear();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                long packed = new ChunkPos(centerChunk.x + dx, centerChunk.z + dz).toLong();
                if (claimed.contains(packed) || foreign.contains(packed)) continue;
                if (isAdjacentToOwned(centerChunk.x + dx, centerChunk.z + dz)) {
                    purchasable.add(packed);
                }
            }
        }
        // Push the new sets into the in-world overlay state so the renderer picks them up
        // without waiting for the next ClientBirdseyeState.enter call.
        ClientBirdseyeState.enter(ghostCamera, originalCamera, claimed, foreign, purchasable,
            newData.colorOrdinal(), newData.townHallChunkPacked(), newData.canAfford(),
            slabY, cameraY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragDx, double dragDy) {
        if (phase != Phase.OPEN || button != 0 || yawAnimStartMs >= 0) {
            return super.mouseDragged(mouseX, mouseY, button, dragDx, dragDy);
        }
        Minecraft mc = this.minecraft;
        if (mc == null || mc.options == null || mc.getWindow() == null) return false;
        double fovDeg = mc.options.fov().get();
        double fovRad = Math.toRadians(fovDeg);
        double distToSlab = cameraY - slabY;
        double visibleH = 2.0 * distToSlab * Math.tan(fovRad / 2.0);
        double aspect = (double) mc.getWindow().getGuiScaledWidth()
                      / (double) mc.getWindow().getGuiScaledHeight();
        double visibleW = visibleH * aspect;
        // Drag direction is the INVERSE of the picker mapping — moving the mouse right
        // scrolls the world right (the camera follows the inverse vector). Same yaw-rotation
        // applies, so we reuse screenToWorldOffset on the drag delta and then negate.
        double screenRightMag = dragDx * (visibleW / this.width);
        double screenDownMag = dragDy * (visibleH / this.height);
        double[] offset = screenToWorldOffset(screenRightMag, screenDownMag);
        applyPanWithChunkClamp(-offset[0], -offset[1]);
        return true;
    }

    /**
     * Per-chunk clamp: camera position can only land inside a CLAIMED chunk. If the requested
     * (dx, dz) move would land on an unclaimed chunk, try X-only and Z-only separately so the
     * player can still slide along an edge between two disjoint claims. Without this the camera
     * can drift across unclaimed cells inside the bounding box of claims.
     */
    private void applyPanWithChunkClamp(double dx, double dz) {
        double tryX = dragCamX + dx;
        double tryZ = dragCamZ + dz;
        if (isClaimedChunkAt(tryX, tryZ)) {
            dragCamX = tryX;
            dragCamZ = tryZ;
            return;
        }
        // Try X-only.
        if (isClaimedChunkAt(tryX, dragCamZ)) {
            dragCamX = tryX;
        }
        // Try Z-only.
        if (isClaimedChunkAt(dragCamX, tryZ)) {
            dragCamZ = tryZ;
        }
    }

    private boolean isClaimedChunkAt(double worldX, double worldZ) {
        long packed = new ChunkPos((int) Math.floor(worldX / 16.0),
                                    (int) Math.floor(worldZ / 16.0)).toLong();
        return claimed.contains(packed);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private int countInInventory(String idStr) {
        if (this.minecraft == null || this.minecraft.player == null) return 0;
        Item item = lookupItem(idStr);
        if (item == Items.AIR) return 0;
        int total = 0;
        for (ItemStack s : this.minecraft.player.getInventory().items) {
            if (s.is(item)) total += s.getCount();
        }
        for (ItemStack s : this.minecraft.player.getInventory().offhand) {
            if (s.is(item)) total += s.getCount();
        }
        return total;
    }

    private static Item lookupItem(String idStr) {
        ResourceLocation rl = ResourceLocation.tryParse(idStr);
        if (rl == null) return Items.AIR;
        return BuiltInRegistries.ITEM.get(rl);
    }

    private static String idShortName(String idStr) {
        int colon = idStr.indexOf(':');
        return colon >= 0 ? idStr.substring(colon + 1) : idStr;
    }
}
