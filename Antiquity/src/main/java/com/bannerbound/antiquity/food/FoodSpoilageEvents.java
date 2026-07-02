package com.bannerbound.antiquity.food;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.item.FoodSpoilage;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Drives the food-freshness layer server-side: once a second it stamps perishables as fresh and rolls
 * their chance to degrade a level (and finally to spoiled food). This covers what players carry and
 * food dropped on the ground; Core's stored-food scan calls {@link Spoilage#tick} on the same cadence
 * for claimed storage. It also halves what the player gains from eating <b>bland</b> food, the eater
 * side of {@link FoodSpoilage#BLAND_FOOD_MULTIPLIER}.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class FoodSpoilageEvents {
    private FoodSpoilageEvents() {}

    @SubscribeEvent
    static void onEntityTick(EntityTickEvent.Post event) {
        Level level = event.getEntity().level();
        if (level.isClientSide || level.getGameTime() % 20L != 0L) return;

        if (event.getEntity() instanceof Player player) {
            Spoilage.sweep(player.getInventory(), level);
        } else if (event.getEntity() instanceof ItemEntity item) {
            // Don't spoil dropped food lying in a DORMANT settlement's claimed chunk: this vanilla entity
            // tick fires whenever the chunk is loaded (chunkloaders / force-loaded claims / a nearby
            // outsider), so it would keep rotting an offline tribe's food. Unclaimed land spoils as normal.
            if (level instanceof ServerLevel sl) {
                Settlement owner = SettlementData.get(sl)
                    .getByChunk(new ChunkPos(item.blockPosition()).toLong());
                if (owner != null && owner.isDormant()) return;
            }
            ItemStack r = Spoilage.tick(item.getItem(), level);
            if (r != item.getItem()) item.setItem(r);
        }
    }

    /**
     * Stamp dropped/harvested food the instant it enters the world, not a second later when the tick
     * sweep catches it. This matters for stacking: an item picked up while still unstamped can't merge
     * into an already-stamped stack in the player's inventory (their components differ), so it would
     * land in a fresh slot. Stamping at spawn means fresh food shares one component before pickup and
     * merges normally.
     */
    @SubscribeEvent
    static void onItemSpawn(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide) return;
        if (event.getEntity() instanceof ItemEntity item) {
            ItemStack r = Spoilage.stamp(item.getItem());
            if (r != item.getItem()) item.setItem(r);
        }
    }

    // ── Bland food gives the eater half its nourishment ─────────────────────────────────────────
    // Vanilla applies a food's full nutrition+saturation inside finishUsingItem (before Finish fires),
    // so we snapshot the player's hunger/saturation when they START eating a bland food and, on Finish,
    // roll back half of whatever they actually gained. Snapshotting handles vanilla's clamp-at-full
    // (a player near full gains less than the food's rated value, so we halve the real gain, not the
    // rated value). Keyed by UUID; cleared on Stop in case the player cancels the meal.
    private static final Map<UUID, float[]> PRE_EAT = new HashMap<>();

    @SubscribeEvent
    static void onStartEating(LivingEntityUseItemEvent.Start event) {
        if (event.getEntity().level().isClientSide
            || !(event.getEntity() instanceof Player player)
            || !isBland(event.getItem())) {
            return;
        }
        FoodData food = player.getFoodData();
        PRE_EAT.put(player.getUUID(), new float[] { food.getFoodLevel(), food.getSaturationLevel() });
    }

    @SubscribeEvent
    static void onStopEating(LivingEntityUseItemEvent.Stop event) {
        if (event.getEntity() instanceof Player player) {
            PRE_EAT.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    static void onFinishEating(LivingEntityUseItemEvent.Finish event) {
        if (event.getEntity().level().isClientSide || !(event.getEntity() instanceof Player player)) {
            return;
        }
        float[] before = PRE_EAT.remove(player.getUUID());
        if (before == null || !isBland(event.getItem())) return;

        FoodData food = player.getFoodData();
        int gainedFood = food.getFoodLevel() - (int) before[0];
        float gainedSat = food.getSaturationLevel() - before[1];
        if (gainedFood > 0) {
            food.setFoodLevel((int) before[0] + Math.round(gainedFood * FoodSpoilage.BLAND_FOOD_MULTIPLIER));
        }
        if (gainedSat > 0f) {
            food.setSaturation(Math.max(0f, before[1] + gainedSat * FoodSpoilage.BLAND_FOOD_MULTIPLIER));
        }
    }

    private static boolean isBland(ItemStack stack) {
        FoodSpoilage fs = stack.get(BannerboundAntiquity.FOOD_SPOILAGE.get());
        return fs != null && fs.isBland();
    }
}
