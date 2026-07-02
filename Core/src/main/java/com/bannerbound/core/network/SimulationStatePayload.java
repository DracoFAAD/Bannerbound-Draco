package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client snapshot for the throwaway {@code /bannerbound simulate} crowd-LOD stress test.
 * Deliberately tiny: the whole point of the prototype is that a believable thousands-strong crowd
 * costs <em>a handful of synced numbers</em> and is generated client-side, never per-pedestrian.
 *
 * <p>The client deterministically reconstructs the decorative crowd from {@link #seed}, anchored
 * around the town hall within {@link #radius} blocks (movers also cluster toward claimed chunks the
 * client already knows from {@code ClaimSyncPayload}). {@link #believedPopulation} is the "headcount"
 * shown on the HUD; the rendered mover count is view-relative and capped, never this number.
 *
 * <p>Sent on start, on stop ({@code active=false}), and roughly once per second while running.
 */
@ApiStatus.Internal
public record SimulationStatePayload(
    boolean active,
    String settlementId,
    int townHallX,
    int townHallY,
    int townHallZ,
    int radius,
    int believedPopulation,
    int realCount,
    long seed,
    int remainingTicks,
    float serverMsPerTick,
    int eraOrdinal,
    /** True only for the /bannerbound simulate debug session (drives the on-screen debug overlay).
     *  False for the real ambient settlement crowd, which renders movers but shows no debug HUD. */
    boolean debug
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SimulationStatePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "simulation_state"));

    public static final StreamCodec<ByteBuf, SimulationStatePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.BOOL.encode(buf, p.active());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.settlementId());
            ByteBufCodecs.INT.encode(buf, p.townHallX());
            ByteBufCodecs.INT.encode(buf, p.townHallY());
            ByteBufCodecs.INT.encode(buf, p.townHallZ());
            ByteBufCodecs.INT.encode(buf, p.radius());
            ByteBufCodecs.VAR_INT.encode(buf, p.believedPopulation());
            ByteBufCodecs.VAR_INT.encode(buf, p.realCount());
            ByteBufCodecs.VAR_LONG.encode(buf, p.seed());
            ByteBufCodecs.VAR_INT.encode(buf, p.remainingTicks());
            ByteBufCodecs.FLOAT.encode(buf, p.serverMsPerTick());
            ByteBufCodecs.VAR_INT.encode(buf, p.eraOrdinal());
            ByteBufCodecs.BOOL.encode(buf, p.debug());
        },
        buf -> new SimulationStatePayload(
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.INT.decode(buf),
            ByteBufCodecs.INT.decode(buf),
            ByteBufCodecs.INT.decode(buf),
            ByteBufCodecs.INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_LONG.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.FLOAT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.BOOL.decode(buf)
        )
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
