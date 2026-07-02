package com.bannerbound.core.api.research;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.research.data.DropOverrideLoader;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Decides whether an individual dropped stack is allowed to reach the world / a worker's
 * drop-off, given the civ that caused the drop. The rule, per stack:
 * <ol>
 *   <li>A {@code never_drop} override (from {@link DropOverrideLoader}) always strips it.</li>
 *   <li>An {@code always_drop} override always keeps it.</li>
 *   <li>Otherwise it's kept only if the settlement knows the item ({@link ItemKnowledge}).</li>
 * </ol>
 * A {@code null} settlement means no civ context, so only globally-known starting items survive.
 * <p>
 * This is the shared chokepoint behind both the player-facing drop events
 * ({@link com.bannerbound.core.event.DropGatingEvents}) and the worker collection sites
 * (forester / digger / fisher), which compute their drops with {@code Block.getDrops} and never
 * fire those events.
 */
public final class SettlementDropFilter {
    private SettlementDropFilter() {
    }

    /**
     * @param settlement the civ that caused the drop, or null for no civ context
     * @param sourceId   id of the broken block or killed entity (for scoped never_drop overrides);
     *                   null when the source is unknown (e.g. a felling-tree particle drop)
     * @param stack      the candidate drop
     * @return true if {@code stack} is allowed to drop
     */
    public static boolean shouldDrop(@Nullable Settlement settlement, @Nullable ResourceLocation sourceId,
                                     ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String source = sourceId == null ? null : sourceId.toString();
        DropOverrideLoader.Decision decision = itemId == null
            ? DropOverrideLoader.Decision.DEFAULT
            : DropOverrideLoader.decide(itemId.toString(), source);
        return switch (decision) {
            case NEVER_DROP -> false;
            case ALWAYS_DROP -> true;
            case DEFAULT -> ItemKnowledge.isKnown(settlement, stack.getItem());
        };
    }

    /** Removes any disallowed stack from a list of raw {@link ItemStack} drops, in place. */
    public static void filterStacks(@Nullable Settlement settlement, @Nullable ResourceLocation sourceId,
                                    List<ItemStack> drops) {
        drops.removeIf(stack -> !shouldDrop(settlement, sourceId, stack));
    }

    /** Removes any disallowed {@link ItemEntity} from a drops collection, in place. Used by the
     *  block/living drop events, whose drops are already spawned as entities. */
    public static void filterEntities(@Nullable Settlement settlement, @Nullable ResourceLocation sourceId,
                                      java.util.Collection<ItemEntity> drops) {
        drops.removeIf(item -> !shouldDrop(settlement, sourceId, item.getItem()));
    }

    /**
     * Resolves the civ that should own a drop caused by {@code entity}: a player's settlement, a
     * worker citizen's settlement, or null for anything else (wild mobs, no-settlement players).
     */
    @Nullable
    public static Settlement settlementOf(@Nullable Entity entity) {
        if (entity instanceof Projectile projectile && projectile.getOwner() != null) {
            return settlementOf(projectile.getOwner());
        }
        if (entity instanceof CitizenEntity citizen) {
            return citizen.getSettlement();
        }
        if (entity instanceof ServerPlayer player) {
            MinecraftServer server = player.getServer();
            if (server == null) {
                return null;
            }
            try {
                return SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
            } catch (Exception ex) {
                return null;
            }
        }
        return null;
    }
}
