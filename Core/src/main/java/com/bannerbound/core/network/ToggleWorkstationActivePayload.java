package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: flip the active/inactive state of a workstation. Server validates that the
 * requesting player is in the workstation's settlement before applying. Used by the workstation
 * GUI's active/inactive checkbox.
 */
@ApiStatus.Internal
public record ToggleWorkstationActivePayload(BlockPos pos, boolean active) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ToggleWorkstationActivePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "toggle_workstation_active"));

    public static final StreamCodec<ByteBuf, ToggleWorkstationActivePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeInt(p.pos().getX());
            buf.writeInt(p.pos().getY());
            buf.writeInt(p.pos().getZ());
            buf.writeBoolean(p.active());
        },
        buf -> new ToggleWorkstationActivePayload(
            new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()),
            buf.readBoolean())
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
