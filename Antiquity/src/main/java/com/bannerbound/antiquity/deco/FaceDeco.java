package com.bannerbound.antiquity.deco;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.bannerbound.antiquity.block.TrimShape;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.DyeColor;

/**
 * The decoration on ONE face of ONE block: an optional plaster coat (the lower layer) and an optional
 * trim ({@link TrimShape} + {@link DyeColor}, the upper layer). Plaster and trim are independent — a
 * face may have either, both, or (when {@link #isEmpty()}) neither, in which case it is not stored.
 */
public record FaceDeco(boolean plaster, Optional<TrimShape> trim, DyeColor trimColor) {
    public static final FaceDeco EMPTY = new FaceDeco(false, Optional.empty(), DyeColor.WHITE);

    public static final Codec<FaceDeco> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.BOOL.optionalFieldOf("plaster", Boolean.FALSE).forGetter(FaceDeco::plaster),
        TrimShape.CODEC.optionalFieldOf("trim").forGetter(FaceDeco::trim),
        DyeColor.CODEC.optionalFieldOf("color", DyeColor.WHITE).forGetter(FaceDeco::trimColor)
    ).apply(i, FaceDeco::new));

    public static final StreamCodec<ByteBuf, FaceDeco> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.BOOL, FaceDeco::plaster,
        ByteBufCodecs.optional(TrimShape.STREAM_CODEC), FaceDeco::trim,
        DyeColor.STREAM_CODEC, FaceDeco::trimColor,
        FaceDeco::new);

    public boolean isEmpty() {
        return !plaster && trim.isEmpty();
    }

    public boolean hasTrim() {
        return trim.isPresent();
    }

    public FaceDeco withPlaster(boolean p) {
        return new FaceDeco(p, trim, trimColor);
    }

    public FaceDeco withTrim(TrimShape shape, DyeColor color) {
        return new FaceDeco(plaster, Optional.of(shape), color);
    }

    public FaceDeco withoutTrim() {
        return new FaceDeco(plaster, Optional.empty(), trimColor);
    }
}
