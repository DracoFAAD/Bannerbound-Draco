package com.bannerbound.core.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C: open (or refresh) the Heraldry banner editor. Carries the saved design (parallel
 * pattern-id / color-id lists, bottom-up), the faction base color, and the settlement's total
 * EARNED Heraldry points — the client computes "available" live as
 * {@code pointsEarned − workingLayers.size()} while the player edits.
 */
@ApiStatus.Internal
public record OpenBannerEditorPayload(int baseColorOrdinal, int pointsEarned,
                                      List<String> patterns, List<Integer> colors)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OpenBannerEditorPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "open_banner_editor"));

    public static final StreamCodec<ByteBuf, OpenBannerEditorPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenBannerEditorPayload::baseColorOrdinal,
            ByteBufCodecs.VAR_INT, OpenBannerEditorPayload::pointsEarned,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), OpenBannerEditorPayload::patterns,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), OpenBannerEditorPayload::colors,
            OpenBannerEditorPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
