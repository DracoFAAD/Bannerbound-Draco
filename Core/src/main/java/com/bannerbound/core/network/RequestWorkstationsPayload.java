package com.bannerbound.core.network;

import com.bannerbound.core.api.settlement.Workstation;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: the player pressed "Assign to Workstation" on the citizen detail screen.
 * Server replies with a {@link WorkstationListPayload} of all workstations in the citizen's
 * settlement so the player can pick one.
 */
@ApiStatus.Internal
public record RequestWorkstationsPayload(int citizenEntityId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RequestWorkstationsPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "request_workstations"));

    public static final StreamCodec<ByteBuf, RequestWorkstationsPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeInt(p.citizenEntityId()),
        buf -> new RequestWorkstationsPayload(buf.readInt())
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
