package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C→S: remove a citizen's job tool from the Job tab — returned to the player's inventory if there's
 *  room, otherwise into the drop-off / at the citizen's feet. */
@ApiStatus.Internal
public record ClearCitizenToolPayload(int entityId, boolean pickaxe) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ClearCitizenToolPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "clear_citizen_tool"));

    public static final StreamCodec<ByteBuf, ClearCitizenToolPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
            ByteBufCodecs.BOOL.encode(buf, p.pickaxe());
        },
        buf -> new ClearCitizenToolPayload(
            ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.BOOL.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
