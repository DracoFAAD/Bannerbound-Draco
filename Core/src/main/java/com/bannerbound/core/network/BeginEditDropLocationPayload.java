package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C→S: the player clicked "Set drop location" (or "Set seeds storage", when {@code seed}) on the
 *  Job tab. The server validates permission and replies with {@link OpenDropLocationEditPayload} so
 *  the client enters the in-world edit mode. {@code seed} marks the farmer's seed source instead of
 *  the harvest drop-off. */
@ApiStatus.Internal
public record BeginEditDropLocationPayload(int entityId, boolean seed) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BeginEditDropLocationPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "begin_edit_drop_location"));

    public static final StreamCodec<ByteBuf, BeginEditDropLocationPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
            ByteBufCodecs.BOOL.encode(buf, p.seed());
        },
        buf -> new BeginEditDropLocationPayload(
            ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.BOOL.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
