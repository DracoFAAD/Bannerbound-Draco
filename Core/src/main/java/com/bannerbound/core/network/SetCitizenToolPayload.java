package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C→S: install the tool in the player's inventory slot {@code playerInvSlot} into the citizen's
 *  primary slot, or the pickaxe (second) slot when {@code pickaxe} is true. The server validates the
 *  item is the right role tool at/below the current tool age and moves exactly one. */
@ApiStatus.Internal
public record SetCitizenToolPayload(int entityId, int playerInvSlot, boolean pickaxe)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SetCitizenToolPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "set_citizen_tool"));

    public static final StreamCodec<ByteBuf, SetCitizenToolPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
            ByteBufCodecs.VAR_INT.encode(buf, p.playerInvSlot());
            ByteBufCodecs.BOOL.encode(buf, p.pickaxe());
        },
        buf -> new SetCitizenToolPayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.BOOL.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
