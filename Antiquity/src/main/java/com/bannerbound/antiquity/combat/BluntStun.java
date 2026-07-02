package com.bannerbound.antiquity.combat;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The blunt-weapon CRIT STUN — the shared effect every blunt weapon (the bone club, the smithing
 * hammers; everything in {@code #bannerboundantiquity:blunt_weapons}) lands on a critical hit. The
 * struck victim is staggered for {@link #STUN_TICKS} (1s): movement is cut in half (a transient
 * {@code MOVEMENT_SPEED} modifier, the {@link com.bannerbound.antiquity.poison.Poisons} paralysis
 * pattern) and — for a struck player — their vision blurs in and out client-side (driven off the
 * synced {@link BannerboundAntiquity#STUN_UNTIL} deadline by {@code StunClientEffects}).
 *
 * <p>Server-side only. {@code StunEvents} arms it on {@link CriticalHitEvent} and clears the speed
 * modifier once the deadline passes.
 */
public final class BluntStun {
    private BluntStun() {}

    /** Stagger length: 1s. */
    public static final int STUN_TICKS = 20;

    /** How hard the stagger cuts movement (−50% of base speed). */
    private static final double STUN_SLOW = -0.5;

    private static final ResourceLocation STUN_SPEED_ID =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "blunt_stun_speed");

    /** Whether {@code victim} is currently mid-stagger (synced, usable on both sides). */
    public static boolean isStunned(LivingEntity victim, long now) {
        return now < victim.getData(BannerboundAntiquity.STUN_UNTIL.get());
    }

    /** Land (or refresh) a 1s stun on {@code victim}: set the synced deadline and pin the half-speed
     *  modifier. Server-side; a no-op on the client. Re-stunning just re-bases the 1s window. */
    public static void stun(LivingEntity victim) {
        if (victim.level().isClientSide) {
            return;
        }
        long until = victim.level().getGameTime() + STUN_TICKS;
        victim.setData(BannerboundAntiquity.STUN_UNTIL.get(), until);
        applySlow(victim);
        // Drop a mob's current path so the stagger actually reads as a hitch, not just a slow crawl.
        if (victim instanceof PathfinderMob mob) {
            mob.getNavigation().stop();
        }
        if (victim instanceof ServerPlayer player) {
            player.setSprinting(false);
        }
    }

    /** Per-tick upkeep (called only while {@code STUN_UNTIL} is set): once the deadline passes, clear
     *  the speed modifier and zero the deadline so the victim recovers fully. */
    public static void tick(LivingEntity victim) {
        long until = victim.getData(BannerboundAntiquity.STUN_UNTIL.get());
        if (until <= 0L) {
            return;
        }
        if (victim.level().getGameTime() >= until) {
            victim.setData(BannerboundAntiquity.STUN_UNTIL.get(), 0L);
            clearSlow(victim);
        } else {
            applySlow(victim); // keep it pinned (idempotent) in case the attribute was rebuilt
            if (victim instanceof ServerPlayer player) {
                player.setSprinting(false);
            }
        }
    }

    private static void applySlow(LivingEntity victim) {
        AttributeInstance speed = victim.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null && speed.getModifier(STUN_SPEED_ID) == null) {
            speed.addTransientModifier(new AttributeModifier(STUN_SPEED_ID, STUN_SLOW,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    private static void clearSlow(LivingEntity victim) {
        AttributeInstance speed = victim.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(STUN_SPEED_ID);
        }
    }
}
