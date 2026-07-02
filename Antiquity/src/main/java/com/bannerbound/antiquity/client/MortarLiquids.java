package com.bannerbound.antiquity.client;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.DyeColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side registry of liquids the Mortar and Pestle can hold. Each liquid resolves to a
 * block-atlas sprite (animated for free if its texture has a {@code .mcmeta}) plus an ARGB tint.
 * The block entity only stores a liquid id string — this is where that id becomes pixels.
 * <p>
 * Mod support: call {@link #register} with a new id and your own sprite + tint. Water is the
 * only liquid wired today; dyes/inks slot in here without touching the block or renderer.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class MortarLiquids {
    /** A liquid's visual: which block-atlas sprite to draw, and the ARGB colour to tint it. */
    public record Entry(ResourceLocation spriteId, int tint) {
        /** Resolves the sprite from the block atlas. Animated automatically by the atlas ticker
         *  when the underlying texture carries an animation {@code .mcmeta} (vanilla water does). */
        public TextureAtlasSprite sprite() {
            return Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(spriteId);
        }
    }

    /** Every liquid reuses vanilla's animated still-water sprite, just tinted differently. */
    private static final ResourceLocation WATER_SPRITE =
        ResourceLocation.withDefaultNamespace("block/water_still");

    private static final Map<String, Entry> REGISTRY = new HashMap<>();

    static {
        // Water — vanilla water blue at ~80% alpha.
        register("water", new Entry(WATER_SPRITE, 0xCC3F76E4));
        // Ink — the dye produced by grinding an ink sac; near-opaque pure black.
        register("ink", new Entry(WATER_SPRITE, 0xF0050505));
        // One liquid per dye colour, tinted with that dye's colour at ~80% alpha. Recipes
        // produce these by id (the DyeColor name, e.g. "pink", "light_gray").
        for (DyeColor color : DyeColor.values()) {
            register(color.getName(),
                new Entry(WATER_SPRITE, 0xCC000000 | (color.getTextureDiffuseColor() & 0xFFFFFF)));
        }
    }

    private MortarLiquids() {
    }

    public static void register(String id, Entry entry) {
        REGISTRY.put(id, entry);
    }

    /** The liquid for {@code id}, or {@code null} for an empty/unknown id. */
    @Nullable
    public static Entry get(String id) {
        return id == null || id.isEmpty() ? null : REGISTRY.get(id);
    }
}
