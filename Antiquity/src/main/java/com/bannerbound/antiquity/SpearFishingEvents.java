package com.bannerbound.antiquity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.entity.SpearProjectile;
import com.bannerbound.antiquity.entity.SpearedFishEntity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Spear fishing: when a thrown {@link SpearProjectile} kills a fish, replace the loose item drops
 * (the fish, its drops, and the spear) with a single floating {@link SpearedFishEntity} — the spear
 * with the fish impaled on its tip, bobbing at the surface. Walking over the catch grants everything
 * at once (see {@link SpearedFishEntity}); it's a purely visual/immersion change — the bundled items
 * are exactly what would otherwise have dropped.
 *
 * <p>Runs on the death-drop event, which fires <i>inside</i> {@code fish.hurt(...)} while the spear
 * projectile still exists — so the spear is read straight off the damage source. The killing-blow
 * branch in {@link SpearProjectile#onHitEntity} skips its own spear drop for fish, so the spear isn't
 * duplicated. Mirrors {@link StuckSpearEvents}'s {@code LivingDropsEvent} pattern.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class SpearFishingEvents {
    private SpearFishingEvents() {}

    @SubscribeEvent
    static void onFishSpeared(LivingDropsEvent event) {
        if (!Config.SPEAR_FISHING_ENABLED.get()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractFish fish)) {
            return; // fish only
        }
        if (!(event.getSource().getDirectEntity() instanceof SpearProjectile spear)) {
            return; // only spear kills convert — melee / other deaths drop normally
        }
        if (!(fish.level() instanceof ServerLevel level)) {
            return;
        }

        // Capture what the fish would have dropped, then suppress the loose drops.
        List<ItemStack> drops = new ArrayList<>();
        for (ItemEntity itemEntity : event.getDrops()) {
            ItemStack stack = itemEntity.getItem();
            if (!stack.isEmpty()) {
                drops.add(stack.copy());
            }
        }
        event.getDrops().clear();

        String fishType = BuiltInRegistries.ENTITY_TYPE.getKey(fish.getType()).toString();
        // Variant rendering is a follow-up; store 0 for now (renderer uses a representative look).
        SpearedFishEntity catchEntity = new SpearedFishEntity(level,
            fish.getX(), fish.getY() + fish.getBbHeight() * 0.5, fish.getZ(),
            spear.getSpearItem(), fishType, 0, drops);
        // Orient the catch the way the spear was travelling when it struck (not a fixed planted pose).
        catchEntity.setPierce(spear.getYRot(), spear.getXRot());
        // Rope-tethered kill: hand the tether off from the (now-discarded) spear to the floating
        // catch, so the green rope stays attached with no gap and the catch is reelable.
        if (spear.isRopeTethered() && spear.getOwner() != null) {
            catchEntity.setTether(spear.getOwner());
        }
        level.addFreshEntity(catchEntity);
    }

    /**
     * Empty-hand reel-in when shift-right-clicking while looking at a block (e.g. the seabed through
     * the water). {@code RightClickEmpty} only fires for clicks at <i>air</i>, so it misses the common
     * "looking down at the water" case — but a block right-click reaches the server, so we reel here.
     * Server-authoritative; the rope already spent on the throw is what you're pulling, so no item is
     * needed in hand. (Held-rope reel is handled by {@code FiberRopeItem.use}.)
     */
    @SubscribeEvent
    static void onEmptyHandReel(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || event.getLevel().isClientSide) {
            return;
        }
        Player player = event.getEntity();
        if (!player.isShiftKeyDown() || !event.getItemStack().isEmpty()) {
            return; // empty-hand only — the rope-in-hand path is FiberRopeItem.use
        }
        if (SpearFishing.startReel(player)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }
}
