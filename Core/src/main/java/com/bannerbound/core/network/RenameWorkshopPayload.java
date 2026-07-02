package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: rename a workshop from its menu. An empty {@code name} resets the workshop to
 * its derived-type display name. The server clamps length and requires the sender to be a member
 * of the owning settlement.
 *
 * @param workshopId the workshop's id (UUID string from {@code OpenWorkshopMenuPayload})
 * @param name       the new custom name ("" = reset to derived type)
 */
@ApiStatus.Internal
public record RenameWorkshopPayload(String workshopId, String name) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RenameWorkshopPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "rename_workshop"));

    public static final StreamCodec<ByteBuf, RenameWorkshopPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RenameWorkshopPayload::workshopId,
            ByteBufCodecs.STRING_UTF8, RenameWorkshopPayload::name,
            RenameWorkshopPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
