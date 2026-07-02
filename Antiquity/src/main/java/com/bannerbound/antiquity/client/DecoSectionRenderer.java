package com.bannerbound.antiquity.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.deco.ChunkDecorations;
import com.bannerbound.antiquity.deco.FaceDeco;
import com.bannerbound.antiquity.deco.FaceDecoEntry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Bakes the plaster/trim face overlays into chunk-section geometry via {@link AddSectionGeometryEvent}
 * — flush quads on decorated faces (plaster under, trim on top), proper chunk lighting, no
 * BlockEntity, leaving the adjacent cell free. Data comes from {@link ClientDecorations}; a change
 * re-bakes the section. Reads are done on the main thread in the event handler; only the gathered
 * snapshot is touched on the mesher thread.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public final class DecoSectionRenderer {
    // Tiny outward offsets to sit just proud of the face without z-fighting. Kept small: the gap is
    // a perpendicular offset, so at grazing angles it projects to a visible seam — ~0.0015 blocks is
    // sub-pixel even edge-on, while still beating depth-fighting with the block face.
    private static final float PLASTER_EPS = 0.0015f; // lower layer
    private static final float TRIM_EPS = 0.003f;     // upper layer, just above plaster

    private DecoSectionRenderer() {}

    @SubscribeEvent
    static void onAddGeometry(AddSectionGeometryEvent event) {
        BlockPos origin = event.getSectionOrigin();
        ChunkDecorations cd = ClientDecorations.chunkAt(origin.getX() >> 4, origin.getZ() >> 4);
        if (cd == null) {
            return;
        }
        List<FaceDecoEntry> here = new ArrayList<>();
        cd.forEachInYRange(origin.getY(), origin.getY() + 16, here::add);
        if (here.isEmpty()) {
            return;
        }
        event.addRenderer(context -> emit(context, origin, here));
    }

    @SubscribeEvent
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            ClientDecorations.forgetChunk(event.getChunk().getPos().x, event.getChunk().getPos().z);
        }
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientDecorations.clear();
    }

    /** Plaster makes a block sturdier to mine: a plastered block breaks at half speed (client-side
     *  feel; the block's own hardness is unchanged so the server accepts the slower break). */
    @SubscribeEvent
    static void onBreakSpeed(net.neoforged.neoforge.event.entity.player.PlayerEvent.BreakSpeed event) {
        event.getPosition().ifPresent(pos -> {
            if (ClientDecorations.hasPlaster(pos)) {
                event.setNewSpeed(event.getNewSpeed() * 0.5F);
            }
        });
    }

    private static void emit(AddSectionGeometryEvent.SectionRenderingContext context,
                             BlockPos origin, List<FaceDecoEntry> entries) {
        VertexConsumer vc = context.getOrCreateChunkBuffer(RenderType.cutout());
        BlockAndTintGetter region = context.getRegion();
        PoseStack.Pose pose = context.getPoseStack().last();
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
        TextureAtlasSprite plaster = atlas.getSprite(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "block/plaster/plaster"));

        for (FaceDecoEntry e : entries) {
            BlockPos pos = e.pos();
            Direction dir = e.dir();
            FaceDeco d = e.deco();
            if (region.getBlockState(pos).isAir()) {
                continue; // block gone via a path we didn't clear — don't draw a floating overlay
            }
            int light = LevelRenderer.getLightColor(region, pos.relative(dir));
            float lx = pos.getX() - origin.getX();
            float ly = pos.getY() - origin.getY();
            float lz = pos.getZ() - origin.getZ();
            if (d.plaster()) {
                face(vc, pose, lx, ly, lz, dir, PLASTER_EPS, plaster, 0xFFFFFFFF, light);
            }
            if (d.hasTrim()) {
                TextureAtlasSprite s = atlas.getSprite(d.trim().get().sprite());
                int argb = 0xFF000000 | (d.trimColor().getTextureDiffuseColor() & 0xFFFFFF);
                face(vc, pose, lx, ly, lz, dir, TRIM_EPS, s, argb, light);
            }
        }
    }

    /** Emit a double-sided textured quad on the given face of cell (lx,ly,lz), pushed out by eps. */
    private static void face(VertexConsumer vc, PoseStack.Pose pose, float lx, float ly, float lz,
                             Direction dir, float eps, TextureAtlasSprite sprite, int argb, int light) {
        float[][] c = corners(dir, lx, ly, lz);
        float nx = dir.getStepX();
        float ny = dir.getStepY();
        float nz = dir.getStepZ();
        for (float[] p : c) {
            p[0] += nx * eps;
            p[1] += ny * eps;
            p[2] += nz * eps;
        }
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        float[][] uv = { {u0, v0}, {u1, v0}, {u1, v1}, {u0, v1} };
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int a = (argb >>> 24) & 0xFF;
        // front winding
        for (int i = 0; i < 4; i++) {
            vertex(vc, pose, c[i], uv[i], r, g, b, a, light, nx, ny, nz);
        }
        // back winding (so it shows regardless of the face's outward orientation)
        for (int i = 3; i >= 0; i--) {
            vertex(vc, pose, c[i], uv[i], r, g, b, a, light, -nx, -ny, -nz);
        }
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float[] p, float[] uv,
                               int r, int g, int b, int a, int light, float nx, float ny, float nz) {
        vc.addVertex(pose.pose(), p[0], p[1], p[2])
            .setColor(r, g, b, a)
            .setUv(uv[0], uv[1])
            .setLight(light)
            .setNormal(pose, nx, ny, nz);
    }

    /** The four boundary corners of {@code dir}'s face of unit cell (lx,ly,lz), CCW from outside. */
    private static float[][] corners(Direction dir, float x, float y, float z) {
        float x1 = x + 1, y1 = y + 1, z1 = z + 1;
        return switch (dir) {
            case DOWN  -> new float[][] {{x, y, z}, {x1, y, z}, {x1, y, z1}, {x, y, z1}};
            case UP    -> new float[][] {{x, y1, z1}, {x1, y1, z1}, {x1, y1, z}, {x, y1, z}};
            case NORTH -> new float[][] {{x1, y, z}, {x, y, z}, {x, y1, z}, {x1, y1, z}};
            case SOUTH -> new float[][] {{x, y, z1}, {x1, y, z1}, {x1, y1, z1}, {x, y1, z1}};
            case WEST  -> new float[][] {{x, y, z1}, {x, y, z}, {x, y1, z}, {x, y1, z1}};
            case EAST  -> new float[][] {{x1, y, z}, {x1, y, z1}, {x1, y1, z1}, {x1, y1, z}};
        };
    }
}
