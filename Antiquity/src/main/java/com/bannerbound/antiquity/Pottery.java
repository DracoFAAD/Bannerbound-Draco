package com.bannerbound.antiquity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.PotterySlabBlockEntity;
import com.bannerbound.antiquity.network.OpenPotteryPayload;
import com.bannerbound.antiquity.network.PotteryActionPayload;
import com.bannerbound.antiquity.recipe.PotteryRecipe;
import com.bannerbound.antiquity.recipe.PotteryRecipeManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-authoritative driver for the pottery wheel minigame. */
@ApiStatus.Internal
public final class Pottery {
    private Pottery() {}

    private static final class Session {
        final BlockPos pos;
        final ResourceLocation recipeId;
        final int spins;
        final long startTime;

        Session(BlockPos pos, ResourceLocation recipeId, int spins, long startTime) {
            this.pos = pos;
            this.recipeId = recipeId;
            this.spins = spins;
            this.startTime = startTime;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    public static void startSession(ServerPlayer player, BlockPos pos, PotterySlabBlockEntity be) {
        PotteryRecipe recipe = be.matchedRecipe();
        if (recipe == null) return;
        ResourceLocation id = PotteryRecipeManager.idOf(recipe);
        if (id == null) return;
        SESSIONS.put(player.getUUID(), new Session(pos.immutable(), id,
            Math.max(1, recipe.spins()), player.serverLevel().getGameTime()));
        com.bannerbound.core.api.workshop.WorkBlockLocks.lock(pos, player.getUUID());
        ItemStack shaping = recipe.inProgress().isPresent()
            ? new ItemStack(recipe.inProgress().get())
            : new ItemStack(Blocks.CLAY);
        be.setInProgress(shaping);
        PacketDistributor.sendToPlayer(player, new OpenPotteryPayload(pos, recipe.spins()));
    }

    public static void handleAction(ServerPlayer player, PotteryActionPayload payload) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.pos.equals(payload.pos())) return;
        ServerLevel level = player.serverLevel();

        switch (payload.action()) {
            case PotteryActionPayload.COMPLETE -> {
                if (MinigameGuard.stationInReach(player, session.pos)
                        && MinigameGuard.elapsedOk(player, session.startTime, session.spins, 5)) {
                    complete(level, session);
                }
                clearInProgress(level, session.pos);
                com.bannerbound.core.api.workshop.WorkBlockLocks.unlock(session.pos, player.getUUID());
                SESSIONS.remove(player.getUUID());
            }
            case PotteryActionPayload.CANCEL -> {
                clearInProgress(level, session.pos);
                com.bannerbound.core.api.workshop.WorkBlockLocks.unlock(session.pos, player.getUUID());
                SESSIONS.remove(player.getUUID());
            }
            default -> { }
        }
    }

    private static void complete(ServerLevel level, Session session) {
        PotteryRecipe recipe = PotteryRecipeManager.byId(session.recipeId);
        if (recipe == null) return;
        if (level.getBlockEntity(session.pos) instanceof PotterySlabBlockEntity be) {
            be.consumePile();
        }
        ItemStack out = recipe.result().copy();
        Block.popResource(level, session.pos.above(), out);
        level.playSound(null, session.pos, SoundEvents.GRAVEL_PLACE, SoundSource.BLOCKS, 0.8F, 0.85F);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.CLAY.defaultBlockState()),
            session.pos.getX() + 0.5, session.pos.getY() + 0.85, session.pos.getZ() + 0.5,
            14, 0.3, 0.15, 0.3, 0.03);
    }

    private static void clearInProgress(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof PotterySlabBlockEntity be) {
            be.setInProgress(ItemStack.EMPTY);
        }
    }

    public static void onPlayerDisconnect(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session != null) {
            clearInProgress(player.serverLevel(), session.pos);
            com.bannerbound.core.api.workshop.WorkBlockLocks.unlock(session.pos, player.getUUID());
        }
    }

    public static void abortSessionAt(BlockPos pos) {
        SESSIONS.values().removeIf(s -> s.pos.equals(pos));
        com.bannerbound.core.api.workshop.WorkBlockLocks.forceUnlock(pos);
    }
}
