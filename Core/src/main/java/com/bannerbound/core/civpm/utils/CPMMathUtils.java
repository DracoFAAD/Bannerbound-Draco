package com.bannerbound.core.civpm.utils;

import net.minecraft.util.Mth;

public class CPMMathUtils {
    public static class CPM2DUtils {
        public static long pack(int x, int y) {
            return ((long) x << 32) | (y & 0xFFFFFFFFL);
        }

        public static int unpackX(long packed) {
            return (int) (packed >> 32);
        }

        public static int unpackY(long packed) {
            return (int) packed;
        }

        public static long packX(long pos, int newX) {
            return ((long) newX << 32) | (pos & 0xFFFFFFFFL);
        }

        public static long packY(long pos, int newY) {
            return (pos & 0xFFFFFFFF00000000L) | (newY & 0xFFFFFFFFL);
        }

        public static long packBlockToRegion(int x, int y) {
            return pack(Mth.floor(x / 48.0), Mth.floor(y / 48.0));
        }
    }
}
