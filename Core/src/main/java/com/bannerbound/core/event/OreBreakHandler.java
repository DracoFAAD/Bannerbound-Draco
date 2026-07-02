package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.research.OreDisguise;
import com.bannerbound.core.api.research.data.OreDisguiseLoader;
import com.bannerbound.core.api.research.ResearchManager;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

/**
 * Server-side enforcement for disguised ores: when a player breaks an ore their settlement
 * hasn't unlocked, the drop is swapped to the disguise block (stone/deepslate) with zero XP,
 * and the break speed is adjusted to match the disguise. Together with the client-side
 * BlockModelShaper mixin, the ore is indistinguishable from regular rock until research.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class OreBreakHandler {
    private OreBreakHandler() {
    }

    /**
     * Server-side: when a hidden ore is broken, run the DISGUISE block's loot table instead of
     * the real ore's. Stone drops cobblestone, deepslate drops cobbled_deepslate, silk touch
     * still gives the smooth variant — all the vanilla rules apply because we're literally
     * asking "what would mining stone here drop?".
     */
    @SubscribeEvent
    public static void onBlockDrops(BlockDropsEvent event) {
        if (event.getLevel() == null || event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getBreaker() instanceof ServerPlayer sp)) {
            return;
        }
        BlockState state = event.getState();
        OreDisguise disguise = OreDisguiseLoader.getDisguiseFor(state.getBlock());
        if (disguise == null) {
            return;
        }
        if (isOreRevealedForPlayer(sp, disguise)) {
            return;
        }

        Block disguiseBlock = resolveBlock(disguise.disguiseId());
        BlockPos pos = event.getPos();
        ServerLevel level = event.getLevel();
        event.getDrops().clear();
        event.setDroppedExperience(0);
        if (disguiseBlock == null || disguiseBlock == Blocks.AIR) {
            return;
        }
        BlockState disguiseState = disguiseBlock.defaultBlockState();

        // Public static helper that builds the LootParams and runs the disguise's loot table.
        // Honors the player's tool (silk touch / fortune flow through correctly).
        List<ItemStack> disguiseDrops;
        try {
            disguiseDrops = Block.getDrops(disguiseState, level, pos, null, sp, sp.getMainHandItem());
        } catch (Exception ex) {
            return;
        }
        for (ItemStack stack : disguiseDrops) {
            if (stack.isEmpty()) continue;
            ItemEntity drop = new ItemEntity(level,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                stack);
            event.getDrops().add(drop);
        }
    }

    /**
     * Override vanilla's "can this tool harvest this block?" check so a disguised iron ore is
     * harvestable by whatever tool can mine its disguise (e.g. wooden pickaxe for stone).
     * Without this, breaking with the wrong tool would silently destroy the block AND skip the
     * entire drop path — BlockDropsEvent never fires, the player gets nothing.
     */
    @SubscribeEvent
    public static void onHarvestCheck(PlayerEvent.HarvestCheck event) {
        Player player = event.getEntity();
        BlockState state = event.getTargetBlock();
        Block block = state.getBlock();

        OreDisguise disguise = resolveDisguiseForBoth(player, block);
        if (disguise == null) {
            return;
        }
        if (!isDisguisedForPlayer(player, block, disguise)) {
            return;
        }

        Block disguiseBlock = resolveBlock(disguise.disguiseId());
        if (disguiseBlock == null) {
            return;
        }
        BlockState disguiseState = disguiseBlock.defaultBlockState();
        // Check the actual held tool directly (avoids re-entering canHarvestBlock and re-firing this event).
        boolean canHarvestDisguise = !disguiseState.requiresCorrectToolForDrops()
            || player.getMainHandItem().isCorrectToolForDrops(disguiseState);
        event.setCanHarvest(canHarvestDisguise);
    }

    /**
     * Both sides: override the break speed so a disguised iron ore feels like stone, not like
     * iron ore. The HarvestCheck override above fixes whether drops happen; this one fixes how
     * long mining takes (otherwise the player can tell something's off from the slow animation).
     */
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        Player player = event.getEntity();
        BlockState state = event.getState();
        Block block = state.getBlock();

        OreDisguise disguise = resolveDisguiseForBoth(player, block);
        if (disguise == null) {
            return;
        }
        if (!isDisguisedForPlayer(player, block, disguise)) {
            return;
        }

        Block disguiseBlock = resolveBlock(disguise.disguiseId());
        if (disguiseBlock == null) return;
        BlockState disguiseState = disguiseBlock.defaultBlockState();
        BlockPos pos = event.getPosition().orElse(player.blockPosition());

        // Vanilla already computed the original speed as: digSpeed(oreState) / oreHardness / oreDivisor.
        // We want: digSpeed(disguiseState) / disguiseHardness / disguiseDivisor.
        // Since digSpeed depends on the tool's mining rules — and stone + iron_ore are both
        // covered by the same #mineable/pickaxe rule, so digSpeed is the same for both — we can
        // just rescale the original speed by the hardness/divisor ratio. This automatically
        // preserves Efficiency, Haste, water/onGround penalties, anything else baked in.
        float oreHardness = state.getDestroySpeed(player.level(), pos);
        float disguiseHardness = disguiseState.getDestroySpeed(player.level(), pos);
        if (oreHardness <= 0.0f || disguiseHardness <= 0.0f) return;
        ItemStack tool = player.getMainHandItem();
        int oreDivisor = (!state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state)) ? 30 : 100;
        int disguiseDivisor = (!disguiseState.requiresCorrectToolForDrops()
            || tool.isCorrectToolForDrops(disguiseState)) ? 30 : 100;

        float scale = (oreHardness / disguiseHardness) * ((float) oreDivisor / (float) disguiseDivisor);
        // Feel adjustment: disguised ores mine 1.5x slower than the real disguise block. Pure
        // stone-speed makes them feel suspiciously snappy under the radar; a touch slower reads
        // as "this rock is a little tougher" without ever pointing at the disguise.
        scale /= 3f;
        event.setNewSpeed(event.getOriginalSpeed() * scale);
    }

    /** Returns the disguise entry that applies to {@code block}, server- or client-side. */
    private static OreDisguise resolveDisguiseForBoth(Player player, Block block) {
        if (player.level().isClientSide()) {
            return com.bannerbound.core.client.ClientOreState.getDisguiseFor(block);
        }
        return OreDisguiseLoader.getDisguiseFor(block);
    }

    /** True if {@code block} is currently disguised for {@code player} (server- or client-side). */
    private static boolean isDisguisedForPlayer(Player player, Block block, OreDisguise disguise) {
        if (player.level().isClientSide()) {
            return com.bannerbound.core.client.ClientOreState.isCurrentlyDisguised(block);
        }
        if (!(player instanceof ServerPlayer sp)) return false;
        return !isOreRevealedForPlayer(sp, disguise);
    }

    private static boolean isOreRevealedForPlayer(ServerPlayer player, OreDisguise disguise) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        try {
            SettlementData data = SettlementData.get(server.overworld());
            Settlement s = data.getByPlayer(player.getUUID());
            return ResearchManager.hasFlag(s, disguise.flag());
        } catch (Exception ex) {
            return false;
        }
    }

    private static Block resolveBlock(String id) {
        try {
            return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        } catch (Exception ex) {
            return null;
        }
    }
}
