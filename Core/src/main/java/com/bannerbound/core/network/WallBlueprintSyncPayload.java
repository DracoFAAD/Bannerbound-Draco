package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client. The full wall blueprint for the receiving player's settlement: packed
 * {@code BlockPos.asLong()} positions paired with global block-state ids
 * ({@code Block.getId(state)} — decoded client-side via {@code Block.stateById}). Drives the
 * ghost renderer. Sent on construct/adapt/cancel and on login — plan changes are rare,
 * explicit player actions, so a full snapshot beats per-section diffing until proven otherwise
 * (WALLS_PLAN.md §E). An empty payload clears the client's blueprint.
 */
@ApiStatus.Internal
public record WallBlueprintSyncPayload(long[] positions, int[] stateIds) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<WallBlueprintSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "wall_blueprint_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WallBlueprintSyncPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.positions().length);
            for (long pos : p.positions()) buf.writeLong(pos);
            for (int id : p.stateIds()) buf.writeVarInt(id);
        },
        buf -> {
            int n = buf.readVarInt();
            long[] positions = new long[n];
            for (int i = 0; i < n; i++) positions[i] = buf.readLong();
            int[] stateIds = new int[n];
            for (int i = 0; i < n; i++) stateIds[i] = buf.readVarInt();
            return new WallBlueprintSyncPayload(positions, stateIds);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
