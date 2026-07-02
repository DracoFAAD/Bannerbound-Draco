package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Gates a player's own use of bone meal behind the {@code bannerbound.allow_fertilizing} flag
 * (granted by the Fertilization research). Bone meal stays craftable and holdable — only the
 * gesture of applying it to a block is blocked until researched, exactly as {@link PlantingGate}
 * gates tilling/sowing and {@link PavingGate} gates path-making. This is the player-side mirror
 * of the same flag {@code FarmerWorkGoal} checks before letting a farmer fertilize crops.
 * Unsettled players (no settlement) are gated too — same rule as paving and planting.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class FertilizingGate {
    private static final String FLAG = "bannerbound.allow_fertilizing";
    /** Fertilizer items whose APPLICATION is gated by the Fertilization research. Core ships this tag with
     *  {@code minecraft:bone_meal}; Antiquity merges in its {@code dung} (the bone-meal-style fertilizer) —
     *  recognised by tag so the gate stays self-contained and any addon's fertilizer is covered. */
    private static final TagKey<Item> FERTILIZER = TagKey.create(Registries.ITEM,
        ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "fertilizer"));

    private FertilizingGate() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!event.getItemStack().is(FERTILIZER)) return;
        if (settlementAllows(player)) return;
        event.setCanceled(true);
        player.displayClientMessage(
            Component.translatable("bannerbound.fertilizing.locked").withStyle(ChatFormatting.RED),
            true);
    }

    private static boolean settlementAllows(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return true; // fail-open if no server context
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        return s != null && ResearchManager.hasFlag(s, FLAG);
    }
}
