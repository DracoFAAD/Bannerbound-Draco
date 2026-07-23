package com.bannerbound.core.civpm.utils;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

public class CPMPacketUtils {
    public static final StreamCodec<ByteBuf, Long> LONG = StreamCodec.of(
            ByteBuf::writeLong,
            ByteBuf::readLong
    );
}
