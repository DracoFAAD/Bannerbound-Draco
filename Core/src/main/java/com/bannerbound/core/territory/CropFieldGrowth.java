package com.bannerbound.core.territory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.entity.FarmerWorkGoal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * The crop-outpost counterpart to {@link MinerVeinRegen}: a slow heartbeat that <b>ripens</b> a
 * banner-owned crop-outpost field in waves, so a managed crop outpost has the same readable
 * harvest cadence as an ore deposit's vein refresh (and isn't bottlenecked by slow dry-farmland
 * vanilla growth). Only fields with a committed {@link FarmerWorkGoal#OUTPOST_SELECTION_TYPE}
 * marker ripen, and only while loaded — an unloaded outpost simply pauses.
 *
 * <p>Each wave brings every planted-but-immature crop on the field's farmland to full maturity (a
 * state swap, like the vein refresh); the farmer harvests them, replants from the hauled-in seed,
 * and the next wave ripens the new planting. Player home farms are untouched — they grow at vanilla
 * speed and earn the crop-chunk yield bonus instead.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class CropFieldGrowth {
    /** How often the registry is swept for due markers. */
    private static final int SWEEP_INTERVAL_TICKS = 100;
    /** Ripening cadence — matched to the ore vein refresh (three times per Minecraft day). */
    private static final int REGEN_WAVE_INTERVAL_TICKS = 8_000;

    /** Marker anchor (packed) → game time its next ripen wave is due. Transient by design. */
    private static final Map<Long, Long> NEXT_DUE = new ConcurrentHashMap<>();

    private CropFieldGrowth() {
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
            if (!FarmerWorkGoal.OUTPOST_SELECTION_TYPE.equals(sel.workstationType())) continue;
            anyMarker = true;
            BlockPos anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            long key = anchor.asLong();
            Long due = NEXT_DUE.get(key);
            if (due != null && now < due) continue;
            if (!sl.hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4)) continue;   // paused while unloaded
            ripenWave(sl, sel);
            NEXT_DUE.put(key, now + REGEN_WAVE_INTERVAL_TICKS);
        }
        if (!anyMarker && !NEXT_DUE.isEmpty()) NEXT_DUE.clear();   // all markers gone → drop state
    }

    /** Brings every immature crop standing on the field's farmland to full maturity. */
    private static void ripenWave(ServerLevel sl, BlockSelection sel) {
        int ripened = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = sel.minY(); y <= sel.maxY(); y++) {
            for (int x = sel.minX(); x <= sel.maxX(); x++) {
                for (int z = sel.minZ(); z <= sel.maxZ(); z++) {
                    m.set(x, y, z);
                    BlockState s = sl.getBlockState(m);
                    if (!(s.getBlock() instanceof CropBlock crop) || crop.isMaxAge(s)) continue;
                    if (!sl.getBlockState(m.below()).is(Blocks.FARMLAND)) continue;
                    sl.setBlock(m.immutable(), crop.getStateForAge(crop.getMaxAge()), 2);
                    ripened++;
                }
            }
        }
        if (ripened > 0) {
            BlockPos centre = new BlockPos((sel.minX() + sel.maxX()) / 2, sel.maxY(),
                (sel.minZ() + sel.maxZ()) / 2);
            sl.playSound(null, centre, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.7f, 1.2f);
        }
    }
}
