package com.bannerbound.antiquity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.MortarAndPestleBlockEntity;
import com.bannerbound.antiquity.network.MortarGrindActionPayload;
import com.bannerbound.antiquity.network.OpenMortarGrindPayload;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.antiquity.recipe.MortarRecipe;
import com.bannerbound.antiquity.recipe.MortarRecipeManager;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.workshop.WorkBlockLocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-authoritative driver for the Mortar and Pestle press-and-grind minigame. Mirrors
 * {@code Pottery}: the client plays the (non-scored) press-and-grind beats and reports
 * completion; the server owns the loaded batch, the research gate, and the output.
 */
@ApiStatus.Internal
public final class MortarGrind {
    private MortarGrind() {}

    /** Press + grind beats it takes to finish a batch — fixed, so a big batch is a reward, not a chore. */
    public static final int REQUIRED_REPS = 4;

    /** Research flag (set by the Herbalism node) that unlocks grinding. The whole tool is gated on
     *  this one flag — every mortar recipe comes online together — rather than per-output, so the
     *  station never works for some recipes and silently fails for others. */
    public static final String FLAG_HERBALISM = "bannerbound.unlock.herbalism";

    /** True if the civ owning {@code pos}'s chunk has researched Herbalism. Mirrors
     *  {@code CraftGating}: permissive on the client / with no server context (server is
     *  authoritative); unclaimed land has no owner and {@code hasFlag(null, …)} is false. */
    public static boolean canGrindAt(@Nullable Level level, BlockPos pos) {
        if (level == null || level.isClientSide()) {
            return true;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return true;
        }
        Settlement owner;
        try {
            owner = SettlementData.get(server.overworld()).getByChunk(new ChunkPos(pos).toLong());
        } catch (Exception ex) {
            owner = null;
        }
        return ResearchManager.hasFlag(owner, FLAG_HERBALISM);
    }

    private static final class Session {
        final BlockPos pos;
        final long startTime;

        Session(BlockPos pos, long startTime) {
            this.pos = pos;
            this.startTime = startTime;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    public static void startSession(ServerPlayer player, BlockPos pos, MortarAndPestleBlockEntity be) {
        if (WorkBlockLocks.isLockedByOther(pos, player.getUUID())) {
            return;
        }
        if (be.getIngredient().isEmpty()
                || MortarRecipeManager.find(be.getIngredient(), be.getLiquidId()) == null
                || !canGrindAt(player.level(), pos)) {
            return;
        }
        int batch = be.getIngredient().getCount();
        SESSIONS.put(player.getUUID(), new Session(pos.immutable(), player.serverLevel().getGameTime()));
        WorkBlockLocks.lock(pos, player.getUUID());
        PacketDistributor.sendToPlayer(player, new OpenMortarGrindPayload(pos, REQUIRED_REPS, batch));
    }

    public static void handleAction(ServerPlayer player, MortarGrindActionPayload payload) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.pos.equals(payload.pos())) return;
        ServerLevel level = player.serverLevel();

        switch (payload.action()) {
            case MortarGrindActionPayload.COMPLETE -> {
                if (MinigameGuard.stationInReach(player, session.pos)
                        && MinigameGuard.elapsedOk(player, session.startTime, REQUIRED_REPS, 8)) {
                    complete(level, session);
                }
                endSession(player, session);
            }
            case MortarGrindActionPayload.CANCEL -> endSession(player, session);
            default -> { }
        }
    }

    private static void complete(ServerLevel level, Session session) {
        if (!(level.getBlockEntity(session.pos) instanceof MortarAndPestleBlockEntity be)) {
            return;
        }
        ItemStack ground = be.getIngredient().copy();
        MortarRecipe recipe = MortarRecipeManager.find(ground, be.getLiquidId());
        if (recipe == null) {
            return;
        }

        // Herbalism (checked when the session was started) gates the whole tool, so every recipe is
        // free to produce here. The liquid becomes the recipe's result ("" empties the bowl); item
        // recipes batch — a whole loaded stack produces (result count × batch) items popped above.
        be.setLiquid(recipe.resultLiquid());
        if (recipe.resultItem().isEmpty()) {
            be.setIngredient(ItemStack.EMPTY);
        } else {
            int total = recipe.resultItem().getCount() * Math.max(1, ground.getCount());
            int max = recipe.resultItem().getMaxStackSize();
            while (total > 0) {
                int chunk = Math.min(max, total);
                Block.popResource(level, session.pos.above(), recipe.resultItem().copyWithCount(chunk));
                total -= chunk;
            }
            be.setIngredient(ItemStack.EMPTY);
        }

        be.playFlourish();
        level.playSound(null, session.pos, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 1.0F, 0.7F);
        if (!ground.isEmpty()) {
            level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, ground),
                session.pos.getX() + 0.5, session.pos.getY() + 0.75, session.pos.getZ() + 0.5,
                16, 0.22, 0.12, 0.22, 0.02);
        }
    }

    private static void endSession(ServerPlayer player, Session session) {
        WorkBlockLocks.unlock(session.pos, player.getUUID());
        SESSIONS.remove(player.getUUID());
    }

    public static void onPlayerDisconnect(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session != null) {
            WorkBlockLocks.unlock(session.pos, player.getUUID());
        }
    }

    public static void abortSessionAt(BlockPos pos) {
        SESSIONS.values().removeIf(s -> s.pos.equals(pos));
        WorkBlockLocks.forceUnlock(pos);
    }
}
