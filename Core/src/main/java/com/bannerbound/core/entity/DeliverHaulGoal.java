package com.bannerbound.core.entity;

import java.util.EnumSet;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

/**
 * Anarchy carry-home delivery. When a self-organizing gatherer has no real storage it deposits its
 * harvest into its {@link CitizenEntity#getAnarchyHaul() carry pack} instead of a chest; once it has
 * filled that pack (and so yielded its work goal), this goal walks the citizen to the town hall and
 * dumps the load on the ground there — the worker physically hauling it home rather than the loot
 * teleporting.
 *
 * <p>Sits at the same priority as the work goals but is registered before them, so when a delivery
 * is due it wins the MOVE flag (the gatherer has already yielded, so they're never both runnable).
 * Dormant whenever the carry pack is empty — which is always, for a citizen with a real drop-off.
 */
@ApiStatus.Internal
public class DeliverHaulGoal extends Goal {
    /** Squared "close enough to dump at the town hall" radius. */
    private static final double REACH_SQ = 6.25;   // 2.5 blocks
    /** Walk budget before we give up and drop the load at our feet (walled off / unreachable). */
    private static final int DELIVER_TIMEOUT_TICKS = 300;
    /** Re-issue the path to a (static) town hall periodically in case the first path failed. */
    private static final int REPATH_INTERVAL = 20;

    private final CitizenEntity citizen;
    private final double speedModifier;

    @Nullable private BlockPos townHall;
    private int tripTicks;
    private boolean done;

    public DeliverHaulGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(citizen.level() instanceof ServerLevel)) return false;
        if (!citizen.hasHaul()) return false;
        // Only deliver once the gatherer has stopped working (pack full / nothing left to gather /
        // resting) — while it's actively gathering we let it keep filling the pack.
        if (citizen.isWorking()) return false;
        if (!citizen.isAiActive()) return false;
        if (citizen.isChild()) return false;
        Settlement s = citizen.getSettlement();
        if (s == null || s.townHallPos() == null) return false;
        this.townHall = s.townHallPos();
        return true;
    }

    @Override
    public void start() {
        done = false;
        tripTicks = DELIVER_TIMEOUT_TICKS;
        // Carry a representative item in hand so it reads as "hauling a load home".
        citizen.setItemSlot(EquipmentSlot.MAINHAND, firstHaulItem());
        if (townHall != null) {
            citizen.getNavigation().moveTo(
                townHall.getX() + 0.5, townHall.getY(), townHall.getZ() + 0.5, speedModifier);
        }
    }

    @Override
    public boolean canContinueToUse() {
        return !done && citizen.hasHaul() && townHall != null;
    }

    @Override
    public void tick() {
        if (townHall == null) {
            // Town hall vanished mid-trip (campfire destroyed / settlement disbanded) — drop the load
            // where we stand rather than carrying it forever.
            dump(citizen.blockPosition());
            done = true;
            return;
        }
        tripTicks--;
        citizen.getLookControl().setLookAt(
            townHall.getX() + 0.5, townHall.getY() + 0.5, townHall.getZ() + 0.5);
        double distSq = citizen.distanceToSqr(
            townHall.getX() + 0.5, townHall.getY(), townHall.getZ() + 0.5);
        if (distSq <= REACH_SQ) {
            dump(townHall);
            done = true;
            return;
        }
        if (tripTicks <= 0) {
            // Couldn't reach the town hall (walled off, fell in a hole) — drop the load where we are
            // so the worker isn't stuck carrying it forever.
            dump(citizen.blockPosition());
            done = true;
            return;
        }
        if (citizen.getNavigation().isDone() || tripTicks % REPATH_INTERVAL == 0) {
            citizen.getNavigation().moveTo(
                townHall.getX() + 0.5, townHall.getY(), townHall.getZ() + 0.5, speedModifier);
        }
    }

    private void dump(BlockPos pos) {
        if (citizen.level() instanceof ServerLevel sl) {
            citizen.dumpHaulAt(sl, pos);
        }
    }

    @Override
    public void stop() {
        citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        citizen.getNavigation().stop();
        done = false;
        townHall = null;
    }

    /** A copy of the first non-empty stack in the carry pack, for the in-hand carry render. */
    private ItemStack firstHaulItem() {
        net.minecraft.world.SimpleContainer pack = citizen.getAnarchyHaul();
        for (int i = 0; i < pack.getContainerSize(); i++) {
            ItemStack s = pack.getItem(i);
            if (!s.isEmpty()) return s.copy();
        }
        return ItemStack.EMPTY;
    }
}
