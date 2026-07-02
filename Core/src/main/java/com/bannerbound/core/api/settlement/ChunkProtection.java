package com.bannerbound.core.api.settlement;

import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

public final class ChunkProtection {
    private ChunkProtection() {
    }

    /**
     * Whether {@code actor} should be blocked from acting on {@code chunk}. Returns true if the
     * chunk is claimed by a settlement that is not the actor's settlement.
     *
     */
    public static boolean isProtected(SettlementData data, ChunkPos chunk, UUID actorId) {
        return !DiplomacyManager.canActInClaim(data, chunk, actorId);
    }

    /**
     * Whether {@code player} bypasses claim protection. Only op-level-2+ players can, and only when
     * {@code opsBypassClaimProtection} is enabled in the config — it defaults to OFF so protection
     * applies to everyone (a LAN world with cheats makes every player an op, which would otherwise
     * disable all grief protection). Admins can flip the config on for moderation.
     */
    public static boolean shouldBypass(ServerPlayer player) {
        return com.bannerbound.core.Config.OPS_BYPASS_CLAIM_PROTECTION.get() && player.hasPermissions(2);
    }
}
