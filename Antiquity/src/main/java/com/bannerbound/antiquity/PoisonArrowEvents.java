package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.antiquity.poison.PoisonType;
import com.bannerbound.antiquity.poison.Poisons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Poison ARROWS aren't a special item — ANY arrow (the {@code #minecraft:arrows} tag) is "tipped" by
 * coating it with a poison paste, which stamps the {@code ARROW_POISON} component (see
 * {@link com.bannerbound.antiquity.item.PoisonPasteItem}). This delivers it on the fired arrow ENTITY,
 * read off the ammo it was shot from: a colour trail in flight, and the poison applied on a creature
 * hit. Works for vanilla AND flint arrows alike (the renderer/model are untouched — the only tell is
 * the open tooltip + the trail).
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class PoisonArrowEvents {
    private PoisonArrowEvents() {}

    /** The poison coating the arrow (from the ammo stack it carries), or {@code null}. */
    @Nullable
    private static PoisonType arrowPoison(AbstractArrow arrow) {
        String id = arrow.getPickupItemStackOrigin().get(BannerboundAntiquity.ARROW_POISON.get());
        return id == null ? null : PoisonType.fromId(id);
    }

    /** Apply the coating's poison when a poison-tipped arrow strikes a creature (vanilla damage still
     *  runs — the event isn't cancelled). */
    @SubscribeEvent
    static void onProjectileImpact(ProjectileImpactEvent event) {
        if (!Config.POISON_ENABLED.get()
            || !(event.getProjectile() instanceof AbstractArrow arrow)
            || arrow.level().isClientSide
            || !(event.getRayTraceResult() instanceof EntityHitResult hit)) {
            return;
        }
        PoisonType poison = arrowPoison(arrow);
        if (poison != null && hit.getEntity() instanceof LivingEntity living && living.isAlive()) {
            // Same friendly-fire rule as curare darts: the kidnap poison never lands on the
            // shooter's own settlement (the arrow itself still hits — only the coating is inert).
            if (poison == PoisonType.CURARE && arrow.getOwner() instanceof LivingEntity shooter
                && CurareDragEvents.sameSettlement(shooter, living)) {
                return;
            }
            Poisons.applyPoison(living, poison);
        }
    }

    /** A poison-tipped arrow trails its poison's colour in flight — server-spawned, so every client
     *  sees the right colour without syncing anything. */
    @SubscribeEvent
    static void onArrowTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof AbstractArrow arrow)
            || !(arrow.level() instanceof ServerLevel server)
            || arrow.getDeltaMovement().lengthSqr() < 0.02) { // only while actually flying, not stuck
            return;
        }
        PoisonType poison = arrowPoison(arrow);
        if (poison == null) {
            return;
        }
        int c = poison.tintColor();
        server.sendParticles(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT,
                ((c >> 16) & 0xFF) / 255.0F, ((c >> 8) & 0xFF) / 255.0F, (c & 0xFF) / 255.0F),
            arrow.getX(), arrow.getY(), arrow.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
    }

    /** Show the coating on any tipped arrow's tooltip — openly (it's a weapon, not a hidden lace). */
    @SubscribeEvent
    static void onTooltip(ItemTooltipEvent event) {
        String id = event.getItemStack().get(BannerboundAntiquity.ARROW_POISON.get());
        PoisonType poison = id == null ? null : PoisonType.fromId(id);
        if (poison != null) {
            event.getToolTip().add(Component.translatable("bannerboundantiquity.poison_arrow.tooltip",
                    Component.translatable("poison.bannerboundantiquity." + poison.id()))
                .withStyle(ChatFormatting.DARK_GREEN));
        }
    }
}
