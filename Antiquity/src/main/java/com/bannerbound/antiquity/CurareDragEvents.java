package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.entity.BlowdartProjectile;
import com.bannerbound.antiquity.poison.PoisonType;
import com.bannerbound.antiquity.poison.Poisons;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.HerderWorkGoal;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * The curare "kidnap" drag: right-click a curare-UNCONSCIOUS creature with a fiber rope (any
 * {@code #bannerbound:herder_rope} item) to tether it, then walk — it's towed behind you. Works on
 * animals, citizens (clean, server-authoritative), and players (immobilised first so they have no
 * input authority, then towed via velocity packets + a teleport catch-up for big gaps — slightly
 * rubber-bandy, as players can't be vanilla-leashed). The link is the synced {@code DRAGGED_BY} (on
 * the victim, drives the rope render) + server-only {@code DRAGGING} (on the dragger, drives the tow).
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class CurareDragEvents {
    private static final double FOLLOW_DIST = 2.0;    // the victim trails this far behind
    private static final double DRAG_SPEED = 0.4;     // blocks/tick pull cap
    private static final double TELEPORT_DIST = 4.0;  // beyond this, snap a player closer (client authority)
    private static final double MAX_TETHER = 8.0;     // beyond this the rope "snaps" and releases

    private CurareDragEvents() {}

    /** Grab/release: a PLAIN (non-shift) rope click on a curare-unconscious target toggles the drag.
     *  Shift is reserved for FiberRopeItem's spear-reel, and the antidote-cure uses shift too. */
    @SubscribeEvent
    static void onRopeGrab(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (player.isShiftKeyDown() || !HerderWorkGoal.isRope(event.getItemStack())
            || !(event.getTarget() instanceof LivingEntity target)
            || !Poisons.isCurareUnconscious(target, target.level().getGameTime())) {
            return;
        }
        // No kidnapping your own settlement's members — cross-settlement/neutral targets only.
        // The click is still consumed so the rope doesn't fall through to another interaction.
        if (!player.level().isClientSide && !sameSettlement(player, target)) {
            int cur = target.getData(BannerboundAntiquity.DRAGGED_BY.get());
            if (cur == player.getId()) {
                release(player, target); // re-click my own victim → let go
            } else {
                if (cur != 0 && target.level().getEntity(cur) instanceof LivingEntity prev) {
                    prev.setData(BannerboundAntiquity.DRAGGING.get(), 0); // steal from a previous dragger
                }
                target.setData(BannerboundAntiquity.DRAGGED_BY.get(), player.getId());
                player.setData(BannerboundAntiquity.DRAGGING.get(), target.getId());
            }
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(player.level().isClientSide));
    }

    /** Friendly-fire guard on the kidnap poison's delivery: a curare dart shot at a member of the
     *  shooter's OWN settlement (player or citizen) passes through harmlessly — same rule as the
     *  drag grab above, so members can't be put under by their own. Other poisons are unaffected. */
    @SubscribeEvent
    static void onDartImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof BlowdartProjectile dart) || dart.level().isClientSide
            || dart.getPoison() != PoisonType.CURARE
            || !(event.getRayTraceResult() instanceof EntityHitResult hit)
            || !(hit.getEntity() instanceof LivingEntity target)
            || !(dart.getOwner() instanceof LivingEntity shooter)
            || !sameSettlement(shooter, target)) {
            return;
        }
        event.setCanceled(true);
    }

    /** True when both entities resolve to the SAME settlement (players via the member roster,
     *  citizens via their own link). Unsettled/unresolvable entities are never "same settlement",
     *  so neutral kidnapping keeps working. Mirrors {@link LeashRopeEvents#leashingUnlocked}'s
     *  defensive settlement resolution. */
    static boolean sameSettlement(LivingEntity a, LivingEntity b) {
        Settlement sa = settlementOf(a);
        if (sa == null) {
            return false;
        }
        Settlement sb = settlementOf(b);
        return sb != null && sa.id().equals(sb.id());
    }

    private static Settlement settlementOf(LivingEntity e) {
        if (e instanceof CitizenEntity citizen) {
            return citizen.getSettlement();
        }
        if (e instanceof ServerPlayer sp) {
            MinecraftServer server = sp.getServer();
            if (server == null) {
                return null;
            }
            try {
                return SettlementData.get(server.overworld()).getByPlayer(sp.getUUID());
            } catch (Exception ex) {
                return null; // no settlement / not loaded → treat as unsettled
            }
        }
        return null;
    }

    /** Tow the dragged victim toward the dragger each tick (run from the dragger's own tick). */
    @SubscribeEvent
    static void onDraggerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Player dragger) || dragger.level().isClientSide) {
            return;
        }
        int victimId = dragger.getData(BannerboundAntiquity.DRAGGING.get());
        if (victimId == 0) {
            return;
        }
        Entity e = dragger.level().getEntity(victimId);
        long now = dragger.level().getGameTime();
        if (!(e instanceof LivingEntity victim) || !victim.isAlive()
            || !Poisons.isCurareUnconscious(victim, now)
            || !holdsRope(dragger)
            || dragger.distanceTo(victim) > MAX_TETHER) {
            release(dragger, e instanceof LivingEntity lv ? lv : null);
            return;
        }
        tow(dragger, victim);
    }

    private static void tow(Player dragger, LivingEntity victim) {
        Vec3 toDragger = new Vec3(dragger.getX() - victim.getX(), 0.0, dragger.getZ() - victim.getZ());
        double dist = toDragger.length();
        if (dist <= FOLLOW_DIST) {
            return; // close enough — let it rest (trailing behind)
        }
        Vec3 dir = toDragger.scale(1.0 / dist);
        Vec3 pull = dir.scale(Math.min(DRAG_SPEED, dist - FOLLOW_DIST));
        if (victim instanceof ServerPlayer sp) {
            // The player is already rooted (speed -1) so they can't fight this; nudge, and for a big
            // gap force a teleport to a trailing point (velocity packets alone lag/rubber-band).
            sp.setDeltaMovement(pull.x, sp.getDeltaMovement().y, pull.z);
            sp.hurtMarked = true;
            if (dist > TELEPORT_DIST) {
                Vec3 tp = dragger.position().subtract(dir.scale(FOLLOW_DIST));
                sp.connection.teleport(tp.x, dragger.getY(), tp.z, sp.getYRot(), sp.getXRot());
            }
        } else {
            if (victim instanceof PathfinderMob mob) {
                mob.getNavigation().stop();
            }
            victim.setDeltaMovement(pull.x, victim.getDeltaMovement().y, pull.z);
            victim.hurtMarked = true;
        }
    }

    private static boolean holdsRope(Player dragger) {
        return HerderWorkGoal.isRope(dragger.getMainHandItem())
            || HerderWorkGoal.isRope(dragger.getOffhandItem());
    }

    /** Drop both ends of the rope link. */
    static void release(Player dragger, LivingEntity victim) {
        dragger.setData(BannerboundAntiquity.DRAGGING.get(), 0);
        if (victim != null) {
            victim.setData(BannerboundAntiquity.DRAGGED_BY.get(), 0);
        }
    }
}
