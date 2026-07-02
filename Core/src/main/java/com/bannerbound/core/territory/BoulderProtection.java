package com.bannerbound.core.territory;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.research.OreDisguise;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.research.SettlementDropFilter;
import com.bannerbound.core.api.research.data.OreDisguiseLoader;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Resource deposits are permanent chunk identity markers, so players can never DESTROY
 * their blocks, only work them the way miners/diggers do. Breaking a boulder ORE face with a
 * tier-correct pickaxe cancels the vanilla break and applies the <b>chip cycle</b> instead:
 * the block swaps to its worked state, the resource item pops, and the regen ticker restores
 * the face later (player parity — "if a player can do it..."). Body/worked deposit blocks
 * simply refuse to break. Creative players bypass everything (builders/testing).
 *
 * <p>Runs on the cancelable {@link BlockEvent.BreakEvent}, which fires BEFORE the block is
 * destroyed — so the disguise system's {@code BlockDropsEvent} path never sees a deposit break.
 * While an ore is still perception-gated for the breaker's civ ({@link OreDisguiseLoader}), the
 * face won't chip either: the "rock" just doesn't break, and no drop leaks what's inside.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class BoulderProtection {
    private BoulderProtection() {
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        if (sl.dimension() != Level.OVERWORLD) return;
        Player player = event.getPlayer();
        if (player == null || player.isCreative()) return;   // creative = deliberate edits allowed

        BlockPos pos = event.getPos();
        ChunkPos cp = new ChunkPos(pos);
        // Cheap pre-filter: boulders only ever occupy the chunk's +8,+8 column ± RADIUS.
        int cx = cp.getMinBlockX() + 8;
        int cz = cp.getMinBlockZ() + 8;
        int radius = Math.max(BoulderLayout.RADIUS, MaterialDepositLayout.MAX_RADIUS);
        if (Math.abs(pos.getX() - cx) > radius
            || Math.abs(pos.getZ() - cz) > radius) {
            return;
        }
        ChunkResource type = ChunkResources.typeAt(sl, cp);
        if (!BoulderLayout.isOreChunk(type)) {
            if (MaterialDepositLayout.isMaterialChunk(type)) {
                protectMaterialDeposit(event, sl, player, pos, cp, type);
            }
            return;
        }
        Integer baseY = BoulderLayout.locateBaseY(sl, cp, type).orElse(null);
        if (baseY == null) return;

        BoulderLayout.Spot spot = null;
        for (BoulderLayout.Spot s : BoulderLayout.spots(sl.getSeed(), cp, baseY)) {
            if (s.pos().equals(pos)) { spot = s; break; }
        }
        if (spot == null) return;   // not a boulder position (e.g. never carved here)

        BlockState state = sl.getBlockState(pos);
        if (spot.ore() && state.is(BoulderLayout.oreBlock(type).getBlock())) {
            event.setCanceled(true);
            // Perception-gated for this civ → the rock just refuses to break; nothing leaks.
            if (!revealedFor(sl, player, state.getBlock())) return;
            // Player parity with the miner's tier gate: wrong-tier pick can't bite the face.
            if (!player.getMainHandItem().isCorrectToolForDrops(state)) return;
            chipForPlayer(sl, player, pos, type, state);
            return;
        }
        // Boulder mass (body / chipped / legacy stand-in states) is indestructible.
        if (state.is(BoulderLayout.bodyBlock(type).getBlock()) || BoulderLayout.isChippedState(type, state)) {
            event.setCanceled(true);
        }
    }

    private static void protectMaterialDeposit(BlockEvent.BreakEvent event, ServerLevel sl, Player player,
                                               BlockPos pos, ChunkPos cp, ChunkResource type) {
        Integer baseY = MaterialDepositLayout.locateBaseY(sl, cp, type).orElse(null);
        if (baseY == null) return;

        MaterialDepositLayout.Spot spot = null;
        for (MaterialDepositLayout.Spot s : MaterialDepositLayout.spots(sl.getSeed(), cp, baseY, type)) {
            if (s.pos().equals(pos)) { spot = s; break; }
        }
        if (spot == null) return;

        BlockState state = sl.getBlockState(pos);
        if (spot.source() && state.is(MaterialDepositLayout.sourceBlock(type).getBlock())) {
            event.setCanceled(true);
            if (!player.getMainHandItem().isCorrectToolForDrops(state)) return;
            workMaterialForPlayer(sl, player, pos, type, state);
            return;
        }
        if (state.is(MaterialDepositLayout.bodyBlock(type).getBlock())
                || MaterialDepositLayout.isWorkedState(type, state)) {
            event.setCanceled(true);
        }
    }

    /** The player-side chip: swap to chipped state, pop the yield (knowledge-gated), make noise. */
    private static void chipForPlayer(ServerLevel sl, Player player, BlockPos pos,
                                      ChunkResource type, BlockState before) {
        sl.setBlock(pos, BoulderLayout.chippedBlock(type), 3);
        sl.playSound(null, pos, before.getSoundType().getBreakSound(), SoundSource.BLOCKS, 0.8f, 1.0f);
        sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, before),
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.0);
        Item drop = BoulderLayout.dropFor(type).orElse(null);
        if (drop == null) return;
        ItemStack one = new ItemStack(drop);
        Settlement settlement = SettlementData.get(sl.getServer().overworld()).getByPlayer(player.getUUID());
        if (SettlementDropFilter.shouldDrop(settlement, null, one)) {
            Block.popResource(sl, pos, one);
        }
    }

    /** Player-side material work: swap to worked state, pop the material yield, make noise. */
    private static void workMaterialForPlayer(ServerLevel sl, Player player, BlockPos pos,
                                              ChunkResource type, BlockState before) {
        sl.setBlock(pos, MaterialDepositLayout.workedBlock(type), 3);
        sl.playSound(null, pos, before.getSoundType().getBreakSound(), SoundSource.BLOCKS, 0.8f, 1.0f);
        sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, before),
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.0);
        Settlement settlement = SettlementData.get(sl.getServer().overworld()).getByPlayer(player.getUUID());
        java.util.List<ItemStack> drops = new java.util.ArrayList<>(MaterialDepositLayout.dropsFor(type));
        SettlementDropFilter.filterStacks(settlement,
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(before.getBlock()), drops);
        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) Block.popResource(sl, pos, drop);
        }
    }

    /** Whether the breaker's civ has revealed {@code oreBlock} (no disguise entry = always). */
    private static boolean revealedFor(ServerLevel sl, Player player, Block oreBlock) {
        OreDisguise disguise = OreDisguiseLoader.getDisguiseFor(oreBlock);
        if (disguise == null) return true;
        Settlement settlement = SettlementData.get(sl.getServer().overworld()).getByPlayer(player.getUUID());
        return ResearchManager.hasFlag(settlement, disguise.flag());
    }
}
