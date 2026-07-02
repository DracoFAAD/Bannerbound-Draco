package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

@ApiStatus.Internal
public record DiplomacyActionPayload(int action, String targetSettlementId)
        implements CustomPacketPayload {
    public static final int DECLARE_WAR = 0;
    public static final int OFFER_PEACE = 1;
    public static final int TOGGLE_RALLY = 2;
    public static final int RAZE = 3;
    // City-state capture resolutions (raze reuses RAZE above). See CityStateWarManager.
    public static final int VASSAL = 4;
    public static final int ANNEX = 5;

    public static final CustomPacketPayload.Type<DiplomacyActionPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "diplomacy_action"));

    public static final StreamCodec<ByteBuf, DiplomacyActionPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DiplomacyActionPayload::action,
            ByteBufCodecs.STRING_UTF8, DiplomacyActionPayload::targetSettlementId,
            DiplomacyActionPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
