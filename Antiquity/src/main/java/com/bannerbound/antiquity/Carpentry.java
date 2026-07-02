package com.bannerbound.antiquity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.WoodworkingTableBlockEntity;
import com.bannerbound.antiquity.network.CarpentryActionPayload;
import com.bannerbound.antiquity.network.OpenCarpentrySawPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-authoritative driver for the carpenter's-table saw minigame. Holds one in-flight session
 * per player (table pos), opens the client minigame, and on completion outputs the whole build list
 * while consuming only the logs it cost. The minigame is non-skill, so there's no quality roll and
 * no anti-reroll forfeit: a cancel leaves the budget + list exactly as they were.
 */
@ApiStatus.Internal
public final class Carpentry {
    private Carpentry() {
    }

    /** Saw strokes for the smallest batch, and the cap for the largest — a bigger wood budget means a
     *  longer saw (the only "size matters" lever; it is not scored), capped so it never gets tedious. */
    private static final int MIN_STROKES = 4;
    private static final int MAX_STROKES = 28;

    private record Session(BlockPos pos, int strokes, long startTime) {}

    /** Active sessions keyed by player UUID (server thread only). */
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    /** Opens the saw minigame for {@code player} on the table at {@code pos} (list already non-empty). */
    public static void startSawing(ServerPlayer player, BlockPos pos, WoodworkingTableBlockEntity be) {
        int strokes = strokesFor(be);
        SESSIONS.put(player.getUUID(),
            new Session(pos.immutable(), strokes, player.serverLevel().getGameTime()));
        // Claim the table so a crafter citizen skips it mid-minigame (mirror of the NPC craft lock).
        com.bannerbound.core.api.workshop.WorkBlockLocks.lock(pos, player.getUUID());
        PacketDistributor.sendToPlayer(player, new OpenCarpentrySawPayload(pos, strokes));
    }

    /** Strokes scale with the total MATERIALS the batch consumes (logs + planks + sticks), clamped to
     *  [MIN, MAX] — the more budget you spend, the longer you saw. */
    private static int strokesFor(WoodworkingTableBlockEntity be) {
        int materials = 0;
        for (WoodworkingTableBlockEntity.ListEntry e : be.getBuildList()) {
            for (com.bannerbound.antiquity.carpentry.Cost c : e.costs()) {
                materials += e.units() * c.perUnit();
            }
        }
        return Math.max(MIN_STROKES, Math.min(MAX_STROKES, MIN_STROKES + materials / 2));
    }

    /** Handles a client carpenter's-table action (saw COMPLETE/CANCEL, or an in-world queue removal). */
    public static void handleAction(ServerPlayer player, CarpentryActionPayload payload) {
        // Queue removal is a direct in-world edit — no saw session involved.
        if (payload.action() == CarpentryActionPayload.REMOVE_QUEUE) {
            BlockPos pos = payload.pos();
            if (!player.level().isLoaded(pos)) return;
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) return;
            if (com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())) return;
            if (player.serverLevel().getBlockEntity(pos) instanceof WoodworkingTableBlockEntity be) {
                if (be.removeEntryAt(payload.index())) {
                    player.serverLevel().playSound(null, pos,
                        net.minecraft.sounds.SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6F, 1.0F);
                }
            }
            return;
        }

        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.pos().equals(payload.pos())) return;
        BlockPos sessionPos = session.pos();
        ServerLevel level = player.serverLevel();
        if (payload.action() == CarpentryActionPayload.COMPLETE
                && MinigameGuard.stationInReach(player, sessionPos)
                && MinigameGuard.elapsedOk(player, session.startTime(), session.strokes(), 4)
                && level.getBlockEntity(sessionPos) instanceof WoodworkingTableBlockEntity be) {
            be.completeAndOutput(level);
            level.playSound(null, sessionPos, BannerboundAntiquity.SAW_DONE_SOUND.get(),
                SoundSource.BLOCKS, 0.9F, 1.0F);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_PLANKS.defaultBlockState()),
                sessionPos.getX() + 0.5, sessionPos.getY() + 1.05, sessionPos.getZ() + 0.5,
                18, 0.5, 0.15, 0.4, 0.02);
            // Chronicle: completes the "Saw the batch" woodworking tutorial step.
            com.bannerbound.core.codex.CodexManager.onCustom(player, "woodworking_sawed", "");
        }
        com.bannerbound.core.api.workshop.WorkBlockLocks.unlock(sessionPos, player.getUUID());
        SESSIONS.remove(player.getUUID());
    }

    /** Drops a disconnecting player's session (nothing was consumed — the list stays on the table). */
    public static void onPlayerDisconnect(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session != null) {
            com.bannerbound.core.api.workshop.WorkBlockLocks.unlock(session.pos(), player.getUUID());
        }
    }

    /** Drops any session anchored at {@code pos} (the table was broken) and clears its work lock. */
    public static void abortSessionAt(BlockPos pos) {
        SESSIONS.values().removeIf(s -> s.pos().equals(pos));
        com.bannerbound.core.api.workshop.WorkBlockLocks.forceUnlock(pos);
    }
}
