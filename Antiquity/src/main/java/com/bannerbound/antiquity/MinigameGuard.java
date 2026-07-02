package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * Shared server-side validation for the crafting-minigame COMPLETE/COMMIT paths: the station must
 * still be loaded and in reach, the client-reported step count must match what the server observed,
 * and a conservative minimum game-time must have elapsed for the number of steps — so a modified
 * client can neither complete instantly nor forge scores outside their legal range.
 */
@ApiStatus.Internal
final class MinigameGuard {
    private MinigameGuard() {}

    /** Same reach rule as the in-world queue edits: chunk loaded and within 8 blocks. */
    static boolean stationInReach(ServerPlayer player, BlockPos pos) {
        return player.level().isLoaded(pos)
            && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    /** True when at least {@code steps * minTicksPerStep} game ticks passed since the session opened. */
    static boolean elapsedOk(ServerPlayer player, long startGameTime, int steps, int minTicksPerStep) {
        return player.serverLevel().getGameTime() - startGameTime >= (long) steps * minTicksPerStep;
    }

    /** Clamps a client-reported per-step score into its legal 0–100 range. */
    static int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }
}
