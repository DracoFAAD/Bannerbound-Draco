package com.bannerbound.core.api.research;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Maps a real ore block ID (e.g. {@code minecraft:iron_ore}) to a visual stand-in
 * (e.g. {@code minecraft:stone}) gated by a research flag. Until the player's settlement
 * has the flag, the ore renders as the disguise and drops the disguise's item.
 */
public record OreDisguise(String oreId, String disguiseId, String flag) {
    public static final StreamCodec<ByteBuf, OreDisguise> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, OreDisguise::oreId,
        ByteBufCodecs.STRING_UTF8, OreDisguise::disguiseId,
        ByteBufCodecs.STRING_UTF8, OreDisguise::flag,
        OreDisguise::new
    );
}
