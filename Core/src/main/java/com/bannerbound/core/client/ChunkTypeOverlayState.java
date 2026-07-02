package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side TTL holder for the {@code /bannerbound chunktype <radius>} debug overlay — the grid of
 * chunk-resource ordinals to float icons over, centred on a chunk, until it expires. Drawn by
 * {@link ChunkTypeOverlayRenderer}.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ChunkTypeOverlayState {
    private static int centerX;
    private static int centerZ;
    private static int radius;
    private static byte[] ordinals = new byte[0];
    private static long expiryGameTime;

    private ChunkTypeOverlayState() {
    }

    public static void show(int cx, int cz, int r, byte[] ord, int durationTicks) {
        Minecraft mc = Minecraft.getInstance();
        long now = mc.level != null ? mc.level.getGameTime() : 0L;
        centerX = cx;
        centerZ = cz;
        radius = r;
        ordinals = ord;
        expiryGameTime = now + Math.max(0, durationTicks);
    }

    public static boolean isActive(long now) {
        return now < expiryGameTime && ordinals.length > 0;
    }

    public static int centerX() { return centerX; }
    public static int centerZ() { return centerZ; }
    public static int radius() { return radius; }
    public static byte[] ordinals() { return ordinals; }
}
