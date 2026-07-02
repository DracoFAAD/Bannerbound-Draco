package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.TownHallFoodDeposits;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Catches the shift-right-click-with-food-on-the-settlement's-town-hall interaction <i>before</i>
 * vanilla's sneak-skip-block-use rule fires (which would otherwise route the click to the food
 * item's {@code use()} = eating, silently losing the deposit). Also runs at {@link
 * EventPriority#HIGH} so it fires before {@code FactionEvents.onCampfireRightClick}, which
 * normally handles the campfire shift-click flow (promoting a campfire to town hall) — when the
 * campfire is already the town hall and the player holds food, we take precedence and convert
 * the click into a food deposit.
 *
 * <p>The town hall is a vanilla {@code CampfireBlock} whose position lives on
 * {@code Settlement.townHallPos()}; the deposit math itself runs through
 * {@link TownHallFoodDeposits#tryDepositFood}, which is block-agnostic — it only requires the click pos
 * to match the settlement's town hall pos.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class TownHallFoodDepositEvents {
    private TownHallFoodDepositEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!player.isShiftKeyDown()) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        // Fast bail: only food items should ever be deposited. Cheap lookup; if zero, the click
        // wasn't ours to handle — let FactionEvents (campfire promote) and vanilla (eat) compete.
        float value = com.bannerbound.core.api.settlement.data.FoodValueLoader.base(stack.getItem());
        if (value <= 0f) return;

        Level level = event.getLevel();
        BlockPos pos = event.getPos();

        if (level.isClientSide) {
            // Mirror the server's decision so the client doesn't start the eat-animation when the
            // server is about to cancel anyway. Suppress on the client whenever shift-clicking food
            // (tiny false-positive: a shift-click-food on a non-town-hall block won't visually try
            // to eat for one frame; the server still allows the eat because it doesn't cancel there).
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (!(level instanceof ServerLevel sl)) return;
        MinecraftServer server = serverPlayer.getServer();
        if (server == null) return;

        // Is this position the player's settlement's town hall? Cheaper than tryDepositFood's full
        // check — early-out lets a shift-click-food on someone else's campfire fall through to
        // FactionEvents (which lets vanilla campfire interaction happen).
        SettlementData data = SettlementData.get(server.overworld());
        Settlement playerSettlement = data.getByPlayer(serverPlayer.getUUID());
        if (playerSettlement == null) return;
        BlockPos townHallPos = playerSettlement.townHallPos();
        if (townHallPos == null || !townHallPos.equals(pos)) return;
        Settlement chunkOwner = data.getByChunk(new ChunkPos(pos).toLong());
        if (chunkOwner == null || !chunkOwner.id().equals(playerSettlement.id())) return;

        if (TownHallFoodDeposits.tryDepositFood(serverPlayer, sl, pos, stack)) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }
}
