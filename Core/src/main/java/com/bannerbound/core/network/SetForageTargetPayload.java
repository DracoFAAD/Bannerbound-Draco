package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C→S: toggle one of a forager citizen's gather categories on/off from the Job tab picker.
 *  {@code categoryOrdinal} is the {@link com.bannerbound.core.api.forager.ForageCategory} ordinal. */
@ApiStatus.Internal
public record SetForageTargetPayload(int entityId, int categoryOrdinal, boolean enabled)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SetForageTargetPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "set_forage_target"));

    public static final StreamCodec<ByteBuf, SetForageTargetPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
            ByteBufCodecs.VAR_INT.encode(buf, p.categoryOrdinal());
            ByteBufCodecs.BOOL.encode(buf, p.enabled());
        },
        buf -> new SetForageTargetPayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.BOOL.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
