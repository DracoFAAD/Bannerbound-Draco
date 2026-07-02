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
 * The "vanilla world cares about bannerbounds" glue layer. Subscribed to every event that
 * needs bannerbound-aware behavior: login (claim sync), block place/break (chunk protection +
 * town hall placement), living damage (friendly fire + claim protection), projectile impacts,
 * and the campfire-as-town-hall flow (place unlit → settle popup → light on settle, shift+RC
 * to promote, break to clear).
 * <p>
 * If you're adding a new "vanilla action should be bannerbound-aware" interaction (e.g. crops
 * only grow in claimed land), this is the home for the event handler.
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
            // The faith sky: seed + celestialSpeed, from which the client generates the
            // entire star field + solar system (FAITH_PLAN.md Part 3). Sent once per login.
            com.bannerbound.core.api.faith.SkyStateSync.sendTo(player);
            // Faith state (devotion, name, choice window) for the town hall surfaces.
            MinecraftServer loginServer = player.getServer();
            if (loginServer != null) {
                com.bannerbound.core.api.settlement.Settlement faithSettlement =
                    com.bannerbound.core.api.settlement.SettlementData
                        .get(loginServer.overworld()).getByPlayer(player.getUUID());
                if (faithSettlement != null) {
                    com.bannerbound.core.api.faith.FaithManager.sendStateTo(
                        loginServer, faithSettlement, player);
                    com.bannerbound.core.api.settlement.DiplomacyManager.sendDiplomacyState(player);
                    if (!com.bannerbound.core.api.settlement.DiplomacyManager.isPublicStandardValid(
                            loginServer.overworld(), faithSettlement)) {
                        player.sendSystemMessage(Component.translatable("bannerbound.banner.required")
                            .withStyle(ChatFormatting.RED));
                    }
                }
                // Pantheon for the believer sky + Pantheon mode exclusions (empty list
                // for the faithless, clearing any stale client state).
                com.bannerbound.core.api.faith.FaithManager.sendConstellationsTo(loginServer, player);
                com.bannerbound.core.journal.JournalManager.sendTo(player);
                com.bannerbound.core.crisis.CrisisManager.sendStateTo(player);
                com.bannerbound.core.codex.CodexManager.reconcile(player, false);
            }
            // Seed the joining player's mirror of the block-selection registry so a Foreman's
            // Rod in their hand can immediately render existing selections without waiting for
            // the next mutation-triggered broadcast.
            com.bannerbound.core.world.SelectionBroadcaster.sendTo(player);
            // Drain any seed-picker prompts that fired while this player was offline. Each
            // pending entry → one OpenSeedPickerPayload pushed in order. The screens will queue
            // up; the player works through them by picking or skipping.
            drainPendingSeedPrompts(player);
        }
    }

    /** Re-pushes any seed-picker prompts that queued up for {@code player} while they were
     *  offline. Idempotent: {@link com.bannerbound.core.api.farmer.AwaitingSeedRegistry#drainFor}
     *  removes the entries so re-login won't double-send. */
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
            // Stale entries (selection was deleted while offline) are silently dropped.
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

    /**
     * Chunk-beauty bookkeeping for block placement. Runs at LOWEST priority so it sees whether
     * the protection handler above cancelled the placement — a cancelled place is not recorded.
     * The placed block joins its chunk's diminishing-returns queue in placement order.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockPlaceBeauty(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level) {
            com.bannerbound.core.api.settlement.ChunkBeautyManager.onBlockPlaced(
                level, event.getPos(), event.getPlacedBlock().getBlock());
        }
    }

    /** Chunk-beauty bookkeeping for block breaking — see {@link #onBlockPlaceBeauty}. */
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

        // Friendly fire: same-settlement players cannot damage each other.
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

        // Protection: cannot damage anything inside another settlement's claimed chunk.
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

    /**
     * Campfires placed in unclaimed chunks default to unlit. The unlit state is what makes them
     * "ready to be a town hall" — right-clicking one opens the settle popup. Campfires placed in
     * claimed chunks behave as normal vanilla campfires (lit on placement).
     */
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
        // Server full (every color taken): no settlement can be founded, so don't arm this
        // campfire as a town-hall candidate — leave it a normal lit campfire.
        if (SettlementManager.isAtMaxFactions(data)) {
            return;
        }
        LevelAccessor level = event.getLevel();
        level.setBlock(pos, placed.setValue(CampfireBlock.LIT, false), 3);
    }

    /**
     * Right-clicking campfires drives the whole town-hall flow:
     *   - Unlit in unclaimed land → open the settle popup, this campfire becomes the pending town hall.
     *   - Lit, your settlement's territory, this IS your town hall → open management screen.
     *   - Shift+right-click, your settlement's territory, settlement has no town hall → promote.
     *   - Anything else → fall through to vanilla campfire behavior.
     */
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
            // Server full: this campfire was placed (and armed unlit) before the last color was
            // claimed. It can't found anything now, so light it — demoting it to a normal
            // campfire — and tell the player why instead of opening a dead-end founding menu.
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
                // No faction banner raised → no command: the town hall menu refuses to open
                // (and via this gate, every screen reached from it). requireRaised also sweeps
                // a stale registration (banner destroyed by explosion etc. fires no break
                // event), so a silently-lost banner is discovered — with the full alarm — here.
                if (!com.bannerbound.core.api.settlement.FactionBanner.requireRaised(
                        (ServerLevel) level, player, playerSettlement)) {
                    event.setCanceled(true);
                    return;
                }
                // Push the latest population/economy snapshot first so the screen renders with
                // up-to-date numbers on the very first frame.
                PacketDistributor.sendToPlayer(player,
                    com.bannerbound.core.api.settlement.ImmigrationManager.buildPayload(
                        player.serverLevel(), playerSettlement));
                // Per-player government-vote state: 0 = none, 1 = council, 2 = chiefdom.
                com.bannerbound.core.api.settlement.Settlement.Government myVote =
                    playerSettlement.governmentVotes().get(player.getUUID());
                int myVoteOrdinal = myVote == null ? 0 : myVote.ordinal();
                int onlineMembers = com.bannerbound.core.api.settlement.SettlementManager
                    .countOnlineMembers(player.getServer(), playerSettlement);
                // Chief-election snapshot: candidates (members), names, current vote counts,
                // and this player's own pick. Empty when not in the election window.
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

                // Step 7 — Chief identification: true iff Chiefdom AND this player is the
                // seated chief. Drives the client-side gate on Disband / Expand Territory.
                boolean playerIsChief =
                    playerSettlement.governmentType()
                        == com.bannerbound.core.api.settlement.Settlement.Government.CHIEFDOM
                    && player.getUUID().equals(playerSettlement.chiefPlayerId());
                // Step 15 — Regent identification: stand-in chief authority. Routine
                // actions allowed; weighty actions still blocked.
                boolean playerIsRegent =
                    playerSettlement.governmentType()
                        == com.bannerbound.core.api.settlement.Settlement.Government.CHIEFDOM
                    && player.getUUID().equals(playerSettlement.regentPlayerId());
                // Step-Down cooldown: absolute tick this chief may resign (seat tick + term).
                // -1 when not the chief or a pre-feature chief with no anchor (no cooldown).
                long chiefStepDownReadyTick = (playerIsChief && playerSettlement.chiefSinceTick() >= 0)
                    ? playerSettlement.chiefSinceTick()
                        + com.bannerbound.core.api.settlement.SettlementManager.CHIEF_STEP_DOWN_COOLDOWN_TICKS
                    : -1L;
                // Leave cooldown: absolute tick this member may walk out (set on join/found). 0 once
                // it's elapsed (or never applied). Drives the Leave button's live mm:ss countdown.
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
                // Push the policy snapshot too so the Policies tab has data the moment it's
                // opened. Settlement-wide broadcast is fine here (low frequency, town-hall open).
                if (player.getServer() != null) {
                    com.bannerbound.core.api.settlement.SettlementManager
                        .broadcastPolicyState(player.getServer(), playerSettlement);
                    com.bannerbound.core.api.settlement.SettlementManager
                        .broadcastPaletteState(player.getServer(), playerSettlement);
                    // Labor snapshot so the Labor tab has data the moment it opens.
                    com.bannerbound.core.api.settlement.SettlementManager
                        .sendLaborStateTo(player);
                    // Votes + Suggestions snapshots, same reason (cheap when empty). The research
                    // suggestion sync rides along so a relogged chief's Suggestions tab isn't
                    // missing rows that were suggested before they joined.
                    com.bannerbound.core.api.settlement.SettlementManager.sendChatVotesStateTo(
                        player.getServer(), player,
                        com.bannerbound.core.api.settlement.ChatVoteManager
                            .activeVotesFor(playerSettlement.id()));
                    com.bannerbound.core.api.settlement.SettlementManager
                        .broadcastExtraSuggestions(player.getServer(), playerSettlement);
                    com.bannerbound.core.api.settlement.SettlementManager
                        .broadcastSuggestionState(player.getServer(), playerSettlement);
                    com.bannerbound.core.api.settlement.DiplomacyManager.sendDiplomacyState(player);
                    // Settlement unrest warnings (homelessness / strikes / coup) so the Town Hall
                    // Main tab can show them. Single source of truth — SettlementManager computes,
                    // we just relay each already-styled Component to this player.
                    PacketDistributor.sendToPlayer(player,
                        new com.bannerbound.core.network.SettlementWarningsPayload(
                            com.bannerbound.core.api.settlement.SettlementManager
                                .settlementWarnings(player.getServer(), playerSettlement)));
                }
                event.setCanceled(true);
            }
        }
    }

    /**
     * If the town hall campfire is broken, clear the settlement's townHallPos and notify members.
     * Members can then shift+right-click another lit campfire in their territory to promote it.
     */
    @SubscribeEvent
    public static void onCampfireBreak(BlockEvent.BreakEvent event) {
        // A break already cancelled by chunk protection (above) must NOT clear the town hall.
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
                // The town hall is protected by its OWNING settlement, independent of whether the
                // chunk is claimed: only a member (or an op) may break it. Everyone else is blocked.
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
