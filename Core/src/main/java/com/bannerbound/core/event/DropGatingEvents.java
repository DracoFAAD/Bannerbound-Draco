package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.research.SettlementDropFilter;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Strips drops a civ doesn't recognize yet — kill a sheep before you know wool and no wool drops;
 * break grass before you know seeds and no seeds drop. The decision is delegated to
 * {@link SettlementDropFilter}: automatic known-set gating plus the {@code drop_overrides}
 * exceptions.
 * <p>
 * Both handlers run at {@link EventPriority#LOW} so they filter the <i>final</i> drop set — after
 * {@link OreBreakHandler} has applied any ore-disguise swap and after
 * {@code AnimalDropsEvents} has added its bone bootstrap (bones survive via an {@code always_drop}
 * override). Worker citizens that harvest with {@code Block.getDrops} bypass these events
 * entirely and apply the same filter at their own collection sites.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class DropGatingEvents {
    private DropGatingEvents() {
    }

    // A multi-block plant (bamboo, scaffolding, cactus, sugar cane, kelp, …) breaks its upper
    // blocks via neighbor-update self-breaks the SAME tick the player breaks the support. Those
    // cascade breaks carry no breaker, so settlementOf(null) is null and every non-starting drop
    // gets stripped — only the first, player-broken block dropped. We remember the settlement of
    // the breaking player and, for a breaker-less break in the same game tick, treat it as part of
    // that player's action so the whole column drops under their research.
    //
    // CRITICAL ordering: the cascade's drop events fire DURING removeBlock of the player's block,
    // which is BEFORE the player's own block-drop event. So we must record the player at
    // BlockEvent.BreakEvent (fired at the start of the break, before removal), not at the player's
    // BlockDropsEvent — otherwise the cascade runs before we've recorded anything.
    private static Settlement lastBreakSettlement;
    private static long lastBreakTick = Long.MIN_VALUE;

    /** Record the breaking player BEFORE the block is removed, so the breaker-less cascade drops it
     *  triggers this same tick can inherit the player's settlement. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBlockBreakStart(BlockEvent.BreakEvent event) {
        if (event.getPlayer() == null) {
            return;
        }
        Level level = event.getPlayer().level();
        if (level.isClientSide()) {
            return;
        }
        lastBreakSettlement = SettlementDropFilter.settlementOf(event.getPlayer());
        lastBreakTick = level.getGameTime();
    }

    /** Player block break: filter the spawned drops against the breaker's settlement. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onBlockDrops(BlockDropsEvent event) {
        Level level = event.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        Entity breaker = event.getBreaker();
        Settlement settlement = SettlementDropFilter.settlementOf(breaker);
        long tick = level.getGameTime();
        if (breaker != null) {
            lastBreakSettlement = settlement;
            lastBreakTick = tick;
        } else if (settlement == null && tick == lastBreakTick) {
            // Cascade break from a player's action this tick — inherit their settlement.
            settlement = lastBreakSettlement;
        }
        com.bannerbound.core.api.research.InsightManager.recordEvent(
            level.getServer(), settlement, "mine_block",
            com.bannerbound.core.api.research.InsightManager.matcherFor(event.getState().getBlock()), 1);
        ResourceLocation sourceId = BuiltInRegistries.BLOCK.getKey(event.getState().getBlock());
        SettlementDropFilter.filterEntities(settlement, sourceId, event.getDrops());
    }

    /** Mob death: filter the spawned drops against the killer's settlement (null = wild death,
     *  which leaves only starting items). */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLivingDrops(LivingDropsEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        Entity killer = event.getSource() == null ? null : event.getSource().getEntity();
        Settlement settlement = SettlementDropFilter.settlementOf(killer);
        com.bannerbound.core.api.research.InsightManager.recordEvent(
            event.getEntity().getServer(), settlement, "kill_entity",
            com.bannerbound.core.api.research.InsightManager.matcherFor(event.getEntity().getType()), 1);
        ResourceLocation sourceId = BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType());
        SettlementDropFilter.filterEntities(settlement, sourceId, event.getDrops());
    }
}
