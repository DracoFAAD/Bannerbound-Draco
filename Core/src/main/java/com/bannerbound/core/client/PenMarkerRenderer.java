package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.building.PenEnclosure;
import com.bannerbound.core.entity.BreedingEvents;
import com.bannerbound.core.entity.HerderWorkGoal;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Floats each herder pen's ANIMAL (its spawn egg) above the pen, with the assigned herder's face below it
 * when the pen is bound to one citizen (open pens show just the animal — any herder works them). The
 * herder-pen counterpart of {@link SeedMarkerRenderer}; only shows while the local player holds a Foreman's
 * Rod set to the "herder" type, so a digger/farmer rod doesn't see pen clutter.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class PenMarkerRenderer {
    private static final float Y_OFFSET = 3.0f;
    private static final float ICON_SCALE = 1.6f;
    private static final float FACE_SIZE = 0.9f;
    private static final float FACE_DROP = 1.25f;
    private static final float LABEL_RISE = 1.1f;        // breed-quality % sits above the animal egg
    private static final int QUALITY_REFRESH = 20;       // recompute a pen's quality at most ~once/second
    private static final double QUALITY_RANGE_SQ = 48.0 * 48.0;   // don't scan/label pens you can't read
    private static final int FULLBRIGHT = 0x00F000F0;

    /** Client-side throttled cache of pen breeding quality (percent), keyed by packed pen anchor. Scanning a
     *  pen every frame would be wasteful, so we recompute at most every {@link #QUALITY_REFRESH} ticks. */
    private record Quality(long tick, int pct) {}
    private static final java.util.Map<Long, Quality> QUALITY_CACHE = new java.util.HashMap<>();

    private PenMarkerRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        ItemStack rod = heldRodStack(player);
        if (rod == null) return;
        String rodType = rod.get(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get());
        if (!HerderWorkGoal.SELECTION_TYPE.equals(rodType)) return;

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        float yaw = camera.getYRot();
        float pitch = camera.getXRot();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        ItemRenderer itemRenderer = mc.getItemRenderer();
        java.util.Map<java.util.UUID, com.bannerbound.core.entity.CitizenEntity> citizens = null; // lazy

        for (BlockSelection sel : ClientSelectionState.getAll()) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!HerderWorkGoal.SELECTION_TYPE.equals(sel.workstationType())) continue;
            EntityType<? extends net.minecraft.world.entity.animal.Animal> type = HerderWorkGoal.animalFromMarker(sel);
            if (type == null) continue;
            ItemStack icon = productIcon(type);

            double cx = sel.minX() + 0.5;            // pen markers are a single point (the clicked anchor)
            double cy = sel.minY() + 1.0 + Y_OFFSET;
            double cz = sel.minZ() + 0.5;

            pose.pushPose();
            pose.translate(cx - cam.x, cy - cam.y, cz - cam.z);
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-yaw));
            pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(pitch));
            pose.scale(ICON_SCALE, ICON_SCALE, ICON_SCALE);
            itemRenderer.renderStatic(icon, ItemDisplayContext.GROUND, 0x00F000F0,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, pose, buffer, mc.level, 0);
            pose.popPose();

            // Breeding-quality readout above the egg (green/yellow/red %) — "is this pen good enough?". Only
            // for pens close enough to read; throttled so we don't flood-scan the pen every frame. If the
            // settlement hasn't researched Animal Husbandry, breeding is off entirely → show that instead of
            // a misleading percentage.
            if (cam.distanceToSqr(cx, cy, cz) <= QUALITY_RANGE_SQ) {
                if (!ClientResearchState.hasFlag(com.bannerbound.core.event.AnimalBreedingGate.FLAG)) {
                    drawLabel(pose, buffer, mc.font, "Locked",
                        cx - cam.x, cy - cam.y + LABEL_RISE, cz - cam.z, yaw, pitch, 0xFFAAAAAA);
                } else {
                    int pct = quality(mc.level, sel);
                    if (pct >= 0) {
                        drawLabel(pose, buffer, mc.font, pct + "%",
                            cx - cam.x, cy - cam.y + LABEL_RISE, cz - cam.z, yaw, pitch, qualityColor(pct));
                    }
                }
            }

            // Bound to one herder → show their face below the animal. Open pens (any herder) show none.
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

    private static void drawFace(PoseStack pose, MultiBufferSource buffer,
                                 net.minecraft.resources.ResourceLocation skin, float size) {
        org.joml.Matrix4f m = pose.last().pose();
        net.minecraft.client.renderer.RenderType type =
            net.minecraft.client.renderer.RenderType.entityCutoutNoCull(skin);
        drawQuad(m, buffer.getBuffer(type), size, 8f / 64f, 16f / 64f, 0f);
        drawQuad(m, buffer.getBuffer(type), size, 40f / 64f, 48f / 64f, 0.01f);
    }

    private static void drawQuad(org.joml.Matrix4f m, com.mojang.blaze3d.vertex.VertexConsumer vc,
                                 float size, float u0, float u1, float z) {
        float h = size / 2f;
        float v0 = 8f / 64f, v1 = 16f / 64f;
        int light = 0x00F000F0;
        quadVertex(vc, m, -h, -h, z, u0, v1, light);
        quadVertex(vc, m,  h, -h, z, u1, v1, light);
        quadVertex(vc, m,  h,  h, z, u1, v0, light);
        quadVertex(vc, m, -h,  h, z, u0, v0, light);
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

    /** The icon for a pen's animal — its familiar PRODUCT (beef/egg/pork/mutton/leather), which reads more
     *  clearly to the player than a spawn egg. Falls back to the spawn egg, then wheat, for anything else. */
    private static ItemStack productIcon(EntityType<?> type) {
        net.minecraft.world.item.Item item;
        if (type == EntityType.COW) item = Items.BEEF;
        else if (type == EntityType.CHICKEN) item = Items.EGG;
        else if (type == EntityType.PIG) item = Items.PORKCHOP;
        else if (type == EntityType.SHEEP) item = Items.MUTTON;
        else if (type == EntityType.HORSE) item = Items.LEATHER;
        else {
            SpawnEggItem egg = SpawnEggItem.byId(type);
            item = egg != null ? egg : Items.WHEAT;
        }
        return new ItemStack(item);
    }

    /** This pen's breeding quality as a percent (0-100), or -1 if the enclosure isn't currently valid.
     *  Throttled via {@link #QUALITY_CACHE} so we scan a pen at most once per {@link #QUALITY_REFRESH} ticks. */
    private static int quality(net.minecraft.world.level.Level level, BlockSelection sel) {
        long key = new BlockPos(sel.minX(), sel.minY(), sel.minZ()).asLong();
        long now = level.getGameTime();
        Quality q = QUALITY_CACHE.get(key);
        if (q == null || now - q.tick() > QUALITY_REFRESH) {
            PenEnclosure.Result r = PenEnclosure.scan(level, new BlockPos(sel.minX(), sel.minY(), sel.minZ()));
            int pct = r.valid() ? (int) Math.round(BreedingEvents.penBreedQuality(level, r) * 100.0) : -1;
            q = new Quality(now, pct);
            QUALITY_CACHE.put(key, q);
        }
        return q.pct();
    }

    /** Green at/above 65%, yellow 40-64%, red below — a quick "good enough?" read. ARGB (opaque). */
    private static int qualityColor(int pct) {
        if (pct >= 65) return 0xFF55FF55;
        if (pct >= 40) return 0xFFFFFF55;
        return 0xFFFF5555;
    }

    /** Billboarded text centred at the camera-relative offset (mirrors ChunkTypeOverlayRenderer's label). */
    private static void drawLabel(PoseStack pose, MultiBufferSource buffer, Font font, String text,
                                  double dx, double dy, double dz, float yaw, float pitch, int color) {
        pose.pushPose();
        pose.translate(dx, dy, dz);
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-yaw));
        pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(pitch));
        pose.scale(-0.025f, -0.025f, 0.025f);   // font renders +Y down + tiny world-space scale
        org.joml.Matrix4f m = pose.last().pose();
        float x = -font.width(text) / 2f;
        font.drawInBatch(text, x, 0f, color, false, m, buffer, Font.DisplayMode.SEE_THROUGH, 0, FULLBRIGHT);
        pose.popPose();
    }

    private static net.minecraft.resources.ResourceLocation skinFor(Minecraft mc,
            com.bannerbound.core.entity.CitizenEntity citizen) {
        var renderer = mc.getEntityRenderDispatcher().getRenderer(citizen);
        if (renderer instanceof CitizenRenderer cr) return cr.getTextureLocation(citizen);
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/entity/citizen.png");
    }

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
