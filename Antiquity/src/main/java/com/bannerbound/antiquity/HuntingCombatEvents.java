package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.entity.GroundDecalEntity;
import com.bannerbound.antiquity.entity.HuntingFear;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Per-tick + on-hurt hunting logic for vanilla prey animals:
 * <ul>
 *   <li><b>Stamina</b> (persistence hunting): drains while the animal is actively fleeing, regens
 *       when calm; below the tired threshold the flee goal slows it (see {@code FleeFromPlayerGoal}),
 *       so you can run a tiring animal down.</li>
 *   <li><b>Hurt → fear</b>: a player hitting a prey animal spooks it and alarms its herd (and elects
 *       a boar-charger for pigs) — so wounding one scatters the rest.</li>
 *   <li>(Bleeding DoT is added here in Part 7.)</li>
 * </ul>
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class HuntingCombatEvents {
    private HuntingCombatEvents() {}

    @SubscribeEvent
    static void onAnimalTick(EntityTickEvent.Post event) {
        if (!Config.HUNTING_ENABLED.get() || !(event.getEntity() instanceof Animal animal)) {
            return;
        }
        if (!(animal.level() instanceof ServerLevel level)) {
            return;
        }
        if (HuntingFear.isTamed(animal)) {
            return; // tamed livestock are vanilla again — no stamina, bleed, or footprints
        }
        // Stamina: drain while exerting (spooked + actually moving), regenerate otherwise.
        float max = (float) (double) Config.STAMINA_MAX.get();
        float stamina = HuntingFear.getStamina(animal);
        boolean exerting = HuntingFear.isScared(animal) && !animal.getNavigation().isDone();
        if (exerting) {
            if (stamina > 0.0F) {
                HuntingFear.setStamina(animal, stamina - (float) (double) Config.STAMINA_DRAIN_PER_TICK.get());
            }
        } else if (stamina < max) {
            HuntingFear.setStamina(animal, stamina + (float) (double) Config.STAMINA_REGEN_PER_TICK.get());
        }
        // Bleeding: count down; on each interval pulse, deal damage-over-time + spit red blood.
        int bleed = animal.getData(BannerboundAntiquity.BLEED_TICKS.get());
        if (Config.BLEED_ENABLED.get() && bleed > 0) {
            int remaining = bleed - 1;
            animal.setData(BannerboundAntiquity.BLEED_TICKS.get(), remaining);
            if (remaining % Config.BLEED_INTERVAL_TICKS.get() == 0) {
                Entity owner = null;

                String causedBy = animal.getData(BannerboundAntiquity.BLEED_BY.get());

                if (!causedBy.isEmpty()) {
                    try {
                        owner = level.getEntity(java.util.UUID.fromString(causedBy));
                    } catch (IllegalArgumentException e) {
                        // Not a valid UUID; Idk when this would happen but ig yh
                    }
                }

                animal.hurt(animal.damageSources().source(BannerboundAntiquity.BLEEDING_DAMAGE, owner),
                    (float) (double) Config.BLEED_DAMAGE_PER_TICK.get());
                level.sendParticles(BannerboundAntiquity.BLOOD_DROP.get(),
                    animal.getX(), animal.getY() + animal.getBbHeight() * 0.6, animal.getZ(),
                    16, 0.15, 0.1, 0.15, 0.15);
                // Leave a fading blood decal where it's bleeding (the visible blood trail).
                if (Config.BLOOD_SPLAT_ENABLED.get()
                        && animal.getRandom().nextDouble() < Config.BLOOD_SPLAT_CHANCE.get()) {
                    // spawnBlood clamps down to the ground in this column, so it never floats.
                    GroundDecalEntity.spawnBlood(level, animal.getX(), animal.getY(), animal.getZ(),
                        animal.getRandom().nextInt(10000), animal);
                }
            }

            if (remaining == 0) {
                // clear up
                animal.removeData(BannerboundAntiquity.BLEED_BY.get());
            }
        }
        dropFootprints(level, animal);
    }

    /** Walking animals of configured species leave footprint tracks at a regular spacing. */
    private static void dropFootprints(ServerLevel level, Animal animal) {
        if (!Config.FOOTPRINTS_ENABLED.get() || !animal.onGround()) {
            return;
        }
        String species = BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).getPath();
        if (!Config.FOOTPRINT_SPECIES.get().contains(species)) {
            return; // only animals that have a <species>_footprint.png
        }
        double moved = Math.hypot(animal.getX() - animal.xOld, animal.getZ() - animal.zOld);
        if (moved < 0.01) {
            return; // standing still
        }
        float dist = animal.getData(BannerboundAntiquity.FOOTPRINT_DIST.get()) + (float) moved;
        // Longer strides at speed → space tracks out, so a sprinting animal leaves far fewer.
        double spacing = Config.FOOTPRINT_SPACING.get() * (1.0 + Config.FOOTPRINT_SPEED_STRETCH.get() * moved);
        if (dist < spacing) {
            animal.setData(BannerboundAntiquity.FOOTPRINT_DIST.get(), dist);
            return;
        }
        animal.setData(BannerboundAntiquity.FOOTPRINT_DIST.get(), (float) (dist - spacing)); // keep remainder
        if (animal.getRandom().nextDouble() < Config.FOOTPRINT_CHANCE.get()) {
            GroundDecalEntity.spawnTrack(level, animal.getX(), animal.getY(), animal.getZ(),
                species, animal);
        }
    }

    @SubscribeEvent
    static void onPreyHurt(LivingIncomingDamageEvent event) {
        if (!Config.HUNTING_ENABLED.get()) {
            return;
        }
        LivingEntity victim = event.getEntity();
        if (!(victim instanceof Animal animal) || animal instanceof Wolf || animal instanceof Ocelot) {
            return; // prey only — predators keep attacking when hit
        }
        if (!(victim.level() instanceof ServerLevel)) {
            return;
        }
        Entity attacker = event.getSource().getEntity();
        // Players and citizens both count as a hunting threat — a Hunter NPC's melee blow or
        // arrow/spear (the projectile's owner) spooks the victim and scatters its herd, exactly
        // like a player's hit. Other damage (cactus, predators) doesn't read as hunting.
        boolean playerThreat = attacker instanceof Player player
            && !player.isCreative() && !player.isSpectator();
        boolean hunterThreat = attacker instanceof com.bannerbound.core.entity.CitizenEntity;
        if (!playerThreat && !hunterThreat) {
            return;
        }
        spook(animal, Config.SCARED_DURATION_TICKS.get());
    }

    /** Breaking blocks is noisy — spook nearby prey (digging gives you away). */
    @SubscribeEvent
    static void onMiningNoise(BlockEvent.BreakEvent event) {
        if (!Config.HUNTING_ENABLED.get()) {
            return;
        }
        int radius = Config.MINING_NOISE_RADIUS.get();
        Player player = event.getPlayer();
        if (radius <= 0 || player == null || player.isCreative() || player.isSpectator()) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        int ticks = Config.SCARED_DURATION_TICKS.get();
        AABB area = new AABB(event.getPos()).inflate(radius);
        for (Animal animal : level.getEntitiesOfClass(Animal.class, area,
                a -> a.isAlive() && !(a instanceof Wolf) && !(a instanceof Ocelot))) {
            spook(animal, ticks);
        }
    }

    /** Spook an animal and, on the not-scared→scared edge, alarm its herd (+ elect a boar charger). */
    private static void spook(Animal animal, int ticks) {
        if (HuntingFear.isTamed(animal)) {
            return; // domesticated livestock don't get spooked — they're vanilla now
        }
        boolean wasScared = HuntingFear.isScared(animal);
        HuntingFear.scare(animal, ticks);
        if (!wasScared) {
            HuntingFear.alarmHerd(animal, Config.HERD_ALARM_RADIUS.get(), ticks);
            if (animal instanceof Pig pig) {
                HuntingFear.electBoarCharger(pig, Config.HERD_ALARM_RADIUS.get(),
                    Config.BOAR_CHARGE_CLAIM_TICKS.get(), Config.BOAR_CHARGE_CHANCE.get(), animal.getRandom());
            }
        }
    }
}
