package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: pin an assigned worker to a specific station family within the workshop (the
 * Workers tab's per-worker station chooser). A mixed workshop holds several station families (e.g.
 * a fletching station and a crafting stone); this lets the player decide who works which — one a
 * fletcher, one a general crafter — instead of letting them self-assign by spare capacity.
 *
 * <p>{@code stationTypeId} is a registered workshop type id; the empty string means "Any" — clear
 * the pin and let the worker auto-pick a station with work (the legacy behaviour).
 *
 * @param workshopId    the workshop's id (UUID string)
 * @param citizenId     the assigned worker's citizen UUID (string)
 * @param stationTypeId the station family to pin the worker to ("" = Any / auto-pick)
 */
@ApiStatus.Internal
public record SetWorkshopWorkerStationPayload(String workshopId, String citizenId,
                                              String stationTypeId)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SetWorkshopWorkerStationPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "set_workshop_worker_station"));

    public static final StreamCodec<ByteBuf, SetWorkshopWorkerStationPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SetWorkshopWorkerStationPayload::workshopId,
            ByteBufCodecs.STRING_UTF8, SetWorkshopWorkerStationPayload::citizenId,
            ByteBufCodecs.STRING_UTF8, SetWorkshopWorkerStationPayload::stationTypeId,
            SetWorkshopWorkerStationPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
