package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.building.PenEnclosure;
import com.bannerbound.core.item.ForemansRodItem;
import com.bannerbound.core.world.SelectionBroadcaster;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Intercepts left-clicks on blocks while the player holds a Foreman's Rod. Always cancels so the rod
 * can't break blocks. A left-click INSIDE a herder pen removes that pen (point selections aren't caught
 * by box containment). Shift-left-click on a block contained by a box selection (digger/farmer field)
 * removes that selection (terraforming undo). Plain left-click elsewhere is a no-op.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class ForemansRodLeftClick {
    private ForemansRodLeftClick() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !stack.is(BannerboundCore.FOREMANS_ROD.get())) return;

        event.setCanceled(true);

        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        Settlement settlement = SettlementData.get(overworld).getByPlayer(player.getUUID());
        if (settlement == null) {
            if (player.isShiftKeyDown()) {
                player.displayClientMessage(
                    Component.translatable("bannerbound.foremans_rod.not_in_settlement")
                        .withStyle(ChatFormatting.RED), true);
            }
            return;
        }

        BlockPos clicked = event.getPos();
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(overworld);

        // Herder pen: a left-click inside its interior removes it (point selections aren't caught by the
        // AABB findContaining used for box selections below).
        BlockSelection pen = herderPenAt(player.serverLevel(), registry, settlement, clicked);
        if (pen != null) {
            registry.unregister(pen.rodId());
            SelectionBroadcaster.broadcast(server);
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.pen_removed").withStyle(ChatFormatting.GREEN), true);
            return;
        }

        if (!player.isShiftKeyDown()) {
            // Plain left-click elsewhere has no role (right-click cycles A/B). Absorb it silently.
            return;
        }

        List<BlockSelection> hits = registry.findContaining(clicked, settlement.id());
        if (hits.isEmpty()) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.no_selection_here")
                    .withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        for (BlockSelection sel : hits) {
            registry.unregister(sel.rodId());
        }
        SelectionBroadcaster.broadcast(server);
        player.displayClientMessage(
            Component.translatable("bannerbound.foremans_rod.selection_removed", hits.size())
                .withStyle(ChatFormatting.GREEN), true);
    }

    /** A herder pen whose marker or interior contains {@code clicked}, owned by {@code settlement}, or null. */
    private static BlockSelection herderPenAt(ServerLevel level, BlockSelectionRegistry registry,
            Settlement settlement, BlockPos clicked) {
        for (BlockSelection sel : registry.getForSettlement(settlement.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION
                || !ForemansRodItem.HERDER_TYPE.equals(sel.workstationType())) {
                continue;
            }
            BlockPos marker = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            if (marker.equals(clicked)) return sel;                   // clicked the marker block itself
            if (marker.distSqr(clicked) > 64 * 64) continue;
            PenEnclosure.Result r = PenEnclosure.scan(level, marker);
            if (r.valid() && (r.interior().contains(clicked) || r.interior().contains(clicked.below()))) {
                return sel;
            }
        }
        return null;
    }
}
