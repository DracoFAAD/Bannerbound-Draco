package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C→S: toggle one huntable species on/off for a hunter citizen from the Job tab prey picker.
 *  {@code entityTypeId} is the species' registry id string (e.g. {@code "minecraft:cow"}) — the
 *  prey list is the data-driven {@code #bannerbound:huntable} tag, so there's no stable ordinal. */
@ApiStatus.Internal
public record SetHunterPreyPayload(int entityId, String entityTypeId, boolean enabled)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SetHunterPreyPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "set_hunter_prey"));

    public static final StreamCodec<ByteBuf, SetHunterPreyPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.entityTypeId());
            ByteBufCodecs.BOOL.encode(buf, p.enabled());
        },
        buf -> new SetHunterPreyPayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.BOOL.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
