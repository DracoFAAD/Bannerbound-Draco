package com.bannerbound.core.territory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.entity.MinerWorkGoal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * The slow heartbeat of every marked ore boulder: positions the {@link BoulderLayout} owns as ORE
 * that currently sit in their chipped/body state regenerate back to ore, one block at a time per
 * marker. This is what makes the miner's chip cycle sustainable forever — the vein face
 * "refreshes" (a state swap, deliberately NOT new blocks appearing from thin air; the boulder's
 * shape never changes, only the speckle pattern breathes).
 *
 * <p>Only chunks with a committed miner marker regenerate (the marker IS the "this deposit is
 * being worked" signal), and only while loaded — an unloaded mine simply pauses, which reads
 * fine: nothing was being chipped out there either. Pacing is tuned so one miner roughly keeps
 * up with one boulder; per-marker due-times live in a transient map (worst case after a restart
 * every marker is immediately due once — harmless).
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class MinerVeinRegen {
    /** How often the registry is swept for due markers. */
    private static final int SWEEP_INTERVAL_TICKS = 100;
    /** The vein refreshes in WAVES: every due time, ALL chipped faces restore at once. At 8000
     *  ticks (3×/day) the miner sat idle for minutes between waves, which read as "broken / refuses
     *  to mine". 1200 ticks (~1 min, 20×/day) keeps a working face available almost continuously so
     *  a miner with ore on the rock actually mines it, while still pacing yield by the boulder's
     *  exposed-face count. TUNABLE — raise toward the old value to throttle ore income, lower for a
     *  near-continuous trickle. The off-screen {@code OutpostYieldManager} mirrors this rate so an
     *  unloaded outpost produces at the same pace. */
    private static final int REGEN_WAVE_INTERVAL_TICKS = 1_200;

    /** Marker anchor (packed) → game time its next face may refresh. Transient by design. */
    private static final Map<Long, Long> NEXT_DUE = new ConcurrentHashMap<>();

    private MinerVeinRegen() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        if (sl.dimension() != Level.OVERWORLD) return;
        long now = sl.getGameTime();
        if (now % SWEEP_INTERVAL_TICKS != 0) return;

        boolean anyMarker = false;
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getAll()) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!MinerWorkGoal.SELECTION_TYPE.equals(sel.workstationType())) continue;
            anyMarker = true;
            BlockPos anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            long key = anchor.asLong();
            Long due = NEXT_DUE.get(key);
            if (due != null && now < due) continue;
            if (!sl.hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4)) continue; // paused while unloaded
            regenWave(sl, sel, anchor);
            NEXT_DUE.put(key, now + REGEN_WAVE_INTERVAL_TICKS);
        }
        if (!anyMarker && !NEXT_DUE.isEmpty()) NEXT_DUE.clear(); // all markers deleted → drop state
    }

    /** One refresh wave: restores EVERY chipped layout-ore position to ore-state. */
    private static void regenWave(ServerLevel sl, BlockSelection sel, BlockPos anchor) {
        ChunkResource type = MinerWorkGoal.mineResource(sel.seedItemId());
        int baseY = MinerWorkGoal.mineBaseY(sel.seedItemId());
        if (!BoulderLayout.isOreChunk(type) || baseY == Integer.MIN_VALUE) return;
        ChunkPos cp = new ChunkPos(anchor);
        int restored = 0;
        for (BoulderLayout.Spot s : BoulderLayout.spots(sl.getSeed(), cp, baseY)) {
            if (!s.ore()) continue;
            // isChippedState also accepts legacy stand-in blocks (pre-tin-ore andesite), so old
            // boulders quietly migrate to the real ore block as their faces refresh.
            if (!BoulderLayout.isChippedState(type, sl.getBlockState(s.pos()))) continue;
            sl.setBlock(s.pos(), BoulderLayout.oreBlock(type), 3);
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, BoulderLayout.oreBlock(type)),
                s.pos().getX() + 0.5, s.pos().getY() + 0.5, s.pos().getZ() + 0.5, 3, 0.25, 0.25, 0.25, 0.0);
            restored++;
        }
        // The "vein has refreshed" moment, audible once per wave for anyone on site — a soft
        // crystalline chime, not an announcement (silent when nothing needed restoring).
        if (restored > 0) {
            BlockPos center = new BlockPos(cp.getMinBlockX() + 8, baseY + 1, cp.getMinBlockZ() + 8);
            sl.playSound(null, center, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.8f, 0.6f);
        }
    }
}
