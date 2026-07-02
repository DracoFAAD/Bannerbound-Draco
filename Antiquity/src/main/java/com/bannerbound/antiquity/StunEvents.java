package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.combat.BluntStun;
import com.bannerbound.antiquity.tannery.WeaponCategory;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Blunt weapons stagger on a crit. When a player lands a critical hit with anything in
 * {@code #bannerboundantiquity:blunt_weapons} (the bone club, the smithing hammers), the struck
 * creature is stunned for 1s — half movement speed plus, for a struck player, a blurred-vision daze
 * (see {@link BluntStun}). A single shared handler so every present and future blunt weapon gets it
 * for free by sitting in the tag.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class StunEvents {
    private StunEvents() {}

    /** A crit with a blunt weapon staggers the target. Fires on both sides; {@link BluntStun#stun}
     *  is server-gated, so the client call is a harmless no-op. */
    @SubscribeEvent
    static void onBluntCrit(CriticalHitEvent event) {
        if (!event.isCriticalHit()
            || !event.getEntity().getMainHandItem().is(WeaponCategory.BLUNT_WEAPONS)
            || !(event.getTarget() instanceof LivingEntity target)) {
            return;
        }
        BluntStun.stun(target);
    }

    /** Expire the stagger once its deadline passes (clears the half-speed modifier). Cheap bail for
     *  the common un-stunned entity: a single attachment read returning the 0L default. */
    @SubscribeEvent
    static void onLivingTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof LivingEntity living && !living.level().isClientSide
            && living.getData(BannerboundAntiquity.STUN_UNTIL.get()) > 0L) {
            BluntStun.tick(living);
        }
    }
}
