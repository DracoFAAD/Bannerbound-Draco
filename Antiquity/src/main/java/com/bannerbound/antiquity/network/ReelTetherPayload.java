package com.bannerbound.antiquity.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: the player shift-right-clicked to reel in their rope-tethered spear / speared-fish catch
 * with <b>empty hands</b> (the held-rope case is handled directly by {@code FiberRopeItem.use}; this
 * exists because vanilla doesn't forward an empty-hand right-click to the server). No fields — the
 * actor is the context's player; the server resolves their tethered entity (see {@code SpearFishing}).
 */
@ApiStatus.Internal
public record ReelTetherPayload() implements CustomPacketPayload {
    public static final ReelTetherPayload INSTANCE = new ReelTetherPayload();

    public static final CustomPacketPayload.Type<ReelTetherPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundAntiquity.MODID, "reel_tether"));

    public static final StreamCodec<ByteBuf, ReelTetherPayload> STREAM_CODEC =
        StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
