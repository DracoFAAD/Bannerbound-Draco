package com.bannerbound.antiquity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.entity.StuckSpear;

import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Lifecycle of spears embedded in a mob (the {@code STUCK_SPEARS} attachment):
 * <ul>
 *   <li><b>Drop on death</b> — each stored spear ItemStack is added to the loot, then the list is
 *       cleared so a non-removed corpse can't re-drop.</li>
 *   <li><b>Pull out by hand</b> — shift + right-click a mob that has a spear in it to yank one back
 *       out (RNG chance), returned as the exact stored spear (full NBT).</li>
 *   <li><b>NPC spears are cosmetic-only</b> — a hunter's thrown copy embeds for the visual but is
 *       {@code !recoverable()}: it never death-drops, can't be pulled (no tool duplication), and is
 *       pruned off the mob once its {@code expireGameTime} passes (arrow-style despawn).</li>
 * </ul>
 * All run on the game bus, server-authoritative. Mirrors {@link AnimalDropsEvents}'s drop pattern.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class StuckSpearEvents {
    private StuckSpearEvents() {}

    @SubscribeEvent
    static void onDropStuckSpears(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        List<StuckSpear> spears = entity.getExistingDataOrNull(BannerboundAntiquity.STUCK_SPEARS.get());
        if (spears == null || spears.isEmpty()) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        for (StuckSpear spear : spears) {
            if (spear.stack().isEmpty() || !spear.recoverable()) {
                continue;   // an NPC hunter's cosmetic spear never yields an item (no tool dup)
            }
            ItemEntity drop = new ItemEntity(level, entity.getX(), entity.getY() + 0.2, entity.getZ(),
                spear.stack().copy());
            drop.setDefaultPickUpDelay();
            event.getDrops().add(drop);
        }
        // Clear so the spears can't drop twice (e.g. a corpse that isn't immediately removed).
        entity.setData(BannerboundAntiquity.STUCK_SPEARS.get(), List.of());
    }

    /** Shift + right-click a speared mob to pull one spear back out — the exact stored spear, NBT
     *  and all. Always succeeds; when several are stuck, a random one comes out. */
    @SubscribeEvent
    static void onPullStuckSpear(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return; // EntityInteract fires per hand — only act once
        }
        Player player = event.getEntity();
        if (!player.isShiftKeyDown() || !(event.getTarget() instanceof LivingEntity mob)) {
            return;
        }
        List<StuckSpear> spears = mob.getExistingDataOrNull(BannerboundAntiquity.STUCK_SPEARS.get());
        // Only a PLAYER'S spear can be pulled — an NPC hunter's cosmetic spear is untouchable (it
        // would duplicate the citizen's reusable tool), so with only those stuck the interaction
        // falls through as if nothing were embedded.
        List<StuckSpear> pullable = spears == null ? List.of()
            : spears.stream().filter(StuckSpear::recoverable).toList();
        if (pullable.isEmpty()) {
            return; // nothing pullable → leave the interaction alone (normal shift-click behaviour)
        }
        // A spear is stuck → this shift-right-click is a pull attempt; consume it so it doesn't also
        // start charging a throw / trigger vanilla entity interaction.
        Level level = event.getLevel();
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (level.isClientSide) {
            return; // server is authoritative for the attachment + item grant
        }
        // Pull a RANDOM one of the pullable spears, returning the exact stored stack.
        StuckSpear pulled = pullable.get(mob.getRandom().nextInt(pullable.size()));
        List<StuckSpear> remaining = new ArrayList<>(spears);
        remaining.remove(pulled);
        mob.setData(BannerboundAntiquity.STUCK_SPEARS.get(), List.copyOf(remaining));
        ItemStack stack = pulled.stack().copy();
        if (!player.addItem(stack)) {
            player.drop(stack, false); // inventory full → drop at the player so it's not lost
        }
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            BannerboundAntiquity.SPEAR_REEL_SOUND.get(), SoundSource.PLAYERS,
            0.8F, 0.8F + mob.getRandom().nextFloat() * 0.2F);
    }

    /** Prune timed-out NPC spears off living mobs (arrow-style despawn). Throttled to every 2 s per
     *  entity; mobs with no attachment pay only a null check. */
    @SubscribeEvent
    static void onPruneExpiredSpears(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity mob) || mob.level().isClientSide()
                || mob.tickCount % 40 != 0) {
            return;
        }
        List<StuckSpear> spears = mob.getExistingDataOrNull(BannerboundAntiquity.STUCK_SPEARS.get());
        if (spears == null || spears.isEmpty()) {
            return;
        }
        long now = mob.level().getGameTime();
        List<StuckSpear> kept = spears.stream().filter(s -> !s.isExpired(now)).toList();
        if (kept.size() != spears.size()) {
            mob.setData(BannerboundAntiquity.STUCK_SPEARS.get(), List.copyOf(kept));
        }
    }
}
