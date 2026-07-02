package com.bannerbound.antiquity.block;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

/**
 * The nine greyscale trim shapes the paint brush can stamp onto a block face (square / triangle /
 * wave families). {@link #sprite()} is the block-atlas sprite (art under {@code textures/block/trims/};
 * stitched via {@code assets/minecraft/atlases/blocks.json}). The serialized name is the lang-key
 * suffix. The wave top/middle/bottom variants stack vertically into a continuous wave.
 */
public enum TrimShape implements StringRepresentable {
    SQUARE("square", "square"),
    SQUARE_FILLED("square_filled", "square_filled"),
    TRIANGLE("triangle", "triangle"),
    TRIANGLE_FILLED("triangle_filled", "triangle_filled"),
    TRIANGLE_DOWN("triangle_down", "triangle_down"),
    TRIANGLE_DOWN_FILLED("triangle_down_filled", "triangle_down_filled"),
    WAVE_TOP("wave_top", "wave_top_horizontal"),
    WAVE_MIDDLE("wave_middle", "wave_middle_horizontal"),
    WAVE_BOTTOM("wave_bottom", "wave_bottom_horizontal");

    /** All shapes in selection order (matches the paint brush's cycle). */
    public static final TrimShape[] ALL = values();

    public static final Codec<TrimShape> CODEC = StringRepresentable.fromEnum(TrimShape::values);
    public static final StreamCodec<ByteBuf, TrimShape> STREAM_CODEC =
        ByteBufCodecs.idMapper(i -> ALL[i], TrimShape::ordinal);

    private final String name;
    private final String texture;

    TrimShape(String name, String texture) {
        this.name = name;
        this.texture = texture;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    /** Block-atlas sprite location for this shape's greyscale texture. */
    public ResourceLocation sprite() {
        return ResourceLocation.fromNamespaceAndPath("bannerboundantiquity", "block/trims/" + this.texture);
    }
}
