package com.bannerbound.core.event;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.FactionBanner;
import com.bannerbound.core.api.settlement.Outpost;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * World-event side of the {@link FactionBanner} system. Three jobs:
 *
 * <ul>
 *   <li><b>Break tracking</b> — breaking THE faction banner clears it (member = quiet
 *       relocation note, anyone/anything else = full alarm) and force-closes every settlement
 *       menu faction-wide.</li>
 *   <li><b>Placement tracking</b> — a member placing any banner inside their own territory
 *       while the faction banner is down raises it as THE banner (this is also the relocation
 *       flow: break yours, carry it, plant it again).</li>
 *   <li><b>Craft conversion</b> — a plain (pattern-less) banner crafted while standing in your
 *       own settlement comes out in the faction's color, whatever wool went in. Loom-designed
 *       banners (non-empty patterns) are deliberate art and are never touched. The swap runs
 *       one tick late via a queue because the crafted stack isn't in the inventory yet while
 *       {@code ItemCraftedEvent} fires (it's mid-click, and the item TYPE can't be mutated
 *       in place).</li>
 * </ul>
 */
@ApiStatus.Internal
@EventBusSubscriber(modid = BannerboundCore.MODID)
public final class FactionBannerEvents {

    private FactionBannerEvents() {}

    // ─── Break / place tracking ────────────────────────────────────────────────────────────

    /**
     * A player mining THE banner block directly — the one case where we know the culprit and
     * their membership, so it gets the nuanced toll (member = quiet relocation note, anyone
     * else = full alarm). Every OTHER way the banner can vanish (support knocked out, piston,
     * water, {@code /setblock}, another mod, explosion) is cause-agnostic and caught by the
     * throttled sweep below, since none of them fire a banner break event we could read.
     */
    @SubscribeEvent
    public static void onBannerBroken(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!FactionBanner.isBanner(event.getState())) return;
        SettlementData data = SettlementData.get(level.getServer().overworld());
        Settlement owner = data.getByChunk(new ChunkPos(event.getPos()).toLong());
        if (owner == null || !event.getPos().equals(owner.bannerPos())) return;
        Player breaker = event.getPlayer();
        boolean member = breaker != null && owner.members().contains(breaker.getUUID());
        String name = breaker != null ? breaker.getGameProfile().getName() : "";
        if (member) {
            if (!com.bannerbound.core.api.settlement.DiplomacyManager.canOwnerBreakStandard(data, owner)) {
                event.setCanceled(true);
                breaker.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "bannerbound.diplomacy.standard.locked").withStyle(net.minecraft.ChatFormatting.RED));
                return;
            }
            FactionBanner.lose(level, owner, event.getPos(), true, name);
            return;
        }
        if (breaker instanceof ServerPlayer sp) {
            Settlement breakerSettlement = data.getByPlayer(sp.getUUID());
            if (breakerSettlement != null && com.bannerbound.core.api.settlement.DiplomacyManager
                    .isActiveWarEnemy(data, breakerSettlement.id(), owner.id())) {
                event.setCanceled(true);
                level.removeBlock(event.getPos(), false);
                com.bannerbound.core.api.settlement.DiplomacyManager.createStolenStandard(
                    level, owner, event.getPos(), sp, breakerSettlement, name);
                return;
            }
            com.bannerbound.core.api.settlement.DiplomacyManager.discoverFromContact(
                level.getServer(), breakerSettlement, owner, "standard");
            // Non-member, not at war, not op: the banner is protected like any other block in
            // the claim. Cancel the break (op-level 2+ falls through to lose() below).
            if (!com.bannerbound.core.api.settlement.ChunkProtection.shouldBypass(sp)) {
                event.setCanceled(true);
                sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "bannerbound.protection.cannot_break", owner.factionName())
                    .withStyle(net.minecraft.ChatFormatting.RED));
                return;
            }
        }
        FactionBanner.lose(level, owner, event.getPos(), false, name);
    }

    @SubscribeEvent
    public static void onBannerPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!FactionBanner.isBanner(event.getPlacedBlock())) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        SettlementData data = SettlementData.get(level.getServer().overworld());
        Settlement mine = data.getByPlayer(player.getUUID());
        if (mine == null) return;
        Settlement chunkOwner = data.getByChunk(new ChunkPos(event.getPos()).toLong());
        if (chunkOwner == null || !chunkOwner.id().equals(mine.id())) return;
        // A registered banner that silently vanished (explosion, piston) shouldn't block a
        // replacement — sweep it first, then register this one if the post is vacant.
        FactionBanner.validate(level, mine);
        if (mine.hasFactionBanner()) return; // banner stands — this one is decoration
        if (com.bannerbound.core.api.settlement.DiplomacyManager.hasStolenOrCapturedStandard(
                data, mine.id())) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                "bannerbound.diplomacy.standard.cannot_reraise").withStyle(net.minecraft.ChatFormatting.RED));
            return;
        }
        if (!com.bannerbound.core.api.settlement.DiplomacyManager.isPublicStandardValidAt(
                level, mine, event.getPos())) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                "bannerbound.banner.required").withStyle(net.minecraft.ChatFormatting.RED));
            return;
        }
        FactionBanner.raise(level, mine, event.getPos());
    }

    // ─── Outposts: a faction banner planted outside the border ─────────────────────────────

    /**
     * Breaking the faction banner that established an outpost drops its working claim — that IS
     * conquest v1 (the banner sits on unprotected land). A MEMBER dismantling gets a quiet note;
     * anyone/anything else is an attack and {@link Outpost#loseOutpost} sounds the alarm. Disjoint
     * from {@link #onBannerBroken}: outposts live on UNCLAIMED chunks (working claims), the main
     * banner in claimed territory, so exactly one handler acts on any given banner.
     */
    @SubscribeEvent
    public static void onOutpostBannerBroken(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!FactionBanner.isBanner(event.getState())) return;
        SettlementData data = SettlementData.get(level.getServer().overworld());
        ChunkPos cp = new ChunkPos(event.getPos());
        Settlement owner = data.getByWorkingClaim(cp.toLong());
        if (owner == null) return;
        // Only the banner that ESTABLISHED the outpost counts; a second decorative banner dropped
        // in the same chunk is just a block. (Legacy claims have no recorded pos — any banner break
        // there falls through, which is correct: that lone banner is the outpost's.)
        BlockPos recorded = owner.outpostBannerPos(cp.toLong());
        if (recorded != null && !recorded.equals(event.getPos())) return;
        Player breaker = event.getPlayer();
        boolean member = breaker != null && owner.members().contains(breaker.getUUID());
        if (breaker instanceof ServerPlayer sp) {
            if (member) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "bannerbound.outpost.dismantled").withStyle(net.minecraft.ChatFormatting.YELLOW));
            } else {
                com.bannerbound.core.api.settlement.DiplomacyManager.discoverFromContact(
                    level.getServer(), data.getByPlayer(sp.getUUID()), owner, "outpost");
            }
        }
        Outpost.loseOutpost(level, owner, event.getPos(), member);
    }

    /**
     * Right-clicking a banner with an empty hand: open the outpost screen. "Place then confirm" —
     * planting a banner is always just decoration; you turn it into an outpost from here. On the
     * settlement's existing outpost chunk → the management screen; on a valid but unclaimed
     * near-border site (outpost researched, within range) → the "Establish outpost here" screen;
     * on another faction's outpost → a "belongs to X" note. Everywhere else we don't intercept, so
     * banners stay ordinary blocks.
     */
    @SubscribeEvent
    public static void onBannerRightClicked(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND).isEmpty()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        net.minecraft.core.BlockPos pos = event.getPos();
        if (!FactionBanner.isBanner(level.getBlockState(pos))) return;
        SettlementData data = SettlementData.get(level.getServer().overworld());
        Settlement mine = data.getByPlayer(player.getUUID());
        if (mine == null) return;
        ChunkPos cp = new ChunkPos(pos);
        // Inside ANY full territory (own or other) → leave alone: that's the main banner or
        // decoration, not an outpost (outposts only live on unclaimed land).
        if (data.getByChunk(cp.toLong()) != null) return;
        Settlement workOwner = data.getByWorkingClaim(cp.toLong());
        if (workOwner != null) {
            if (workOwner.id().equals(mine.id())) {
                Outpost.openScreen(level, player, pos);
            } else {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "bannerbound.outpost.belongs_to", workOwner.factionName())
                    .withStyle(net.minecraft.ChatFormatting.RED), true);
            }
            event.setCanceled(true);
            return;
        }
        // Unclaimed chunk: offer to establish only on a researched, in-range site (so a banner hung
        // far from home stays plain decoration and never pops a screen).
        if (!ResearchManager.hasFlag(mine, Outpost.FLAG_OUTPOST)) return;
        if (!Outpost.withinRange(mine, cp)) return;
        Outpost.openEstablishScreen(level, player, pos);
        event.setCanceled(true);
    }

    // ─── Crafted banners come out in faction colors ────────────────────────────────────────

    private record PendingConvert(UUID playerId, Item crafted, int count) {}

    /** Crafts seen this tick, converted on the next {@code ServerTickEvent.Post}. */
    private static final Queue<PendingConvert> PENDING = new ConcurrentLinkedQueue<>();

    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack crafted = event.getCrafting();
        if (!(crafted.getItem() instanceof BannerItem) || crafted.isEmpty()) return;
        if (hasPatterns(crafted)) return; // purposefully designed — leave it alone
        SettlementData data = SettlementData.get(player.server.overworld());
        Settlement mine = data.getByPlayer(player.getUUID());
        if (mine == null) return;
        // "Crafted in the settlement": the crafter stands on their own claim.
        Settlement here = data.getByChunk(new ChunkPos(player.blockPosition()).toLong());
        if (here == null || !here.id().equals(mine.id())) return;
        // Already the faction banner only when BOTH color matches and there's no Heraldry
        // design to stamp on — a plain faction-color banner still needs its patterns.
        if (crafted.getItem() == FactionBanner.itemFor(mine.color())
                && mine.bannerDesign().isEmpty()) {
            return;
        }
        PENDING.add(new PendingConvert(player.getUUID(), crafted.getItem(), crafted.getCount()));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        convertCraftedBanners(event);
        sweepBannerPositions(event);
    }

    private static void convertCraftedBanners(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        PendingConvert pending;
        while ((pending = PENDING.poll()) != null) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(pending.playerId());
            if (player == null) continue;
            SettlementData data = SettlementData.get(event.getServer().overworld());
            Settlement mine = data.getByPlayer(player.getUUID());
            if (mine == null) continue;
            int remaining = pending.count();
            // Cursor first (normal result pickup), then the inventory (shift-click). Identity
            // of the crafted stack is lost to merging, so we convert matching plain stacks up
            // to the crafted count — over-matching only ever hits identical plain banners.
            ItemStack carried = player.containerMenu.getCarried();
            if (isConvertible(carried, pending.crafted())) {
                player.containerMenu.setCarried(FactionBanner.designedItem(
                    mine, event.getServer().registryAccess(), carried.getCount()));
                remaining -= carried.getCount();
            }
            for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (!isConvertible(stack, pending.crafted())) continue;
                player.getInventory().setItem(slot, FactionBanner.designedItem(
                    mine, event.getServer().registryAccess(), stack.getCount()));
                remaining -= stack.getCount();
            }
            player.containerMenu.broadcastChanges();
        }
    }

    /** Server ticks between banner-position sweeps — once a second is far faster than anyone
     *  can react to a fallen banner, and one block check per settlement is negligible. */
    private static final int SWEEP_INTERVAL = 20;
    private static int sweepTick;

    /**
     * Cause-agnostic safety net: re-checks every settlement's registered banner position and
     * clears it the moment the block is gone — whatever removed it (support knocked out, piston,
     * water, {@code /setblock}, command block, another mod, explosion). Direct player breaks are
     * already handled with culprit info by {@link #onBannerBroken}; everything else funnels
     * through here as a generic "fallen" alarm. Only loaded positions are judged — an unloaded
     * banner is presumed standing (same rule as {@link FactionBanner#validate}).
     */
    private static void sweepBannerPositions(ServerTickEvent.Post event) {
        if (++sweepTick < SWEEP_INTERVAL) return;
        sweepTick = 0;
        ServerLevel overworld = event.getServer().overworld();
        SettlementData data = SettlementData.get(overworld);
        for (Settlement settlement : data.all()) {
            if (settlement.hasFactionBanner()) {
                FactionBanner.validate(overworld, settlement);
            }
            // Same net for outposts: a banner blown up / pistoned / setblock'd away fires no break
            // event, so re-check each recorded outpost banner and drop the claim if it's gone.
            Outpost.validateOutposts(overworld, settlement);
        }
    }

    private static boolean isConvertible(ItemStack stack, Item craftedItem) {
        return !stack.isEmpty() && stack.getItem() == craftedItem && !hasPatterns(stack);
    }

    private static boolean hasPatterns(ItemStack stack) {
        BannerPatternLayers layers = stack.get(DataComponents.BANNER_PATTERNS);
        return layers != null && !layers.layers().isEmpty();
    }
}
