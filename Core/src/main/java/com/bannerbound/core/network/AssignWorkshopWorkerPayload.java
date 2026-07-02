package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: assign ({@code assign=true}) or unassign a citizen to/from a workshop, from the
 * workshop menu's worker rows. The server re-checks capacity + the crafter research unlock, writes
 * BOTH sides (citizen job/binding + workshop roster), and re-sends the menu snapshot so the screen
 * refreshes.
 */
@ApiStatus.Internal
public record AssignWorkshopWorkerPayload(String workshopId, String citizenId, boolean assign,
                                          String jobTypeId)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<AssignWorkshopWorkerPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "assign_workshop_worker"));

    public static final StreamCodec<ByteBuf, AssignWorkshopWorkerPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, AssignWorkshopWorkerPayload::workshopId,
            ByteBufCodecs.STRING_UTF8, AssignWorkshopWorkerPayload::citizenId,
            ByteBufCodecs.BOOL, AssignWorkshopWorkerPayload::assign,
            ByteBufCodecs.STRING_UTF8, AssignWorkshopWorkerPayload::jobTypeId,
            AssignWorkshopWorkerPayload::new
        );

    public AssignWorkshopWorkerPayload(String workshopId, String citizenId, boolean assign) {
        this(workshopId, citizenId, assign, "");
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
