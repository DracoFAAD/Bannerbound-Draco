package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: the player pressed "Assign Worker" on a workstation. Server replies with a
 * {@link CitizenListPayload} listing unemployed citizens in that settlement.
 */
@ApiStatus.Internal
public record RequestUnemployedCitizensPayload(BlockPos workstationPos) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RequestUnemployedCitizensPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "request_unemployed"));

    public static final StreamCodec<ByteBuf, RequestUnemployedCitizensPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeInt(p.workstationPos().getX());
            buf.writeInt(p.workstationPos().getY());
            buf.writeInt(p.workstationPos().getZ());
        },
        buf -> new RequestUnemployedCitizensPayload(new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    @SuppressWarnings("unused")
    private static final Class<?> CODECS_DEP = ByteBufCodecs.class;
}
