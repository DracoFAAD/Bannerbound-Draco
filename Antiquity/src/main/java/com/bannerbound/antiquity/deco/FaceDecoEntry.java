package com.bannerbound.antiquity.deco;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.StreamCodec;

/** One decorated (block, face) → its {@link FaceDeco}. The serialization/sync unit for both the
 *  chunk attachment and the network payloads. */
public record FaceDecoEntry(BlockPos pos, Direction dir, FaceDeco deco) {
    public static final Codec<FaceDecoEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
        BlockPos.CODEC.fieldOf("pos").forGetter(FaceDecoEntry::pos),
        Direction.CODEC.fieldOf("dir").forGetter(FaceDecoEntry::dir),
        FaceDeco.CODEC.fieldOf("deco").forGetter(FaceDecoEntry::deco)
    ).apply(i, FaceDecoEntry::new));

    public static final StreamCodec<ByteBuf, FaceDecoEntry> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, FaceDecoEntry::pos,
        Direction.STREAM_CODEC, FaceDecoEntry::dir,
        FaceDeco.STREAM_CODEC, FaceDecoEntry::deco,
        FaceDecoEntry::new);
}
