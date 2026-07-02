package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.vanilla.VanillaContentState;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Seals the Nether and the End when vanilla content is stripped
 * ({@link VanillaContentState#isEnabled()} is false): nether-portal frames can't be lit and end
 * portals can't be completed in survival. Creative/op players bypass (so the dimensions stay
 * reachable for testing).
 *
 * <ul>
 *   <li>{@link BlockEvent.PortalSpawnEvent} — a source-agnostic backstop that cancels any nether
 *       portal forming (fire spread, etc.), with no nice message.</li>
 *   <li>{@link PlayerInteractEvent.RightClickBlock} — the player-facing path: cancels lighting
 *       obsidian and seating an ender eye into an empty frame, with a "locked" actionbar.</li>
 * </ul>
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class VanillaPortalGate {
    private VanillaPortalGate() {
    }

    @SubscribeEvent
    public static void onPortalSpawn(BlockEvent.PortalSpawnEvent event) {
        if (VanillaContentState.isEnabled()) return;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (VanillaContentState.isEnabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        BlockState bs = event.getLevel().getBlockState(event.getPos());
        ItemStack held = event.getItemStack();

        boolean igniting = (held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE))
            && bs.is(Blocks.OBSIDIAN);
        boolean seatingEye = held.is(Items.ENDER_EYE)
            && bs.is(Blocks.END_PORTAL_FRAME)
            && !bs.getValue(BlockStateProperties.EYE);
        if (!igniting && !seatingEye) return;

        if (bypass(player)) return; // creative or op may still force a portal for testing
        event.setCanceled(true);
        player.displayClientMessage(
            Component.translatable("bannerbound.vanilla.portal_locked").withStyle(ChatFormatting.RED),
            true);
    }

    private static boolean bypass(ServerPlayer player) {
        return player.hasPermissions(2) || player.getAbilities().instabuild;
    }
}
