package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Tracks the {@link LivingEntity} whose model (and therefore held item) is being rendered right now.
 *
 * <p>Held-item baked-model wrappers (e.g. {@link SpearHandFlipModel}) only receive an
 * {@code ItemDisplayContext} from {@code applyTransform} — no entity. Keying their per-render decision
 * on a global like {@code Minecraft#player} causes cross-talk: when the local player raises a spear,
 * <i>every</i> spear on screen flips ("rotating one spear rotates all spears"). This holder lets the
 * wrapper key on the actual entity being drawn instead, so each spear flips independently.
 *
 * <p>Set/cleared per entity by {@link SpearRenderEvents} around the living-entity render (third person,
 * covers other players and citizens) and around the first-person hand render (local player). Render
 * thread only, so a plain static field is sufficient.
 */
@OnlyIn(Dist.CLIENT)
public final class HeldItemRenderContext {
    @Nullable
    private static LivingEntity current;

    private HeldItemRenderContext() {
    }

    public static void set(@Nullable LivingEntity entity) {
        current = entity;
    }

    public static void clear() {
        current = null;
    }

    @Nullable
    public static LivingEntity current() {
        return current;
    }
}
