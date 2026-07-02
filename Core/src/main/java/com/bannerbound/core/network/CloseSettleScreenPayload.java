package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C signal: close the settlement-founding screen ({@code SettleScreen}) if the player has it
 * open. Broadcast to everyone the moment the last banner color is claimed — once the server is
 * full no founding can succeed, so any open founding menu must be dismissed.
 *
 * <p>Deliberately narrower than {@link CloseSettlementScreensPayload}: that one closes every
 * settlement screen (town hall, citizens, research, …) for a player whose settlement disbanded.
 * This one targets <em>only</em> the founding screen, so broadcasting it to the whole server
 * doesn't collateral-close established settlements' town-hall menus.
 */
@ApiStatus.Internal
public record CloseSettleScreenPayload() implements CustomPacketPayload {
    public static final CloseSettleScreenPayload INSTANCE = new CloseSettleScreenPayload();

    public static final CustomPacketPayload.Type<CloseSettleScreenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "close_settle_screen"));

    public static final StreamCodec<ByteBuf, CloseSettleScreenPayload> STREAM_CODEC =
        StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
