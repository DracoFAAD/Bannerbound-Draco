package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: set an output's queued-order count from the workshop menu's Stock tab.
 * {@code value ≤ 0} clears the row. Orders are an explicit FIFO queue: they OUTRANK and ignore
 * the min-stock governor (a queued item crafts regardless of configured minimums), and each
 * finished craft of the item decrements the order by one. An order whose ingredients are missing
 * is skipped, never blocking the rest of the queue.
 *
 * @param workshopId the workshop's id (UUID string)
 * @param itemId     the output item's registry id
 * @param value      the new queued count (≤0 = cancel the order)
 */
@ApiStatus.Internal
public record SetWorkshopOrderPayload(String workshopId, int itemId, int value)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SetWorkshopOrderPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "set_workshop_order"));

    public static final StreamCodec<ByteBuf, SetWorkshopOrderPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SetWorkshopOrderPayload::workshopId,
            ByteBufCodecs.VAR_INT, SetWorkshopOrderPayload::itemId,
            ByteBufCodecs.VAR_INT, SetWorkshopOrderPayload::value,
            SetWorkshopOrderPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
