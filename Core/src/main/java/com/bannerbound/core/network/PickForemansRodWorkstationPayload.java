package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server. Player picked a workstation type in the rod picker screen. Empty string =
 * "Clear selection". Server writes the chosen type to the held rod's data components (and clears
 * any existing selection from the registry if the empty-string clear path was taken).
 */
@ApiStatus.Internal
public record PickForemansRodWorkstationPayload(String workstationType) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PickForemansRodWorkstationPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "pick_foremans_rod_workstation"));

    public static final StreamCodec<ByteBuf, PickForemansRodWorkstationPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> ByteBufCodecs.STRING_UTF8.encode(buf, p.workstationType()),
        buf -> new PickForemansRodWorkstationPayload(ByteBufCodecs.STRING_UTF8.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
