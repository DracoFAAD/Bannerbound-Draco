package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * Soft lock for farmers, parallel to {@link DiggerBlockClaimRegistry}. Separate registry so a
 * farmer and a digger don't accidentally fight over the same coordinate (cross-type overlap is
 * already prevented at selection-commit time by the rod's overlap check, but defense-in-depth).
 */
@ApiStatus.Internal
public final class FarmerBlockClaimRegistry {
    private static final Map<BlockPos, UUID> CLAIMS = new HashMap<>();
    private static final Map<UUID, BlockPos> BY_CITIZEN = new HashMap<>();

    private FarmerBlockClaimRegistry() {
    }

    public static synchronized boolean tryClaim(BlockPos pos, UUID citizenId) {
        BlockPos existing = BY_CITIZEN.get(citizenId);
        if (existing != null && !existing.equals(pos)) {
            CLAIMS.remove(existing);
        }
        UUID holder = CLAIMS.get(pos);
        if (holder != null && !holder.equals(citizenId)) {
            return false;
        }
        CLAIMS.put(pos.immutable(), citizenId);
        BY_CITIZEN.put(citizenId, pos.immutable());
        return true;
    }

    public static synchronized boolean isClaimedByOther(BlockPos pos, UUID citizenId) {
        UUID holder = CLAIMS.get(pos);
        return holder != null && !holder.equals(citizenId);
    }

    public static synchronized void release(UUID citizenId) {
        BlockPos held = BY_CITIZEN.remove(citizenId);
        if (held != null) CLAIMS.remove(held);
    }

    public static synchronized void releaseBlock(BlockPos pos) {
        UUID holder = CLAIMS.remove(pos);
        if (holder != null) {
            BlockPos byHolder = BY_CITIZEN.get(holder);
            if (byHolder != null && byHolder.equals(pos)) BY_CITIZEN.remove(holder);
        }
    }
}
