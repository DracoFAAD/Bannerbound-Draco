package com.bannerbound.core.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.bannerbound.core.Config;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.building.PenEnclosure;
import com.bannerbound.core.event.AnimalBreedingGate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;

/**
 * A per-source food RATE estimate for a settlement's <b>live penned livestock</b>. Every animal in a
 * registered herder pen contributes {@code size × }{@link Config#HERDER_FOOD_PER_SIZE_PER_SECOND}
 * food/sec (chicken=1, cow/sheep/pig=2, horse=3 — see {@link HerderWorkGoal#foodSize}). So 6 horses =
 * 6×3×0.05 = 0.9 food/s of <i>livestock rate</i>.
 *
 * <p><b>Currently orphaned.</b> Like {@link com.bannerbound.core.api.farmer.FarmerFoodBonus}, this is NOT
 * part of {@link Settlement#effectiveFoodPerSecond()} since the COOKING_PLAN larder rewrite — real herder
 * food reaches the settlement anonymously through culled meat in the storage scan — and {@link #refresh}
 * is no longer called. Per-source accounting now lives in {@link Settlement#addFoodProduced} (a cumulative
 * production statistic credited per cull), which is what crisis objectives read. Kept for reference /
 * possible reuse as an instantaneous-rate estimate.
 *
 * <p>Harvesting (culling) an animal therefore <i>lowers</i> this rate automatically — that's the
 * intended trade: standing food income for the settlement vs. a one-shot meat harvest for the citizens.
 * Gated on the same Animal-Husbandry research flag as breeding, so it's part of one researched feature
 * (a pen normally already implies husbandry, since its fences come from that node).
 */
public final class HerderFoodBonus {
    private static final Map<UUID, Double> CACHE = new HashMap<>();

    private HerderFoodBonus() {
    }

    /** Last-cached passive livestock food/sec for {@code settlementId} (0 if nothing computed yet). */
    public static double get(UUID settlementId) {
        return CACHE.getOrDefault(settlementId, 0.0);
    }

    /** Recompute and cache the bonus for {@code s}. Called once/second from the immigration tick. */
    public static double refresh(ServerLevel level, Settlement s) {
        double bonus = compute(level, s);
        CACHE.put(s.id(), bonus);
        s.setPassiveFoodSourceRate("livestock", bonus);
        return bonus;
    }

    /** Drop a disbanded settlement's entry — no-op if absent. */
    public static void forget(UUID settlementId) {
        CACHE.remove(settlementId);
    }

    private static double compute(ServerLevel level, Settlement s) {
        double rate = Config.HERDER_FOOD_PER_SIZE_PER_SECOND.get();
        if (rate <= 0.0) return 0.0;
        // Livestock food is part of Animal Husbandry — no research, no passive income (matches breeding).
        if (!ResearchManager.hasFlag(s, AnimalBreedingGate.FLAG)) return 0.0;

        double sizeSum = 0.0;
        for (BlockSelection sel : BlockSelectionRegistry.get(level).getForSettlement(s.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!HerderWorkGoal.SELECTION_TYPE.equals(sel.workstationType())) continue;
            EntityType<? extends Animal> type = HerderWorkGoal.animalFromMarker(sel);
            if (type == null) continue;
            BlockPos anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            // Don't force-load a far pen just to count it — an unloaded pen earns nothing this tick.
            if (!level.hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4)) continue;
            PenEnclosure.Result r = PenEnclosure.scan(level, anchor);
            if (!r.valid()) continue;
            sizeSum += (double) HerderWorkGoal.foodSize(type) * countInside(level, r, type);
        }
        return sizeSum * rate;
    }

    private static int countInside(ServerLevel level, PenEnclosure.Result r, EntityType<?> type) {
        return level.getEntitiesOfClass(Animal.class, r.bounds().inflate(1.0, 2.0, 1.0),
            a -> a.isAlive() && a.getType() == type && inInterior(r, a)).size();
    }

    /** Interior-column membership (1-block margin), matching the herder's own herd count so a pen earns
     *  for exactly the animals it's considered to hold (an L-shaped pen's bounding box covers ground
     *  outside the rope, which a raw bbox test would miscount). */
    private static boolean inInterior(PenEnclosure.Result r, Animal a) {
        int feetY = a.blockPosition().getY();
        int cx0 = (int) Math.floor(a.getX() - 1.0), cx1 = (int) Math.floor(a.getX() + 1.0);
        int cz0 = (int) Math.floor(a.getZ() - 1.0), cz1 = (int) Math.floor(a.getZ() + 1.0);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (r.interior().contains(new BlockPos(cx, feetY, cz))
                    || r.interior().contains(new BlockPos(cx, feetY - 1, cz))) {
                    return true;
                }
            }
        }
        return false;
    }
}
