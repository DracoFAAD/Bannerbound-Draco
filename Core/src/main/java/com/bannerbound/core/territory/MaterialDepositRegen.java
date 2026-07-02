package com.bannerbound.core.territory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.entity.DiggerWorkGoal;

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
 * Refresh waves for worked stone/clay/sand deposits. These are marker-driven like ore veins:
 * a deposit only refreshes while a settlement has actually marked it for a digger/quarryworker.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class MaterialDepositRegen {
    private static final int SWEEP_INTERVAL_TICKS = 100;
    private static final int REGEN_WAVE_INTERVAL_TICKS = 8_000;

    private static final Map<Long, Long> NEXT_DUE = new ConcurrentHashMap<>();

    private MaterialDepositRegen() {
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
            if (!DiggerWorkGoal.SELECTION_TYPE.equals(sel.workstationType())) continue;
            ChunkResource type = MaterialDepositLayout.materialResource(sel.seedItemId());
            if (!MaterialDepositLayout.isMaterialChunk(type)) continue;
            anyMarker = true;
            BlockPos anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            long key = anchor.asLong();
            Long due = NEXT_DUE.get(key);
            if (due != null && now < due) continue;
            if (!sl.hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4)) continue;
            regenWave(sl, sel, anchor, type);
            NEXT_DUE.put(key, now + REGEN_WAVE_INTERVAL_TICKS);
        }
        if (!anyMarker && !NEXT_DUE.isEmpty()) NEXT_DUE.clear();
    }

    private static void regenWave(ServerLevel sl, BlockSelection sel, BlockPos anchor, ChunkResource type) {
        int baseY = MaterialDepositLayout.materialBaseY(sel.seedItemId());
        if (baseY == Integer.MIN_VALUE) return;
        ChunkPos cp = new ChunkPos(anchor);
        int restored = 0;
        for (MaterialDepositLayout.Spot s : MaterialDepositLayout.spots(sl.getSeed(), cp, baseY, type)) {
            if (!s.source()) continue;
            if (!MaterialDepositLayout.isWorkedState(type, sl.getBlockState(s.pos()))) continue;
            sl.setBlock(s.pos(), MaterialDepositLayout.sourceBlock(type), 3);
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, MaterialDepositLayout.sourceBlock(type)),
                s.pos().getX() + 0.5, s.pos().getY() + 0.5, s.pos().getZ() + 0.5,
                3, 0.25, 0.25, 0.25, 0.0);
            restored++;
        }
        if (restored > 0) {
            BlockPos center = new BlockPos(cp.getMinBlockX() + 8, baseY + 1, cp.getMinBlockZ() + 8);
            sl.playSound(null, center, net.minecraft.sounds.SoundEvents.GRINDSTONE_USE,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.5f, 0.8f);
        }
    }
}
