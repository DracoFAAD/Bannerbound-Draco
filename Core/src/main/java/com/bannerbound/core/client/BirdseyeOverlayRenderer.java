package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Renders the in-world chunk-claim overlay while {@link ClientBirdseyeState} is active. Each
 * chunk in the visible window gets a solid translucent slab plus a thin outline so the player
 * can pick chunks directly off the terrain — no GUI panel needed. Roles:
 * <ul>
 *   <li>Own chunks → settlement color (subtler alpha, no hover).</li>
 *   <li>Foreign chunks → dim grey.</li>
 *   <li>Purchasable unclaimed → bright white-blue. Brightens to near-white when the cursor's
 *       world-projected position falls inside this chunk. Faded when unaffordable.</li>
 * </ul>
 * The slab Y is fixed at {@link #SLAB_Y} — high enough that camera is always above, low enough
 * that most overworld terrain is below. Tall mountains can occlude (acceptable).
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class BirdseyeOverlayRenderer {
    /** Slab thickness — thin enough not to obscure terrain features, thick enough to draw
     *  cleanly without z-fighting. */
    private static final float SLAB_HEIGHT = 0.5f;
    // Alphas kept on the lower side so terrain reads clearly through the overlay.
    private static final float FILL_ALPHA_OWN = 0.28f;
    private static final float FILL_ALPHA_FOREIGN = 0.22f;
    private static final float FILL_ALPHA_PURCH = 0.32f;
    private static final float FILL_ALPHA_PURCH_HOVER = 0.55f;
    private static final float FILL_ALPHA_PURCH_DISABLED = 0.18f;

    private BirdseyeOverlayRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!ClientBirdseyeState.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // Identity, not founding slot: a re-dyed banner recolors the territory overlay too.
        // Own chunks fill with the primary and OUTLINE with the secondary accent — the two-tone
        // faction identity, read straight off the terrain from above.
        int ord = ClientBirdseyeState.colorOrdinal();
        int rgb = ClientIdentityState.primaryRgb(ord);
        float ownR = ((rgb >> 16) & 0xFF) / 255f;
        float ownG = ((rgb >> 8) & 0xFF) / 255f;
        float ownB = (rgb & 0xFF) / 255f;
        int accent = ClientIdentityState.secondaryRgb(ord);
        float accR = ((accent >> 16) & 0xFF) / 255f;
        float accG = ((accent >> 8) & 0xFF) / 255f;
        float accB = (accent & 0xFF) / 255f;

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        Long hovered = ClientBirdseyeState.hoveredChunk();
        boolean affordable = ClientBirdseyeState.canAfford();

        for (long packed : ClientBirdseyeState.own()) {
            drawChunk(pose, buffer, packed, ownR, ownG, ownB, FILL_ALPHA_OWN,
                accR, accG, accB);
        }
        for (long packed : ClientBirdseyeState.foreign()) {
            drawChunk(pose, buffer, packed, 0.35f, 0.35f, 0.4f, FILL_ALPHA_FOREIGN);
        }
        for (long packed : ClientBirdseyeState.purchasable()) {
            float r, g, b, a;
            if (!affordable) {
                r = 0.35f; g = 0.4f; b = 0.5f;  a = FILL_ALPHA_PURCH_DISABLED;
            } else if (hovered != null && hovered == packed) {
                r = 0.85f; g = 0.95f; b = 1.0f; a = FILL_ALPHA_PURCH_HOVER;
            } else {
                r = 0.45f; g = 0.72f; b = 1.0f; a = FILL_ALPHA_PURCH;
            }
            drawChunk(pose, buffer, packed, r, g, b, a);
        }

        buffer.endBatch(RenderType.debugFilledBox());
        buffer.endBatch(RenderType.lines());
        pose.popPose();
    }

    /** Renders one chunk as a filled translucent slab + a brighter line outline on its top
     *  face. Slab Y is read from {@link ClientBirdseyeState} so it tracks whatever the screen
     *  computed for this session (player-relative). */
    private static void drawChunk(PoseStack pose, MultiBufferSource buffer, long packed,
                                   float r, float g, float b, float a) {
        // Default outline: a brighter shade of the fill (foreign / purchasable chunks).
        drawChunk(pose, buffer, packed, r, g, b, a,
            Math.min(1.0f, r * 1.4f), Math.min(1.0f, g * 1.4f), Math.min(1.0f, b * 1.4f));
    }

    /** As {@link #drawChunk(PoseStack, MultiBufferSource, long, float, float, float, float)}
     *  but with an explicit outline color — own chunks pass the faction's secondary accent so
     *  the territory reads two-tone (primary fill, accent border). */
    private static void drawChunk(PoseStack pose, MultiBufferSource buffer, long packed,
                                   float r, float g, float b, float a,
                                   float lineR, float lineG, float lineB) {
        ChunkPos cp = new ChunkPos(packed);
        double x0 = cp.getMinBlockX();
        double z0 = cp.getMinBlockZ();
        double x1 = x0 + 16;
        double z1 = z0 + 16;
        double slabY = ClientBirdseyeState.slabY();
        DebugRenderer.renderFilledBox(pose, buffer,
            x0, slabY, z0,
            x1, slabY + SLAB_HEIGHT, z1,
            r, g, b, a);
        LevelRenderer.renderLineBox(pose, buffer.getBuffer(RenderType.lines()),
            x0, slabY + SLAB_HEIGHT, z0,
            x1, slabY + SLAB_HEIGHT + 0.05, z1,
            lineR, lineG, lineB, 0.95f);
    }
}
