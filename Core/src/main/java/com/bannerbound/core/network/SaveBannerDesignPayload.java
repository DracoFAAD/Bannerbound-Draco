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
 * C→S: save the Heraldry banner design — parallel lists of pattern registry ids and
 * {@code DyeColor} ids, bottom-up layer order. The server re-validates everything (membership,
 * Heraldry flag, layer cap, point budget, pattern ids) before storing; the client lists are a
 * proposal, never authority.
 */
@ApiStatus.Internal
public record SaveBannerDesignPayload(List<String> patterns, List<Integer> colors)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SaveBannerDesignPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "save_banner_design"));

    public static final StreamCodec<ByteBuf, SaveBannerDesignPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), SaveBannerDesignPayload::patterns,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), SaveBannerDesignPayload::colors,
            SaveBannerDesignPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
