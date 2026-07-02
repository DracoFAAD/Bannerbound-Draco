package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.entity.SpearProjectile;
import com.bannerbound.antiquity.entity.SpearedFishEntity;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Spear fishing's research gate and reel-in target resolution. The rope-tethered throw + reel-in is
 * gated behind {@link #FLAG} (a per-settlement research unlock); spearing a fish still produces the
 * floating catch without it (that's purely cosmetic). The flag id is a constant here so the Research
 * Tree Editor auto-discovers it (matches {@code FisherCatchTable.FLAG_TREASURE_FISHING}).
 */
@ApiStatus.Internal
public final class SpearFishing {
    /** Research flag that unlocks the rope-tethered spear + reel-in. */
    public static final String FLAG = "bannerbound.unlock_spear_fishing";
    /** How far (blocks) a reel-in scan reaches for the player's tethered spear/catch. */
    private static final double REEL_RANGE = 48.0;

    private SpearFishing() {}

    /** Whether {@code player}'s settlement has researched spear fishing. Mirrors AnimalBreedingGate. */
    public static boolean unlocked(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        MinecraftServer server = serverPlayer.getServer();
        if (server == null) {
            return false;
        }
        try {
            SettlementData data = SettlementData.get(server.overworld());
            Settlement settlement = data.getByPlayer(serverPlayer.getUUID());
            return ResearchManager.hasFlag(settlement, FLAG);
        } catch (Exception ex) {
            return false; // no settlement / not loaded → treat as not unlocked
        }
    }

    /**
     * Find {@code player}'s nearest rope-tethered catch or spear and start reeling it in. Returns true
     * if one was found (so the caller can consume the interaction). Server-side only.
     */
    public static boolean startReel(Player player) {
        Level level = player.level();
        if (level.isClientSide) {
            return false;
        }
        AABB area = player.getBoundingBox().inflate(REEL_RANGE);
        Entity best = null;
        double bestSq = Double.MAX_VALUE;
        for (SpearedFishEntity catch_ : level.getEntitiesOfClass(SpearedFishEntity.class, area,
                e -> e.isTetheredTo(player))) {
            double d = catch_.distanceToSqr(player);
            if (d < bestSq) {
                bestSq = d;
                best = catch_;
            }
        }
        for (SpearProjectile spear : level.getEntitiesOfClass(SpearProjectile.class, area,
                e -> e.isRopeTethered() && isOwner(e, player))) {
            double d = spear.distanceToSqr(player);
            if (d < bestSq) {
                bestSq = d;
                best = spear;
            }
        }
        if (best instanceof SpearedFishEntity catch_) {
            catch_.startReeling();
        } else if (best instanceof SpearProjectile spear) {
            spear.startReeling();
        } else {
            return false;
        }
        // The yank of the rope as it pulls in (0.8–1.0 pitch — higher sounds off).
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            BannerboundAntiquity.SPEAR_REEL_SOUND.get(), SoundSource.PLAYERS,
            0.9F, 0.8F + player.getRandom().nextFloat() * 0.2F);
        return true;
    }

    /** Read-only check (works on either side) for whether {@code player} has a tethered spear/catch
     *  nearby — the client uses it to decide whether an empty-hand reel-click is worth sending. */
    public static boolean hasTether(Player player) {
        Level level = player.level();
        AABB area = player.getBoundingBox().inflate(REEL_RANGE);
        if (!level.getEntitiesOfClass(SpearedFishEntity.class, area, e -> e.isTetheredTo(player)).isEmpty()) {
            return true;
        }
        return !level.getEntitiesOfClass(SpearProjectile.class, area,
            e -> e.isRopeTethered() && isOwner(e, player)).isEmpty();
    }

    private static boolean isOwner(SpearProjectile spear, Player player) {
        Entity owner = spear.getOwner();
        return owner != null && owner.getUUID().equals(player.getUUID());
    }
}
