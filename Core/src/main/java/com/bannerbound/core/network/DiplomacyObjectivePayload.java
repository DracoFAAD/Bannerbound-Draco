package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

@ApiStatus.Internal
public record DiplomacyObjectivePayload(boolean active, String title, String subtitle,
                                        BlockPos pos, int colorRgb)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DiplomacyObjectivePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "diplomacy_objective"));

    public static final StreamCodec<ByteBuf, DiplomacyObjectivePayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.BOOL, DiplomacyObjectivePayload::active,
            ByteBufCodecs.STRING_UTF8, DiplomacyObjectivePayload::title,
            ByteBufCodecs.STRING_UTF8, DiplomacyObjectivePayload::subtitle,
            BlockPos.STREAM_CODEC, DiplomacyObjectivePayload::pos,
            ByteBufCodecs.VAR_INT, DiplomacyObjectivePayload::colorRgb,
            DiplomacyObjectivePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
