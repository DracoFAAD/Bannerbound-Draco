package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C→S: the player clicked the Town Hall Labor tab's "Preferred storage" button. The server puts
 *  them into the same click-a-block edit mode the per-citizen drop-off uses (a settlement-level
 *  sentinel), then their next block right-click sets the settlement's preferred-storage depot. */
@ApiStatus.Internal
public record BeginEditPreferredStoragePayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BeginEditPreferredStoragePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "begin_edit_preferred_storage"));

    public static final StreamCodec<ByteBuf, BeginEditPreferredStoragePayload> STREAM_CODEC =
        StreamCodec.unit(new BeginEditPreferredStoragePayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
