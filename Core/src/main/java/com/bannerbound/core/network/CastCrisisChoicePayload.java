package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: choose or vote for a crisis response path. */
@ApiStatus.Internal
public record CastCrisisChoicePayload(String choiceId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CastCrisisChoicePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "cast_crisis_choice"));

    public static final StreamCodec<ByteBuf, CastCrisisChoicePayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, CastCrisisChoicePayload::choiceId,
        CastCrisisChoicePayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
