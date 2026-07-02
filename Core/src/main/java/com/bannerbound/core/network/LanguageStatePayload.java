package com.bannerbound.core.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

@ApiStatus.Internal
public record LanguageStatePayload(boolean enabled, long seed, List<String> conceptOverrides)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<LanguageStatePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "language_state"));

    public LanguageStatePayload {
        conceptOverrides = conceptOverrides == null ? List.of() : List.copyOf(conceptOverrides);
    }

    public static final StreamCodec<ByteBuf, LanguageStatePayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.BOOL, LanguageStatePayload::enabled,
            ByteBufCodecs.VAR_LONG, LanguageStatePayload::seed,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), LanguageStatePayload::conceptOverrides,
            LanguageStatePayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
