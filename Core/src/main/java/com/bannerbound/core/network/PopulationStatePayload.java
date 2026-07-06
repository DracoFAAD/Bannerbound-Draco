package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client snapshot of a settlement's immigration state. Broadcast once per second to
 * every member so the Town Hall screen can render live food/culture per second, stored progress,
 * the next-citizen cost, the (possibly-research-boosted) stockpile capacity, and the
 * {@code population / populationMax} display that drives the lovemaking gate.
 */
@ApiStatus.Internal
public record PopulationStatePayload(
    String settlementId,
    int population,
    int populationMax,
    double foodPerSecond,
    double culturePerSecond,
    double foodStored,
    double cultureStored,
    double storedFoodValue,
    double storedFoodPerSecond,
    double nextFoodCost,
    double nextCultureCost,
    double foodCap,
    double cultureCap,
    /** Ordinal of {@link com.bannerbound.core.api.settlement.Settlement.Government} — drives
     *  the client-side title (Tribe once a government is enacted, regardless of size). */
    int governmentOrdinal,
    /** Player-member UUIDs of this settlement, so the client can tell whether a given player (e.g. the
     *  one who laced a food item) currently shares the viewer's settlement. */
    java.util.List<java.util.UUID> members,
    /** Food eaten per second by the citizens (0 under anarchy). The drain the food line must beat. */
    double foodConsumptionPerSecond,
    /** Per-source production rate (food-stuff/sec) keyed by source ("farming"/"fishing"/"livestock"),
     *  so the Town Hall food tooltip can show where the settlement's food is coming from. */
    java.util.Map<String, Double> foodSourceRates,
    /** Culture/sec contributed by claimed-territory block appeal — already included in
     *  {@code culturePerSecond}. Signed: attractive chunks add, ugly chunks subtract. Broken out so
     *  the Town Hall culture tooltip can show how much of the culture rate comes from territory appeal. */
    double appealCulturePerSecond
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PopulationStatePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "population_state"));

    public static final StreamCodec<ByteBuf, PopulationStatePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, p.settlementId());
            ByteBufCodecs.VAR_INT.encode(buf, p.population());
            ByteBufCodecs.VAR_INT.encode(buf, p.populationMax());
            ByteBufCodecs.DOUBLE.encode(buf, p.foodPerSecond());
            ByteBufCodecs.DOUBLE.encode(buf, p.culturePerSecond());
            ByteBufCodecs.DOUBLE.encode(buf, p.foodStored());
            ByteBufCodecs.DOUBLE.encode(buf, p.cultureStored());
            ByteBufCodecs.DOUBLE.encode(buf, p.storedFoodValue());
            ByteBufCodecs.DOUBLE.encode(buf, p.storedFoodPerSecond());
            ByteBufCodecs.DOUBLE.encode(buf, p.nextFoodCost());
            ByteBufCodecs.DOUBLE.encode(buf, p.nextCultureCost());
            ByteBufCodecs.DOUBLE.encode(buf, p.foodCap());
            ByteBufCodecs.DOUBLE.encode(buf, p.cultureCap());
            ByteBufCodecs.VAR_INT.encode(buf, p.governmentOrdinal());
            ByteBufCodecs.VAR_INT.encode(buf, p.members().size());
            for (java.util.UUID m : p.members()) {
                net.minecraft.core.UUIDUtil.STREAM_CODEC.encode(buf, m);
            }
            ByteBufCodecs.DOUBLE.encode(buf, p.foodConsumptionPerSecond());
            ByteBufCodecs.VAR_INT.encode(buf, p.foodSourceRates().size());
            for (java.util.Map.Entry<String, Double> e : p.foodSourceRates().entrySet()) {
                ByteBufCodecs.STRING_UTF8.encode(buf, e.getKey());
                ByteBufCodecs.DOUBLE.encode(buf, e.getValue());
            }
            ByteBufCodecs.DOUBLE.encode(buf, p.appealCulturePerSecond());
        },
        buf -> {
            String settlementId = ByteBufCodecs.STRING_UTF8.decode(buf);
            int population = ByteBufCodecs.VAR_INT.decode(buf);
            int populationMax = ByteBufCodecs.VAR_INT.decode(buf);
            double foodPerSecond = ByteBufCodecs.DOUBLE.decode(buf);
            double culturePerSecond = ByteBufCodecs.DOUBLE.decode(buf);
            double foodStored = ByteBufCodecs.DOUBLE.decode(buf);
            double cultureStored = ByteBufCodecs.DOUBLE.decode(buf);
            double storedFoodValue = ByteBufCodecs.DOUBLE.decode(buf);
            double storedFoodPerSecond = ByteBufCodecs.DOUBLE.decode(buf);
            double nextFoodCost = ByteBufCodecs.DOUBLE.decode(buf);
            double nextCultureCost = ByteBufCodecs.DOUBLE.decode(buf);
            double foodCap = ByteBufCodecs.DOUBLE.decode(buf);
            double cultureCap = ByteBufCodecs.DOUBLE.decode(buf);
            int governmentOrdinal = ByteBufCodecs.VAR_INT.decode(buf);
            int memberCount = ByteBufCodecs.VAR_INT.decode(buf);
            java.util.List<java.util.UUID> members = new java.util.ArrayList<>(memberCount);
            for (int i = 0; i < memberCount; i++) {
                members.add(net.minecraft.core.UUIDUtil.STREAM_CODEC.decode(buf));
            }
            double foodConsumptionPerSecond = ByteBufCodecs.DOUBLE.decode(buf);
            int rateCount = ByteBufCodecs.VAR_INT.decode(buf);
            java.util.Map<String, Double> foodSourceRates = new java.util.LinkedHashMap<>();
            for (int i = 0; i < rateCount; i++) {
                String key = ByteBufCodecs.STRING_UTF8.decode(buf);
                foodSourceRates.put(key, ByteBufCodecs.DOUBLE.decode(buf));
            }
            double appealCulturePerSecond = ByteBufCodecs.DOUBLE.decode(buf);
            return new PopulationStatePayload(settlementId, population, populationMax, foodPerSecond,
                culturePerSecond, foodStored, cultureStored, storedFoodValue, storedFoodPerSecond,
                nextFoodCost, nextCultureCost, foodCap, cultureCap, governmentOrdinal, members,
                foodConsumptionPerSecond, foodSourceRates, appealCulturePerSecond);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
