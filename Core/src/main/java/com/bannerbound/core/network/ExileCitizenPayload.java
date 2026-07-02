package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: the player pressed Exile on the citizen detail screen. Server resolves the
 * entity by its network id, confirms the actor is a member of that citizen's settlement, removes
 * the citizen from the roster, and despawns the entity.
 */
@ApiStatus.Internal
public record ExileCitizenPayload(int entityId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ExileCitizenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "exile_citizen"));

    public static final StreamCodec<ByteBuf, ExileCitizenPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> ByteBufCodecs.VAR_INT.encode(buf, p.entityId()),
        buf -> new ExileCitizenPayload(ByteBufCodecs.VAR_INT.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
