package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.item.ArrowParts;
import com.bannerbound.antiquity.recipe.ArrowPart;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The modular arrow's inventory/hand/ground icon — composited at render time from the three part
 * sprites named in the {@link ArrowPart} registry, so a modpack-added material's icon appears with NO
 * model files (the layered combo-model approach can't be extended by a datapack). Each layer is a flat
 * textured quad drawn from the item atlas (vanilla already stitches every {@code textures/item/**}); the
 * arrow.json model is {@code builtin/entity} so this renderer runs in every display context with the
 * standard item transforms. Mirrors the flat-quad technique of {@link CompositeArrowRenderer}.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class CompositeArrowItemRenderer extends BlockEntityWithoutLevelRenderer {

    public CompositeArrowItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),
            Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext ctx, PoseStack pose,
                             MultiBufferSource buffer, int light, int overlay) {
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutout(TextureAtlas.LOCATION_BLOCKS));
        // Back (lowest) → shaft → tip, each lifted a hair in z so overlapping pixels never fight.
        float z = 0.5F;
        z = layer(vc, pose, light, overlay, ArrowParts.itemTexture(ArrowPart.SLOT_BACK, ArrowParts.back(stack)), z);
        z = layer(vc, pose, light, overlay, ArrowParts.itemTexture(ArrowPart.SLOT_SHAFT, ArrowParts.shaft(stack)), z);
        layer(vc, pose, light, overlay, ArrowParts.itemTexture(ArrowPart.SLOT_TIP, ArrowParts.tip(stack)), z);
    }

    /** Draws one part layer as a double-sided flat quad filling the icon, returns the next z to use. */
    private static float layer(VertexConsumer vc, PoseStack pose, int light, int overlay,
                               ResourceLocation texture, float z) {
        if (texture == null) return z;
        TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager()
            .getAtlas(TextureAtlas.LOCATION_BLOCKS).getSprite(texture);
        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
        PoseStack.Pose p = pose.last();
        // Front face (+Z, toward the GUI camera).
        v(vc, p, 0, 0, z, u0, v1, 0, 0, 1, light, overlay);
        v(vc, p, 1, 0, z, u1, v1, 0, 0, 1, light, overlay);
        v(vc, p, 1, 1, z, u1, v0, 0, 0, 1, light, overlay);
        v(vc, p, 0, 1, z, u0, v0, 0, 0, 1, light, overlay);
        // Back face (−Z), reversed winding, so the icon is visible from behind in third person.
        v(vc, p, 0, 0, z, u0, v1, 0, 0, -1, light, overlay);
        v(vc, p, 0, 1, z, u0, v0, 0, 0, -1, light, overlay);
        v(vc, p, 1, 1, z, u1, v0, 0, 0, -1, light, overlay);
        v(vc, p, 1, 0, z, u1, v1, 0, 0, -1, light, overlay);
        return z + 0.001F;
    }

    private static void v(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z,
                          float u, float w, int nx, int ny, int nz, int light, int overlay) {
        vc.addVertex(p, x, y, z)
            .setColor(-1)
            .setUv(u, w)
            .setOverlay(overlay)
            .setLight(light)
            .setNormal(p, nx, ny, nz);
    }
}
