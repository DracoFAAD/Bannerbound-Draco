package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C: a settlement-wide food warning broadcast to EVERY member. Drives a small on-screen HUD
 * banner (see {@code SettlementFoodWarningHudLayer}) so every player in the settlement — not just
 * whoever has a screen open — is told when food is running out.
 *
 * <p>{@code level} is the threshold bucket the settlement's food has crossed:
 * <ul>
 *   <li>{@link #LEVEL_OK} (0) — food is healthy; clears any banner.</li>
 *   <li>{@link #LEVEL_LOW} (1) — food below the low threshold (amber "Food running low").</li>
 *   <li>{@link #LEVEL_STARVING} (2) — food at zero with active consumption (red "STARVING").</li>
 * </ul>
 *
 * <p>Broadcast only on the once-per-second food tick AND only when the bucket actually changes
 * (tracked server-side), so it never spams packets.
 */
@ApiStatus.Internal
public record SettlementFoodWarningPayload(int level) implements CustomPacketPayload {
    public static final int LEVEL_OK = 0;
    public static final int LEVEL_LOW = 1;
    public static final int LEVEL_STARVING = 2;

    public static final CustomPacketPayload.Type<SettlementFoodWarningPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "settlement_food_warning"));

    public static final StreamCodec<ByteBuf, SettlementFoodWarningPayload> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> ByteBufCodecs.VAR_INT.encode(buf, p.level()),
            buf -> new SettlementFoodWarningPayload(ByteBufCodecs.VAR_INT.decode(buf))
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
