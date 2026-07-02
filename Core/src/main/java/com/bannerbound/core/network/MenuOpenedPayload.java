package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: the player opened one of the mod's menus (the {@code menuId} names which, e.g.
 * {@code "chronicle"}). Fires a {@code menu_opened} Chronicle trigger so tutorial objectives like
 * "open the Chronicle" can complete just from opening the screen — no entry has to be read.
 */
@ApiStatus.Internal
public record MenuOpenedPayload(String menuId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<MenuOpenedPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "menu_opened"));

    public static final StreamCodec<ByteBuf, MenuOpenedPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8,
        MenuOpenedPayload::menuId,
        MenuOpenedPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
