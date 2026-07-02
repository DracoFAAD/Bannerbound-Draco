package com.bannerbound.core.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client snapshot of the settlement's current unrest warnings (homelessness, looming
 * strikes, brewing coup, …). Each entry is an already-styled {@link Component} produced by
 * {@link com.bannerbound.core.api.settlement.SettlementManager#settlementWarnings}; an empty list
 * means "all clear". Sent alongside the town-hall open so {@code TownHallScreen} can render them
 * without recomputing.
 *
 * <p>The {@link Component} list piggy-backs on {@link ComponentSerialization#STREAM_CODEC} for
 * style preservation, same trick as {@link OpenHouseStatusPayload}.
 */
@ApiStatus.Internal
public record SettlementWarningsPayload(List<Component> warnings) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SettlementWarningsPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "settlement_warnings"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SettlementWarningsPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.warnings().size());
            for (Component c : p.warnings()) {
                ComponentSerialization.STREAM_CODEC.encode(buf, c);
            }
        },
        buf -> {
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            List<Component> warnings = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                warnings.add(ComponentSerialization.STREAM_CODEC.decode(buf));
            }
            return new SettlementWarningsPayload(warnings);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
