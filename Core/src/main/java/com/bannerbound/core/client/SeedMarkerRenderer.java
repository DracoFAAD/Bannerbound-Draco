package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.farmer.SeedCandidates;
import com.bannerbound.core.api.world.BlockSelection;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws the assigned crop's yield item floating above each farmer selection — a 3D "waypoint"
 * so the player can see at a glance what's planted there. Only renders while the local player
 * is holding a Foreman's Rod that's been configured for the "farmer" workstation type, so a
 * digger-rod doesn't see clutter from other workstations. One marker per selection at the
 * selection's geometric center, Y = maxY + 3, billboarded to the camera.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class SeedMarkerRenderer {
    private static final float Y_OFFSET = 3.0f;
    private static final float ICON_SCALE = 1.6f;

    private SeedMarkerRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        ItemStack rodStack = heldRodStack(player);
        if (rodStack == null) return;
        // Only show markers while the player's rod is set to "farmer" — otherwise a digger-rod
        // would see seed icons floating over fields it doesn't manage.
        String rodType = rodStack.get(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get());
        if (!"farmer".equals(rodType)) return;

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        float yaw = camera.getYRot();
        float pitch = camera.getXRot();

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        ItemRenderer itemRenderer = mc.getItemRenderer();
        java.util.Map<java.util.UUID, com.bannerbound.core.entity.CitizenEntity> citizens = null; // lazy

        for (BlockSelection sel : ClientSelectionState.getAll()) {
            if (sel.completed()) continue;
            if (!"farmer".equals(sel.workstationType())) continue;
            if (sel.seedItemId() == null || sel.seedItemId().isEmpty()) continue;
            Item outputItem = SeedCandidates.outputFor(sel.seedItemId());
            if (outputItem == Items.AIR) continue;
            ItemStack icon = new ItemStack(outputItem);

            double cx = (sel.minX() + sel.maxX() + 1) / 2.0;
            double cy = sel.maxY() + 1.0 + Y_OFFSET;
            double cz = (sel.minZ() + sel.maxZ() + 1) / 2.0;

            pose.pushPose();
            pose.translate(cx - cam.x, cy - cam.y, cz - cam.z);
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-yaw));
            pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(pitch));
            pose.scale(ICON_SCALE, ICON_SCALE, ICON_SCALE);
            itemRenderer.renderStatic(
                icon,
                ItemDisplayContext.GROUND,
                0x00F000F0,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                pose,
                buffer,
                mc.level,
                0);
            pose.popPose();

            // If this field belongs to ONE citizen (not "all farmers"), draw their face below the icon
            // — like a TAB-list head. "All" fields get no face.
            if (!sel.targetsAllWorkers()) {
                if (citizens == null) citizens = collectCitizens(mc);
                com.bannerbound.core.entity.CitizenEntity owner = citizens.get(sel.assignedCitizenId());
                if (owner != null) {
                    net.minecraft.resources.ResourceLocation skin = skinFor(mc, owner);
                    pose.pushPose();
                    pose.translate(cx - cam.x, cy - cam.y - FACE_DROP, cz - cam.z);
                    pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-yaw));
                    pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(pitch));
                    drawFace(pose, buffer, skin, FACE_SIZE);
                    pose.popPose();
                }
            }
        }
        buffer.endBatch();
    }

    private static final float FACE_SIZE = 0.9f;
    private static final float FACE_DROP = 1.25f;   // below the yield icon

    /** Draws the citizen's face (skin face layer + hat overlay) as a 1-quad billboard in the current
     *  pose. Mirrors how the multiplayer TAB list composites a head from the skin's 8×8 face region. */
    private static void drawFace(PoseStack pose, MultiBufferSource buffer,
                                 net.minecraft.resources.ResourceLocation skin, float size) {
        org.joml.Matrix4f m = pose.last().pose();
        net.minecraft.client.renderer.RenderType type =
            net.minecraft.client.renderer.RenderType.entityCutoutNoCull(skin);
        // Base face: skin pixels (8,8)..(16,16) of a 64×64 sheet.
        drawQuad(m, buffer.getBuffer(type), size, 8f / 64f, 16f / 64f, 0f);
        // Hat overlay: (40,8)..(48,16), nudged toward the camera so it sits on top.
        drawQuad(m, buffer.getBuffer(type), size, 40f / 64f, 48f / 64f, 0.01f);
    }

    private static void drawQuad(org.joml.Matrix4f m, com.mojang.blaze3d.vertex.VertexConsumer vc,
                                 float size, float u0, float u1, float z) {
        float h = size / 2f;
        float v0 = 8f / 64f, v1 = 16f / 64f;   // top / bottom of the face row
        int light = 0x00F000F0;
        quadVertex(vc, m, -h, -h, z, u0, v1, light);  // bottom-left
        quadVertex(vc, m,  h, -h, z, u1, v1, light);  // bottom-right
        quadVertex(vc, m,  h,  h, z, u1, v0, light);  // top-right
        quadVertex(vc, m, -h,  h, z, u0, v0, light);  // top-left
    }

    private static void quadVertex(com.mojang.blaze3d.vertex.VertexConsumer vc, org.joml.Matrix4f m,
                                   float x, float y, float z, float u, float v, int light) {
        vc.addVertex(m, x, y, z)
            .setColor(255, 255, 255, 255)
            .setUv(u, v)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(0f, 0f, 1f);
    }

    /** The skin texture for {@code citizen}, via its live renderer (same art the entity uses). */
    private static net.minecraft.resources.ResourceLocation skinFor(Minecraft mc,
            com.bannerbound.core.entity.CitizenEntity citizen) {
        var renderer = mc.getEntityRenderDispatcher().getRenderer(citizen);
        if (renderer instanceof CitizenRenderer cr) return cr.getTextureLocation(citizen);
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/entity/citizen.png");
    }

    /** Map of loaded citizens by UUID, built once per frame only when a bound field needs a face. */
    private static java.util.Map<java.util.UUID, com.bannerbound.core.entity.CitizenEntity> collectCitizens(Minecraft mc) {
        java.util.Map<java.util.UUID, com.bannerbound.core.entity.CitizenEntity> out = new java.util.HashMap<>();
        if (mc.level == null) return out;
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof com.bannerbound.core.entity.CitizenEntity c) out.put(c.getUUID(), c);
        }
        return out;
    }

    private static ItemStack heldRodStack(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.is(BannerboundCore.FOREMANS_ROD.get())) return main;
        ItemStack off = player.getOffhandItem();
        if (off.is(BannerboundCore.FOREMANS_ROD.get())) return off;
        return null;
    }
}
