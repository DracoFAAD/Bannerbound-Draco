package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.vanilla.VanillaContentState;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Gates opening vanilla chests and barrels behind research, so an early settlement relies on
 * baskets / the stockpile (unlocked by {@code storage_logistics}) and can't crack open structure
 * loot chests for free. Only active when vanilla content is stripped
 * ({@link VanillaContentState#isEnabled()} is false — always so under Antiquity).
 *
 * <ul>
 *   <li>Barrel → {@code bannerbound.unlock.barrel} (the {@code barrel_making} research, Antiquity era).</li>
 *   <li>Chest / trapped chest → {@code bannerbound.unlock.chest} (the {@code joinery} research,
 *       Medieval, iron-gated).</li>
 * </ul>
 *
 * <p>Mirrors {@code FertilizingGate}: per-settlement flag check, unsettled players are gated too.
 * The block stays breakable/placeable — only the GUI-open gesture is blocked. Sneaking is left
 * alone so a held block can be placed against a chest.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class VanillaStorageGate {
    private static final String FLAG_BARREL = "bannerbound.unlock.barrel";
    private static final String FLAG_CHEST = "bannerbound.unlock.chest";

    private VanillaStorageGate() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (VanillaContentState.isEnabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isShiftKeyDown()) return; // sneaking never opens the GUI; allow place-against

        Block block = event.getLevel().getBlockState(event.getPos()).getBlock();
        final String flag;
        if (block == Blocks.BARREL) {
            flag = FLAG_BARREL;
        } else if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            flag = FLAG_CHEST;
        } else {
            return;
        }

        if (settlementHasFlag(player, flag)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        player.displayClientMessage(
            Component.translatable("bannerbound.vanilla.storage_locked").withStyle(ChatFormatting.RED),
            true);
    }

    private static boolean settlementHasFlag(ServerPlayer player, String flag) {
        MinecraftServer server = player.getServer();
        if (server == null) return true; // fail-open if no server context
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        return s != null && ResearchManager.hasFlag(s, flag);
    }
}
