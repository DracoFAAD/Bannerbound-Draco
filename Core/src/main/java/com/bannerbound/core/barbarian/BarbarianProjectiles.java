package com.bannerbound.core.barbarian;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;

/**
 * Decoupling seam for barbarian ranged weapons: Core's {@code BarbarianRangedGoal} fires projectiles
 * by id, but the projectile entities (thrown spear, flint arrow) live in Antiquity. Antiquity
 * {@link #register}s a factory per projectile id at setup; Core calls {@link #create} and treats the
 * result as a vanilla {@link AbstractArrow} (owner + damage + aim). Unregistered ids fall back to a
 * generic registry-built arrow, so any {@code AbstractArrow} entity id works without a factory.
 */
public final class BarbarianProjectiles {
    @FunctionalInterface
    public interface Factory {
        /** Build the projectile positioned at the shooter's eye, with owner + base damage set
         *  (the caller aims via {@code shoot} and adds it to the world). */
        AbstractArrow create(ServerLevel level, LivingEntity shooter, double damage);
    }

    private static final Map<ResourceLocation, Factory> FACTORIES = new HashMap<>();

    private BarbarianProjectiles() {
    }

    public static void register(ResourceLocation id, Factory factory) {
        FACTORIES.put(id, factory);
    }

    @Nullable
    public static AbstractArrow create(ServerLevel level, LivingEntity shooter, ResourceLocation id,
                                       double damage) {
        Factory f = FACTORIES.get(id);
        if (f != null) {
            AbstractArrow a = f.create(level, shooter, damage);
            if (a != null) return a;
        }
        // Generic fallback: any AbstractArrow entity id, built from the registry.
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        if (type == null) return null;
        Entity e = type.create(level);
        if (!(e instanceof AbstractArrow arrow)) {
            if (e != null) e.discard();
            return null;
        }
        arrow.setOwner(shooter);
        arrow.setBaseDamage(damage);
        arrow.setPos(shooter.getX(), shooter.getEyeY() - 0.1, shooter.getZ());
        return arrow;
    }
}
