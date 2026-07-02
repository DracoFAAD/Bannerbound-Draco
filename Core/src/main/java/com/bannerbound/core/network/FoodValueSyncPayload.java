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
 * Server → client: the base food-value table loaded from {@code data/<ns>/food_values/*.json}.
 * Two parallel lists (item id, food value). Re-sent on datapack reload so the green "Food value"
 * tooltip line stays accurate.
 */
@ApiStatus.Internal
public record FoodValueSyncPayload(List<String> itemIds, List<Float> values)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<FoodValueSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "food_value_sync"));

    public static final StreamCodec<ByteBuf, FoodValueSyncPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), FoodValueSyncPayload::itemIds,
        ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list()), FoodValueSyncPayload::values,
        FoodValueSyncPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
