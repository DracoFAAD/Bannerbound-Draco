package com.bannerbound.antiquity.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server to client: open the press-and-grind mortar minigame. {@code reps} is how many press +
 *  grind beats finish the batch; {@code batch} is how many ingredients are loaded (for display).
 *  Non-skill, like the pottery wheel — there's no accuracy scoring. */
@ApiStatus.Internal
public record OpenMortarGrindPayload(BlockPos pos, int reps, int batch)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenMortarGrindPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "open_mortar_grind"));

    public static final StreamCodec<ByteBuf, OpenMortarGrindPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenMortarGrindPayload::pos,
            ByteBufCodecs.VAR_INT, OpenMortarGrindPayload::reps,
            ByteBufCodecs.VAR_INT, OpenMortarGrindPayload::batch,
            OpenMortarGrindPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
