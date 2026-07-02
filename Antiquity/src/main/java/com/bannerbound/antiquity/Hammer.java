package com.bannerbound.antiquity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.StoneAnvilBlockEntity;
import com.bannerbound.antiquity.item.HammerItem;
import com.bannerbound.antiquity.metalworking.MetalworkingItems;
import com.bannerbound.antiquity.network.HammerActionPayload;
import com.bannerbound.antiquity.network.OpenHammerPayload;
import com.bannerbound.antiquity.recipe.AnvilRecipe;
import com.bannerbound.antiquity.recipe.AnvilRecipeManager;
import com.bannerbound.core.api.quality.QualityMath;
import com.bannerbound.core.api.quality.QualityTier;
import com.bannerbound.core.api.workshop.WorkBlockLocks;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-authoritative driver for the cold-hammer minigame at the Stone Anvil (METALWORKING_PLAN.md
 * Part 3). The anvil is a pile station; this opens the gravity-drag minigame for the pile's matched
 * {@link AnvilRecipe} (blade + stick → sword), consumes the pile on commit, and on completion applies
 * the <b>hammer-rank quality gate</b> before stamping the tier via {@link Fletching#applyQuality}.
 */
@ApiStatus.Internal
public final class Hammer {
    private Hammer() {}

    private static final class Session {
        final BlockPos pos;
        final ResourceLocation recipeId;
        final int hammerRank;
        final int metalColor;
        final int requiredStrikes;
        final long startTime;
        final List<Integer> strikeScores = new java.util.ArrayList<>();
        int strikeCount;
        boolean committed;

        Session(BlockPos pos, ResourceLocation recipeId, int hammerRank, int metalColor,
                int requiredStrikes, long startTime) {
            this.pos = pos;
            this.recipeId = recipeId;
            this.hammerRank = hammerRank;
            this.metalColor = metalColor;
            this.requiredStrikes = requiredStrikes;
            this.startTime = startTime;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    /** Opens the minigame for the anvil's matched recipe, finished with a hammer of {@code hammerRank}. */
    public static void startSession(ServerPlayer player, BlockPos pos, StoneAnvilBlockEntity be, int hammerRank) {
        AnvilRecipe recipe = be.matchedRecipe();
        if (recipe == null) return;
        ResourceLocation id = AnvilRecipeManager.idOf(recipe);
        if (id == null) return;
        String metal = MetalworkingItems.metalOf(recipe.result().getItem());
        int workpieceRank = com.bannerbound.antiquity.metalworking.MetalworkingData.rank(metal);
        boolean canSuperior = hammerRank >= workpieceRank - 1;
        int metalColor = com.bannerbound.antiquity.metalworking.MetalworkingData.color(metal);
        SESSIONS.put(player.getUUID(), new Session(pos.immutable(), id, hammerRank, metalColor,
            Math.max(1, recipe.strikes()), player.serverLevel().getGameTime()));
        WorkBlockLocks.lock(pos, player.getUUID());
        PacketDistributor.sendToPlayer(player,
            new OpenHammerPayload(pos, Math.max(1, recipe.strikes()), canSuperior));
        broadcastArm(player, true);
    }

    /** Tell tracking clients (and the smith) to raise/lower the third-person hammer arm. */
    private static void broadcastArm(ServerPlayer player, boolean active) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
            new com.bannerbound.antiquity.network.HammerArmPayload(player.getUUID(), active));
    }

    public static void handleAction(ServerPlayer player, HammerActionPayload payload) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.pos.equals(payload.pos())) return;
        ServerLevel level = player.serverLevel();

        switch (payload.action()) {
            case HammerActionPayload.COMMIT -> {
                if (!session.committed
                        && level.getBlockEntity(session.pos) instanceof StoneAnvilBlockEntity be) {
                    AnvilRecipe recipe = AnvilRecipeManager.byId(session.recipeId);
                    int strikes = recipe != null ? Math.max(1, recipe.strikes()) : 1;
                    be.consumePile();
                    if (recipe != null) {
                        be.beginForging(recipe.result(), strikes, session.metalColor); // glowing workpiece
                    }
                    session.committed = true;
                }
            }
            case HammerActionPayload.COMPLETE -> {
                if (session.committed
                        && MinigameGuard.stationInReach(player, session.pos)
                        && session.strikeCount == session.requiredStrikes
                        && MinigameGuard.elapsedOk(player, session.startTime, session.requiredStrikes, 8)) {
                    complete(level, player, session);
                }
                if (level.getBlockEntity(session.pos) instanceof StoneAnvilBlockEntity be) be.endForging();
                WorkBlockLocks.unlock(session.pos, player.getUUID());
                SESSIONS.remove(player.getUUID());
                broadcastArm(player, false);
            }
            case HammerActionPayload.STRIKE -> {
                if (session.committed) {
                    int score = MinigameGuard.clampScore(
                        payload.scores().isEmpty() ? 0 : payload.scores().get(0));
                    session.strikeCount++;
                    if (session.strikeScores.size() < session.requiredStrikes) {
                        session.strikeScores.add(score);
                    }
                    if (level.getBlockEntity(session.pos) instanceof StoneAnvilBlockEntity be) {
                        be.forgeStrike(); // cool the workpiece one notch
                    }
                    strikeEffects(level, player, session.pos, score, session.metalColor);
                }
            }
            case HammerActionPayload.CANCEL -> {
                if (level.getBlockEntity(session.pos) instanceof StoneAnvilBlockEntity be) be.endForging();
                WorkBlockLocks.unlock(session.pos, player.getUUID());
                SESSIONS.remove(player.getUUID());
                broadcastArm(player, false);
            }
            default -> { }
        }
    }

    /** World-visible feedback for one strike — sparks, a hammer-clang, and the smith's arm swing — so
     *  nearby players see/hear the smithing. The sound excludes {@code player} (their client already
     *  played the graded hammer sound locally for zero-latency feedback). */
    private static void strikeEffects(ServerLevel level, ServerPlayer player, BlockPos pos, int score,
                                      int metalColor) {
        double x = pos.getX() + 0.5, y = pos.getY() + 1.02, z = pos.getZ() + 0.5;
        int sparks = 6 + score / 6;
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.LAVA, x, y, z, sparks,
            0.18, 0.05, 0.18, 0.0);
        // Per-metal coloured sparks (redstone-dust particle takes an arbitrary tint).
        var dust = new net.minecraft.core.particles.DustParticleOptions(
            new org.joml.Vector3f(((metalColor >> 16) & 0xFF) / 255f,
                ((metalColor >> 8) & 0xFF) / 255f, (metalColor & 0xFF) / 255f), 0.9F);
        level.sendParticles(dust, x, y, z, sparks, 0.26, 0.12, 0.26, 0.0);
        if (score >= 55) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT, x, y, z, sparks,
                0.28, 0.1, 0.28, 0.18);
        }
        if (score >= 100) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANTED_HIT, x, y, z, 12,
                0.3, 0.05, 0.3, 0.2);
        }
        float pitch = score >= 100 ? 1.3F : score >= 80 ? 1.15F : score >= 55 ? 1.0F : 0.85F;
        level.playSound(player, pos, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.5F, pitch);
        // The arm swing broadcasts to all tracking clients, so others see the smith hammering.
        for (net.minecraft.world.InteractionHand h : net.minecraft.world.InteractionHand.values()) {
            if (player.getItemInHand(h).getItem() instanceof HammerItem) {
                player.swing(h, true);
                break;
            }
        }
    }

    private static void complete(ServerLevel level, ServerPlayer player, Session session) {
        AnvilRecipe recipe = AnvilRecipeManager.byId(session.recipeId);
        if (recipe == null) return;
        List<Integer> scores = session.strikeScores;
        int[] arr = new int[scores.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = scores.get(i);
        int score = QualityMath.aggregate(arr);

        // Hammer-rank gate: a workpiece reaches its top tier (Superior = Masterwork) only when the
        // hammer's rank is at least one step below the metal's; otherwise it caps at Standard.
        String metal = MetalworkingItems.metalOf(recipe.result().getItem());
        int workpieceRank = com.bannerbound.antiquity.metalworking.MetalworkingData.rank(metal);
        boolean canSuperior = session.hammerRank >= workpieceRank - 1;
        QualityTier rolled = QualityMath.npcTierFromScore(score); // CRUDE..MASTERWORK
        QualityTier tier = canSuperior ? rolled
            : (rolled.ordinal() > QualityTier.STANDARD.ordinal() ? QualityTier.STANDARD : rolled);
        tier = com.bannerbound.antiquity.item.Intoxication.craftQuality(player, tier); // drunk/hungover → botched

        ItemStack out = Fletching.applyQuality(recipe.result().copy(), tier);
        if (!player.getInventory().add(out)) {
            Block.popResource(level, session.pos.above(), out);
        }
        // Wear the hammer.
        for (net.minecraft.world.InteractionHand h : net.minecraft.world.InteractionHand.values()) {
            if (player.getItemInHand(h).getItem() instanceof HammerItem) {
                player.getItemInHand(h).hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
                break;
            }
        }
        finishFlourish(level, session.pos, tier, session.metalColor);
    }

    /** A tier-scaled completion flourish: a colour-tinted spark fountain + a chime that brightens with
     *  quality — Masterwork gets the big celebratory ring + level-up chime. */
    private static void finishFlourish(ServerLevel level, BlockPos pos, QualityTier tier, int metalColor) {
        double x = pos.getX() + 0.5, y = pos.getY() + 1.0, z = pos.getZ() + 0.5;
        int rank = tier.ordinal(); // CRUDE 0 .. MASTERWORK 3
        level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.7F, 1.1F);

        // A rising fountain of the metal's coloured sparks, taller and denser the better the tier.
        var dust = new net.minecraft.core.particles.DustParticleOptions(
            new org.joml.Vector3f(((metalColor >> 16) & 0xFF) / 255f,
                ((metalColor >> 8) & 0xFF) / 255f, (metalColor & 0xFF) / 255f), 1.1F);
        level.sendParticles(dust, x, y + 0.1, z, 14 + rank * 10, 0.18, 0.25, 0.18, 0.08);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT, x, y, z,
            10 + rank * 6, 0.3, 0.2, 0.3, 0.12);

        if (tier.ordinal() >= QualityTier.FINE.ordinal()) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANTED_HIT,
                x, y, z, 12, 0.3, 0.25, 0.3, 0.12);
            level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS,
                0.8F, 0.9F + 0.15F * rank);
        }
        if (tier == QualityTier.MASTERWORK) {
            // A celebratory ring + the level-up chime for the top tier.
            for (int i = 0; i < 16; i++) {
                double a = i / 16.0 * Math.PI * 2;
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANTED_HIT,
                    x + Math.cos(a) * 0.6, y + 0.1, z + Math.sin(a) * 0.6, 1, 0.0, 0.02, 0.0, 0.0);
            }
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.FIREWORK, x, y + 0.3, z,
                20, 0.2, 0.2, 0.2, 0.12);
            level.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.6F, 1.4F);
        }
    }

    public static void onPlayerDisconnect(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session != null) {
            if (player.serverLevel().getBlockEntity(session.pos) instanceof StoneAnvilBlockEntity be) {
                be.endForging();
            }
            WorkBlockLocks.unlock(session.pos, player.getUUID());
            broadcastArm(player, false);
        }
    }

    public static void abortSessionAt(BlockPos pos) {
        SESSIONS.values().removeIf(s -> s.pos.equals(pos));
        WorkBlockLocks.forceUnlock(pos);
    }
}
