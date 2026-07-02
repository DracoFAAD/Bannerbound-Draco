package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: set a workshop's min-stock row from its menu's Stock tab. {@code value ≤ 0}
 * clears the row. The minimum is SETTLEMENT-WIDE (stockpiles + workshop storages, quality ignored):
 * the workers craft this output only while the census is below it — and once ANY row is set, the
 * workshop only crafts configured outputs (the governor; see CRAFTER_PLAN.md Phase 3).
 *
 * @param workshopId the workshop's id (UUID string)
 * @param itemId     the output item's registry id
 * @param value      the new settlement-wide minimum (≤0 = off)
 */
@ApiStatus.Internal
public record SetWorkshopMinStockPayload(String workshopId, int itemId, int value)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SetWorkshopMinStockPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "set_workshop_min_stock"));

    public static final StreamCodec<ByteBuf, SetWorkshopMinStockPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SetWorkshopMinStockPayload::workshopId,
            ByteBufCodecs.VAR_INT, SetWorkshopMinStockPayload::itemId,
            ByteBufCodecs.VAR_INT, SetWorkshopMinStockPayload::value,
            SetWorkshopMinStockPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
