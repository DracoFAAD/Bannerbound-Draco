package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: propose a palette change — drop {@code addPaletteId} into slot
 * {@code slotIndex} and/or remove {@code removePaletteId}. Empty strings mean "no add" / "no
 * remove". Twin of {@link ProposePolicyChangePayload}: Council opens a confirm vote, a Chiefdom
 * chief enacts immediately, a Chiefdom non-chief should send {@link SuggestPalettePayload}.
 */
@ApiStatus.Internal
public record ProposePaletteChangePayload(int slotIndex, String addPaletteId, String removePaletteId)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ProposePaletteChangePayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "propose_palette_change"));

    public static final StreamCodec<ByteBuf, ProposePaletteChangePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.slotIndex());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.addPaletteId() == null ? "" : p.addPaletteId());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.removePaletteId() == null ? "" : p.removePaletteId());
        },
        buf -> new ProposePaletteChangePayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
