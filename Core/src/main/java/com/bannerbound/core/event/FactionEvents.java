package com.bannerbound.core.event;

import com.bannerbound.core.api.farmer.AwaitingSeedRegistry;
import com.bannerbound.core.api.settlement.ImmigrationManager;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.ChunkProtection;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.SettlementManager;
import com.bannerbound.core.network.OpenSettleScreenPayload;
import com.bannerbound.core.network.OpenTownHallScreenPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The "vanilla world respects faction claims" glue layer: every handler that makes an ordinary
 * Minecraft action bannerbound-aware. Registered on the mod event bus, all handlers server-side. If
 * you are adding a new "vanilla action should respect claims" interaction (e.g. crops only grow in
 * claimed land), this is where the event handler belongs.
 *
 * Login (onPlayerLoggedIn) fans out the one-time client sync: claim map, custom language, the faith
 * sky (seed + celestialSpeed, from which the client regenerates the whole star field / solar system),
 * faith + diplomacy + pantheon + journal + crisis + codex state, the settlement's current
 * food-warning banner level (the periodic broadcast only fires on level CHANGE, so a fresh login
 * would otherwise miss an ongoing warning; the client wiped its copy on logout), and a mirror of
 * the block-selection registry so a Foreman's Rod renders existing selections without waiting for
 * the next mutation broadcast. It also re-pushes any seed-picker prompts that queued while the player was offline;
 * AwaitingSeedRegistry.drainFor is idempotent (it removes drained entries so a re-login cannot
 * double-send), and stale entries whose selection was deleted offline are silently dropped.
 *
 * Chunk protection: onBlockPlace / onBlockBreak / onLivingDamage / onProjectileImpact cancel actions
 * against another settlement's claimed chunks (with op bypass and cross-faction "discovery"
 * bookkeeping); onLivingDamage additionally blocks same-settlement friendly fire. onBlockPlaceBeauty /
 * onBlockBreakBeauty feed a chunk's diminishing-returns beauty queue and MUST run at LOWEST priority
 * so they observe the protection handlers' cancel and never record a cancelled action.
 *
 * Town-hall-via-campfire flow: a campfire placed on unclaimed land is forced UNLIT, which is exactly
 * what arms it as a town-hall candidate (unless the server is at max factions, in which case founding
 * is impossible so it stays a normal lit campfire). Right-clicking an unlit candidate opens the settle
 * popup; shift+right-click on a lit campfire in your own territory promotes it to town hall; a plain
 * right-click on your existing town hall opens the management screen -- but only after
 * FactionBanner.requireRaised gates it (no raised banner = no command), and that same gate sweeps a
 * silently-lost banner and fires the full alarm. Breaking the town hall clears townHallPos and
 * notifies members; the town hall is protected by its OWNING settlement independent of chunk claim
 * (only a member or an op may break it).
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class FactionEvents {
    private FactionEvents() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SettlementManager.sendClaimsTo(player);
            com.bannerbound.core.language.CustomLanguageSync.sendTo(player);
            com.bannerbound.core.api.faith.SkyStateSync.sendTo(player);
            MinecraftServer loginServer = player.getServer();
            if (loginServer != null) {
                com.bannerbound.core.api.settlement.Settlement faithSettlement =
                    com.bannerbound.core.api.settlement.SettlementData
                        .get(loginServer.overworld()).getByPlayer(player.getUUID());
                if (faithSettlement != null) {
                    com.bannerbound.core.api.faith.FaithManager.sendStateTo(
                        loginServer, faithSettlement, player);
                    int foodWarnLevel = faithSettlement.lastFoodWarningLevel();
                    if (foodWarnLevel > com.bannerbound.core.network
                            .SettlementFoodWarningPayload.LEVEL_OK) {
                        PacketDistributor.sendToPlayer(player,
                            new com.bannerbound.core.network.SettlementFoodWarningPayload(
                                foodWarnLevel));
                    }
                    com.bannerbound.core.api.settlement.DiplomacyManager.sendDiplomacyState(player);
                    if (!com.bannerbound.core.api.settlement.DiplomacyManager.isPublicStandardValid(
                            loginServer.overworld(), faithSettlement)) {
                        player.sendSystemMessage(Component.translatable("bannerbound.banner.required")
                            .withStyle(ChatFormatting.RED));
                    }
                }
                com.bannerbound.core.api.faith.FaithManager.sendConstellationsTo(loginServer, player);
                com.bannerbound.core.journal.JournalManager.sendTo(player);
                com.bannerbound.core.crisis.CrisisManager.sendStateTo(player);
                com.bannerbound.core.codex.CodexManager.reconcile(player, false);
            }
            com.bannerbound.core.world.SelectionBroadcaster.sendTo(player);
            drainPendingSeedPrompts(player);
        }
    }

    private static void drainPendingSeedPrompts(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        com.bannerbound.core.api.world.BlockSelectionRegistry registry =
            com.bannerbound.core.api.world.BlockSelectionRegistry.get(overworld);
        java.util.List<UUID> pending =
            com.bannerbound.core.api.farmer.AwaitingSeedRegistry.drainFor(player.getUUID());
        for (UUID rodId : pending) {
            com.bannerbound.core.api.world.BlockSelection sel = registry.get(rodId);
            if (sel == null || sel.completed()) continue;
            if (!"farmer".equals(sel.workstationType())) continue;
            if (!sel.seedItemId().isEmpty()) continue;
            PacketDistributor.sendToPlayer(player,
                new com.bannerbound.core.network.OpenSeedPickerPayload(
                    rodId, com.bannerbound.core.farmer.SeedCandidates.itemIds(),
                    com.bannerbound.core.territory.CropChunks.bonusSeedIds(
                        overworld, sel.minX(), sel.minZ(), sel.maxX(), sel.maxZ())));
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SettlementManager.clearPendingTownHall(player.getUUID());
            DropLocationEditServer.clear(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ServerLevel overworld = server.overworld();
        SettlementData data = SettlementData.get(overworld);
        ChunkPos chunkPos = new ChunkPos(event.getPos());
        Settlement chunkOwner = data.getByChunk(chunkPos.toLong());
        Settlement playerSettlement = data.getByPlayer(player.getUUID());

        if (chunkOwner == null) {
            return;
        }

        boolean sameSettlement = playerSettlement != null && chunkOwner.id().equals(playerSettlement.id());

        if (!sameSettlement) {
            com.bannerbound.core.api.settlement.DiplomacyManager.discoverFromContact(
                server, playerSettlement, chunkOwner, "territory");
            if (ChunkProtection.shouldBypass(player)
                    || !ChunkProtection.isProtected(data, chunkPos, player.getUUID())) {
                return;
            }
            event.setCanceled(true);
            player.sendSystemMessage(Component.translatable("bannerbound.protection.cannot_place", chunkOwner.factionName())
                .withStyle(ChatFormatting.RED));
            return;
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (ChunkProtection.shouldBypass(player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        SettlementData data = SettlementData.get(server.overworld());
        ChunkPos chunkPos = new ChunkPos(event.getPos());
        Settlement owner = data.getByChunk(chunkPos.toLong());
        Settlement playerSettlement = data.getByPlayer(player.getUUID());
        if (owner != null && playerSettlement != null && !owner.id().equals(playerSettlement.id())) {
            com.bannerbound.core.api.settlement.DiplomacyManager.discoverFromContact(
                server, playerSettlement, owner, "territory");
            com.bannerbound.core.api.settlement.DiplomacyManager.recordPotentialSupportBreak(
                player, owner, event.getPos());
        }
        if (ChunkProtection.isProtected(data, chunkPos, player.getUUID())) {
            event.setCanceled(true);
            player.sendSystemMessage(Component.translatable("bannerbound.protection.cannot_break", owner.factionName())
                .withStyle(ChatFormatting.RED));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockPlaceBeauty(BlockEvent.EntityPlaceEvent event) {
        // LOWEST priority + this guard: a place the protection handler cancelled must not be recorded.
        if (event.isCanceled()) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level) {
            com.bannerbound.core.api.settlement.ChunkBeautyManager.onBlockPlaced(
                level, event.getPos(), event.getPlacedBlock().getBlock());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockBreakBeauty(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level) {
            com.bannerbound.core.api.settlement.ChunkBeautyManager.onBlockRemoved(
                level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onLivingDamage(LivingIncomingDamageEvent event) {
        Entity sourceEntity = event.getSource().getEntity();
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel victimLevel)) {
            return;
        }
        MinecraftServer server = victimLevel.getServer();
        if (server == null) {
            return;
        }
        SettlementData data = SettlementData.get(server.overworld());
        if (!(sourceEntity instanceof ServerPlayer attacker)) {
            if (!com.bannerbound.core.api.settlement.DiplomacyManager.canDamageInClaim(
                    data, new ChunkPos(victim.blockPosition()), sourceEntity)) {
                event.setCanceled(true);
            }
            return;
        }
        if (attacker == victim) {
            return;
        }

        if (victim instanceof ServerPlayer victimPlayer) {
            Settlement attackerSettlement = data.getByPlayer(attacker.getUUID());
            Settlement victimSettlement = data.getByPlayer(victimPlayer.getUUID());
            if (attackerSettlement != null && victimSettlement != null
                    && attackerSettlement.id().equals(victimSettlement.id())) {
                event.setCanceled(true);
                return;
            }
        }

        if (ChunkProtection.shouldBypass(attacker)) {
            return;
        }

        ChunkPos victimChunk = new ChunkPos(victim.blockPosition());
        if (ChunkProtection.isProtected(data, victimChunk, attacker.getUUID())) {
            event.setCanceled(true);
            Settlement owner = data.getByChunk(victimChunk.toLong());
            attacker.sendSystemMessage(Component.translatable("bannerbound.protection.cannot_attack", owner.factionName())
                .withStyle(ChatFormatting.RED));
        }
    }

    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        Entity owner = projectile.getOwner();
        if (!(owner instanceof ServerPlayer shooter)) {
            return;
        }
        if (ChunkProtection.shouldBypass(shooter)) {
            return;
        }
        MinecraftServer server = shooter.getServer();
        if (server == null) {
            return;
        }
        HitResult hit = event.getRayTraceResult();
        BlockPos pos;
        if (hit instanceof BlockHitResult bhr) {
            pos = bhr.getBlockPos();
        } else if (hit instanceof EntityHitResult ehr) {
            pos = ehr.getEntity().blockPosition();
        } else {
            return;
        }
        SettlementData data = SettlementData.get(server.overworld());
        ChunkPos chunk = new ChunkPos(pos);
        if (ChunkProtection.isProtected(data, chunk, shooter.getUUID())) {
            event.setCanceled(true);
            projectile.discard();
            Settlement chunkOwner = data.getByChunk(chunk.toLong());
            shooter.sendSystemMessage(Component.translatable("bannerbound.protection.projectile_blocked", chunkOwner.factionName())
                .withStyle(ChatFormatting.RED));
        }
    }

    @SubscribeEvent
    public static void onCampfirePlace(BlockEvent.EntityPlaceEvent event) {
        BlockState placed = event.getPlacedBlock();
        if (!(placed.getBlock() instanceof CampfireBlock)) {
            return;
        }
        if (!placed.hasProperty(CampfireBlock.LIT) || !placed.getValue(CampfireBlock.LIT)) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        SettlementData data = SettlementData.get(server.overworld());
        BlockPos pos = event.getPos();
        if (data.getByChunk(new ChunkPos(pos).toLong()) != null) {
            return;
        }
        if (SettlementManager.isAtMaxFactions(data)) {
            return;
        }
        LevelAccessor level = event.getLevel();
        level.setBlock(pos, placed.setValue(CampfireBlock.LIT, false), 3);
    }

    @SubscribeEvent
    public static void onCampfireRightClick(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide) {
            return;
        }
        BlockState state = level.getBlockState(event.getPos());
        if (!(state.getBlock() instanceof CampfireBlock) || !state.hasProperty(CampfireBlock.LIT)) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        SettlementData data = SettlementData.get(server.overworld());
        BlockPos pos = event.getPos();
        Settlement chunkOwner = data.getByChunk(new ChunkPos(pos).toLong());
        Settlement playerSettlement = data.getByPlayer(player.getUUID());
        boolean lit = state.getValue(CampfireBlock.LIT);
        boolean shiftClick = player.isShiftKeyDown();

        if (!lit) {
            if (chunkOwner != null) {
                return;
            }
            if (playerSettlement != null) {
                player.sendSystemMessage(Component.translatable("bannerbound.townhall.already_in_settlement")
                    .withStyle(ChatFormatting.RED));
                event.setCanceled(true);
                return;
            }
            if (SettlementManager.isAtMaxFactions(data)) {
                level.setBlock(pos, state.setValue(CampfireBlock.LIT, true), 3);
                player.sendSystemMessage(Component.translatable("bannerbound.settle.error.max_factions")
                    .withStyle(ChatFormatting.RED));
                event.setCanceled(true);
                return;
            }
            SettlementManager.setPendingTownHall(player.getUUID(), pos);
            int siteWarnings = level instanceof net.minecraft.server.level.ServerLevel serverLevel
                ? com.bannerbound.core.territory.SettlementSiteAssessor.assessMask(serverLevel, pos)
                : 0;
            PacketDistributor.sendToPlayer(player, new OpenSettleScreenPayload(siteWarnings));
            event.setCanceled(true);
            return;
        }

        if (shiftClick) {
            if (playerSettlement == null) {
                return;
            }
            if (chunkOwner == null || !chunkOwner.id().equals(playerSettlement.id())) {
                return;
            }
            BlockPos currentThp = playerSettlement.townHallPos();
            if (currentThp != null) {
                BlockState thpState = level.getBlockState(currentThp);
                boolean thpValid = thpState.getBlock() instanceof CampfireBlock
                    && thpState.hasProperty(CampfireBlock.LIT)
                    && thpState.getValue(CampfireBlock.LIT);
                if (thpValid) {
                    if (!currentThp.equals(pos)) {
                        player.sendSystemMessage(Component.translatable("bannerbound.townhall.already_have_one")
                            .withStyle(ChatFormatting.YELLOW));
                    }
                    return;
                }
                playerSettlement.setTownHallPos(null);
                data.setDirty();
            }
            playerSettlement.setTownHallPos(pos);
            data.setDirty();
            player.sendSystemMessage(Component.translatable("bannerbound.townhall.promoted", playerSettlement.name())
                .withStyle(playerSettlement.identityFormatting()));
            event.setCanceled(true);
            return;
        }

        if (playerSettlement != null && chunkOwner != null && chunkOwner.id().equals(playerSettlement.id())) {
            BlockPos thp = playerSettlement.townHallPos();
            if (thp != null && thp.equals(pos)) {
                if (com.bannerbound.core.api.settlement.DiplomacyManager.tryScoreStandard(player, pos)) {
                    event.setCanceled(true);
                    return;
                }
                if (!com.bannerbound.core.api.settlement.FactionBanner.requireRaised(
                        (ServerLevel) level, player, playerSettlement)) {
                    event.setCanceled(true);
                    return;
                }
                PacketDistributor.sendToPlayer(player,
                    com.bannerbound.core.api.settlement.ImmigrationManager.buildPayload(
                        player.serverLevel(), playerSettlement));
                com.bannerbound.core.api.settlement.Settlement.Government myVote =
                    playerSettlement.governmentVotes().get(player.getUUID());
                int myVoteOrdinal = myVote == null ? 0 : myVote.ordinal();
                int onlineMembers = com.bannerbound.core.api.settlement.SettlementManager
                    .countOnlineMembers(player.getServer(), playerSettlement);
                boolean chiefElectionActive = playerSettlement.chiefdomElectionWindowOpen();
                java.util.ArrayList<java.util.UUID> chiefCandidates = new java.util.ArrayList<>();
                java.util.ArrayList<String> chiefCandidateNames = new java.util.ArrayList<>();
                java.util.ArrayList<Integer> chiefCandidateVotes = new java.util.ArrayList<>();
                if (chiefElectionActive) {
                    for (java.util.UUID memberId : playerSettlement.members()) {
                        chiefCandidates.add(memberId);
                        net.minecraft.server.level.ServerPlayer mp =
                            player.getServer().getPlayerList().getPlayer(memberId);
                        String name;
                        if (mp != null) {
                            name = mp.getGameProfile().getName();
                        } else if (player.getServer().getProfileCache() != null) {
                            name = player.getServer().getProfileCache().get(memberId)
                                .map(profile -> profile.getName())
                                .orElse(memberId.toString().substring(0, 8));
                        } else {
                            name = memberId.toString().substring(0, 8);
                        }
                        chiefCandidateNames.add(name);
                        chiefCandidateVotes.add(playerSettlement.chiefNominationCountFor(memberId));
                    }
                }
                java.util.UUID myChiefNom = playerSettlement.chiefNominations().get(player.getUUID());
                if (myChiefNom == null) myChiefNom = new java.util.UUID(0L, 0L);

                boolean playerIsChief =
                    playerSettlement.governmentType()
                        == com.bannerbound.core.api.settlement.Settlement.Government.CHIEFDOM
                    && player.getUUID().equals(playerSettlement.chiefPlayerId());
                boolean playerIsRegent =
                    playerSettlement.governmentType()
                        == com.bannerbound.core.api.settlement.Settlement.Government.CHIEFDOM
                    && player.getUUID().equals(playerSettlement.regentPlayerId());
                long chiefStepDownReadyTick = (playerIsChief && playerSettlement.chiefSinceTick() >= 0)
                    ? playerSettlement.chiefSinceTick()
                        + com.bannerbound.core.api.settlement.SettlementManager.CHIEF_STEP_DOWN_COOLDOWN_TICKS
                    : -1L;
                long leaveReadyTick = com.bannerbound.core.api.settlement.SettlementData
                    .get(player.serverLevel()).leaveCooldownUntil(player.getUUID());

                if (com.bannerbound.core.crisis.CrisisManager.shouldOpenCrisisScreen(playerSettlement)
                        && !playerSettlement.governmentChoiceWindowOpen()
                        && !playerSettlement.chiefdomElectionWindowOpen()) {
                    com.bannerbound.core.crisis.CrisisManager.openCrisisScreen(player);
                    event.setCanceled(true);
                    return;
                }

                PacketDistributor.sendToPlayer(player, new OpenTownHallScreenPayload(
                    playerSettlement.name(),
                    playerSettlement.color().ordinal(),
                    playerSettlement.age().ordinal(),
                    playerSettlement.tabletsIssued(),
                    playerSettlement.tabletCapacity(),
                    playerSettlement.disbandVoteCount(),
                    playerSettlement.members().size(),
                    playerSettlement.hasDisbandVoted(player.getUUID()),
                    playerSettlement.isDisbandVoteActive(),
                    playerSettlement.governmentType().ordinal(),
                    playerSettlement.codeOfLawsPromptShown(),
                    playerSettlement.governmentChoiceWindowOpen(),
                    playerSettlement.isGovernmentVoteActive(),
                    playerSettlement.governmentVoteCountFor(
                        com.bannerbound.core.api.settlement.Settlement.Government.COUNCIL),
                    playerSettlement.governmentVoteCountFor(
                        com.bannerbound.core.api.settlement.Settlement.Government.CHIEFDOM),
                    onlineMembers,
                    myVoteOrdinal,
                    chiefElectionActive,
                    chiefCandidates,
                    chiefCandidateNames,
                    chiefCandidateVotes,
                    myChiefNom,
                    playerIsChief,
                    playerIsRegent,
                    chiefStepDownReadyTick,
                    leaveReadyTick,
                    playerSettlement.identityRgbList()));
                if (player.getServer() != null) {
                    com.bannerbound.core.api.settlement.SettlementManager
                        .broadcastPolicyState(player.getServer(), playerSettlement);
                    com.bannerbound.core.api.settlement.SettlementManager
                        .broadcastPaletteState(player.getServer(), playerSettlement);
                    com.bannerbound.core.api.settlement.SettlementManager
                        .sendLaborStateTo(player);
                    com.bannerbound.core.api.settlement.SettlementManager.sendChatVotesStateTo(
                        player.getServer(), player,
                        com.bannerbound.core.api.settlement.ChatVoteManager
                            .activeVotesFor(playerSettlement.id()));
                    com.bannerbound.core.api.settlement.SettlementManager
                        .broadcastExtraSuggestions(player.getServer(), playerSettlement);
                    com.bannerbound.core.api.settlement.SettlementManager
                        .broadcastSuggestionState(player.getServer(), playerSettlement);
                    com.bannerbound.core.api.settlement.DiplomacyManager.sendDiplomacyState(player);
                    PacketDistributor.sendToPlayer(player,
                        new com.bannerbound.core.network.SettlementWarningsPayload(
                            com.bannerbound.core.api.settlement.SettlementManager
                                .settlementWarnings(player.getServer(), playerSettlement)));
                }
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onCampfireBreak(BlockEvent.BreakEvent event) {
        // A break already cancelled by chunk protection must NOT clear the town hall.
        if (event.isCanceled()) {
            return;
        }
        BlockState state = event.getState();
        if (!(state.getBlock() instanceof CampfireBlock)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        MinecraftServer server = serverLevel.getServer();
        SettlementData data = SettlementData.get(server.overworld());
        BlockPos pos = event.getPos();
        for (Settlement s : data.all()) {
            if (pos.equals(s.townHallPos())) {
                if (event.getPlayer() instanceof ServerPlayer breaker
                        && !s.members().contains(breaker.getUUID())
                        && !ChunkProtection.shouldBypass(breaker)) {
                    event.setCanceled(true);
                    breaker.sendSystemMessage(Component.translatable(
                        "bannerbound.protection.cannot_break", s.factionName())
                        .withStyle(ChatFormatting.RED));
                    return;
                }
                s.setTownHallPos(null);
                data.setDirty();
                for (UUID memberId : s.members()) {
                    ServerPlayer member = server.getPlayerList().getPlayer(memberId);
                    if (member != null) {
                        member.sendSystemMessage(Component.translatable("bannerbound.townhall.destroyed", s.name())
                            .withStyle(ChatFormatting.RED));
                    }
                }
                break;
            }
        }
    }
}
