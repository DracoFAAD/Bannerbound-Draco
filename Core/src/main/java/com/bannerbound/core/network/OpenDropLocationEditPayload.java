package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C: enter the in-world drop-location edit mode for a citizen. Carries the styled citizen name +
 * job title and the settlement color (packed RGB) so the client can render the action-bar prompt in
 * the settlement's color without a settlement lookup.
 */
@ApiStatus.Internal
public record OpenDropLocationEditPayload(
    int entityId,
    Component name,
    Component jobTitle,
    int settlementRgb,
    boolean seed
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenDropLocationEditPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "open_drop_location_edit"));

    /** Sentinel {@link #entityId} meaning "this edit targets the settlement-wide preferred storage",
     *  not a citizen. Shared by the server (DropLocationEditServer / guard) and the client edit state.
     *  {@link Integer#MIN_VALUE} so it never collides with a real entity id or the -1 inactive flag. */
    public static final int PREFERRED_STORAGE_TARGET = Integer.MIN_VALUE;

    // RegistryFriendlyByteBuf because ComponentSerialization.STREAM_CODEC needs registry access.
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenDropLocationEditPayload> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
                ComponentSerialization.STREAM_CODEC.encode(buf, p.name());
                ComponentSerialization.STREAM_CODEC.encode(buf, p.jobTitle());
                ByteBufCodecs.INT.encode(buf, p.settlementRgb());
                ByteBufCodecs.BOOL.encode(buf, p.seed());
            },
            buf -> new OpenDropLocationEditPayload(
                ByteBufCodecs.VAR_INT.decode(buf),
                ComponentSerialization.STREAM_CODEC.decode(buf),
                ComponentSerialization.STREAM_CODEC.decode(buf),
                ByteBufCodecs.INT.decode(buf),
                ByteBufCodecs.BOOL.decode(buf))
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
