package com.bannerbound.core.network;

import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: move a citizen into or out of the home with id {@code homeId}.
 *
 * <p>When {@code assign} is true the citizen is added as a resident — and, because one citizen
 * lives in exactly one home at a time, any prior residency is dropped first by the server. When
 * {@code assign} is false the citizen is removed from this home (no-op if they weren't a resident
 * here).
 *
 * <p>{@code fromHousePanel} selects which screen the server refreshes afterwards: false (the
 * resident picker) re-sends {@link HomeCitizenListPayload}; true (the House status panel's own
 * inline unassign) re-sends {@link OpenHouseStatusPayload}. Either way the originating screen
 * refreshes in place — no separate "I changed the assignment" payload is needed.
 */
@ApiStatus.Internal
public record AssignCitizenToHomePayload(UUID homeId, UUID citizenId, boolean assign, boolean fromHousePanel)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AssignCitizenToHomePayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "assign_citizen_to_home"));

    public static final StreamCodec<ByteBuf, AssignCitizenToHomePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.homeId().getMostSignificantBits());
            buf.writeLong(p.homeId().getLeastSignificantBits());
            buf.writeLong(p.citizenId().getMostSignificantBits());
            buf.writeLong(p.citizenId().getLeastSignificantBits());
            buf.writeBoolean(p.assign());
            buf.writeBoolean(p.fromHousePanel());
        },
        buf -> new AssignCitizenToHomePayload(
            new UUID(buf.readLong(), buf.readLong()),
            new UUID(buf.readLong(), buf.readLong()),
            buf.readBoolean(),
            buf.readBoolean()
        )
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
