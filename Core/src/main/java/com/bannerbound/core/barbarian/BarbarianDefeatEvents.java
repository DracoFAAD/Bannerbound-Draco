package com.bannerbound.core.barbarian;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.FactionBanner;
import com.bannerbound.core.entity.BarbarianEntity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Wires the two camp-defeat conditions to the camp manager: a barbarian COMMANDER dying advances
 * defeat progress, and breaking a camp's STANDARD razes its banner. When both are satisfied the camp
 * is permanently cleared (see {@code BarbarianCampManager.checkDefeat}). Banner-break detection keys
 * off {@code BarbarianData.bannerAt(pos)}, so a camp standard is never confused with a settlement
 * banner (handled separately by {@code FactionBannerEvents}).
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class BarbarianDefeatEvents {
    private BarbarianDefeatEvents() {
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof BarbarianEntity b)) return;
        if (!(b.level() instanceof ServerLevel sl) || sl.dimension() != Level.OVERWORLD) return;
        if (b.isMessenger()) {
            MessengerManager.onMessengerKilled(sl, b); // killing an envoy = hard refusal
            return;
        }
        BarbarianCampManager.onBarbarianDeath(sl, b);
    }

    @SubscribeEvent
    public static void onBannerBroken(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel sl) || sl.dimension() != Level.OVERWORLD) return;
        if (!FactionBanner.isBanner(event.getState())) return; // cheap filter before the camp lookup
        BarbarianCampManager.onBannerBroken(sl, event.getPos());
    }
}
