package com.bannerbound.antiquity;

import java.util.ArrayList;
import java.util.List;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.building.PenEnclosure;
import com.bannerbound.core.entity.HerderWorkGoal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Every so often a domesticated animal in a herder pen drops a {@link BannerboundAntiquity#MANURE manure}
 * pat on the floor near it. Manure fouls the pen's fertility (Core's {@code BreedingEvents}) until it's
 * cleared — by a herder mucking out (pen upkeep) or the player (yielding {@code dung}). A pen self-limits
 * to a cap so it never carpets in manure if left untended.
 *
 * <p>Mirrors {@link com.bannerbound.core.entity.HerderFoodBonus}'s pen walk (settlements → herder pen
 * markers → {@link PenEnclosure} → animals inside), but on a slow {@link ServerTickEvent.Post} cadence and
 * chunk-guarded so it never force-loads a far pen.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
public final class ManureEvents {
    /** How often the pass runs (server ticks). 600 = every 30 s — manure is meant to be occasional. */
    private static final int INTERVAL_TICKS = 600;
    /** Per-animal chance, each pass, to drop a pat. ~0.7 over the 30 s cadence → a cow fouls its spot
     *  roughly every ~40 s (kept in line with the old 5 s × 0.12 rate, just checked less often). */
    private static final double POOP_CHANCE = 0.7;
    /** How close to the animal a pat lands (interior floor cells within this horizontal distance). */
    private static final double DROP_RADIUS = 2.5;

    private static int tickCounter;

    private ManureEvents() {
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter % INTERVAL_TICKS != 0) return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();   // settlements (and their pens) live in the overworld
        BlockSelectionRegistry reg = BlockSelectionRegistry.get(level);
        for (Settlement s : SettlementData.get(level).all()) {
            // Don't foul a DORMANT settlement's pens: force-loaded claims keep this pass running
            // while every member is offline (mirrors FoodSpoilageEvents' dormancy guard).
            if (s.isDormant()) continue;
            for (BlockSelection sel : reg.getForSettlement(s.id())) {
                if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
                if (!HerderWorkGoal.SELECTION_TYPE.equals(sel.workstationType())) continue;
                EntityType<? extends Animal> type = HerderWorkGoal.animalFromMarker(sel);
                if (type == null) continue;
                BlockPos anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
                if (!level.hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4)) continue;
                PenEnclosure.Result r = PenEnclosure.scan(level, anchor);
                if (!r.valid()) continue;
                foulPen(level, r, type);
            }
        }
    }

    private static void foulPen(ServerLevel level, PenEnclosure.Result r, EntityType<? extends Animal> type) {
        RandomSource rng = level.getRandom();
        // Self-limit: a pen tops out at ~1 pat per 8 floor cells so an untended pen doesn't carpet over.
        int cap = Math.max(1, r.interior().size() / 8);
        if (countManure(level, r) >= cap) return;

        for (Animal a : level.getEntitiesOfClass(Animal.class, r.bounds().inflate(1.0, 2.0, 1.0),
                a -> a.isAlive() && a.getType() == type
                    && a.getPersistentData().getBoolean(HerderWorkGoal.DOMESTICATED_TAG))) {
            if (rng.nextDouble() >= POOP_CHANCE) continue;
            BlockPos floor = pickDropFloor(level, r, a, rng);
            if (floor == null) continue;
            level.setBlockAndUpdate(floor.above(), BannerboundAntiquity.MANURE.get().defaultBlockState());
            if (countManure(level, r) >= cap) return;   // respect the cap as the pass deposits
        }
    }

    /** An interior floor cell near {@code a} whose air cell is free for a pat (solid floor, empty above,
     *  no water, not already manure). Null if none qualify. */
    private static BlockPos pickDropFloor(ServerLevel level, PenEnclosure.Result r, Animal a, RandomSource rng) {
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos c : r.interior()) {
            double dx = (c.getX() + 0.5) - a.getX();
            double dz = (c.getZ() + 0.5) - a.getZ();
            if (dx * dx + dz * dz > DROP_RADIUS * DROP_RADIUS) continue;
            BlockState floor = level.getBlockState(c);
            if (!floor.blocksMotion() || !floor.getFluidState().isEmpty()) continue;   // need a dry solid floor
            BlockState above = level.getBlockState(c.above());
            if (!above.isAir()) continue;   // occupied (water, another pat, a block) → skip
            candidates.add(c.immutable());
        }
        return candidates.isEmpty() ? null : candidates.get(rng.nextInt(candidates.size()));
    }

    private static int countManure(ServerLevel level, PenEnclosure.Result r) {
        int n = 0;
        for (BlockPos c : r.interior()) {
            if (level.getBlockState(c.above()).is(com.bannerbound.core.entity.BreedingEvents.MANURE)) n++;
        }
        return n;
    }
}
