package com.bannerbound.core.api.settlement;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.ResearchManager;

import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The OUTPOST — a remote, exclusive-but-unprotected <b>working claim</b> on an unclaimed chunk
 * near (but outside) a settlement's borders. There is no longer a custom outpost block: an outpost
 * is established by planting a <b>faction banner</b> outside the border and confirming it in the
 * banner's right-click screen ("place then confirm"). The miner/herder/farmer rod markers and
 * worker drop-offs treat a working-claimed chunk as workable territory, and stockers haul from its
 * containers like any other drop-off — so a remote ore chunk becomes a real, supplied extraction
 * site with an exposed supply line.
 *
 * <p><b>The weakness is the design:</b> the banner sits on unprotected land, and breaking it
 * (anyone, survival) drops the claim on the spot — that IS conquest v1. The owning settlement is
 * told the moment it happens.
 *
 * <p>World-event wiring (break / right-click / stale sweep) lives in
 * {@code FactionBannerEvents}; this class holds the rules and the management/establish screen
 * payloads, parallel to {@link FactionBanner}. See MINER_PLAN.md / FACTION_BANNER_PLAN.md.
 */
@ApiStatus.Internal
public final class Outpost {
    private Outpost() {}

    /** Research flag gating outposts. */
    public static final String FLAG_OUTPOST = "bannerbound.unlock.outpost";
    /** Base simultaneous outposts per settlement (the strategic scarcity lever — start stingy).
     *  Research raises it — see {@link #maxOutposts}. */
    public static final int BASE_OUTPOSTS = 2;
    /** How many numbered {@code bannerbound.outpost_slot_N} flags the cap scan checks. */
    private static final int MAX_SLOT_FLAGS = 3;
    /** Max distance (chunks, Chebyshev) from the settlement's nearest claimed chunk. */
    public static final int OUTPOST_RANGE_CHUNKS = 8;

    /** The settlement's outpost cap: {@link #BASE_OUTPOSTS} + one per completed research (science
     *  OR culture) granting a numbered {@code bannerbound.outpost_slot_N} flag — counted, so
     *  several researches stack without code changes. */
    public static int maxOutposts(Settlement settlement) {
        int max = BASE_OUTPOSTS;
        for (int i = 1; i <= MAX_SLOT_FLAGS; i++) {
            if (ResearchManager.hasFlagEitherTree(settlement, "bannerbound.outpost_slot_" + i)) max++;
        }
        return max;
    }

    /** True when {@code cp} is within {@link #OUTPOST_RANGE_CHUNKS} of any chunk the settlement
     *  fully claims (the "near, but outside, the border" rule). */
    public static boolean withinRange(Settlement settlement, ChunkPos cp) {
        for (long packed : settlement.claimedChunks()) {
            ChunkPos own = new ChunkPos(packed);
            if (Math.max(Math.abs(own.x - cp.x), Math.abs(own.z - cp.z)) <= OUTPOST_RANGE_CHUNKS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates everything and, on success, grants the working claim, records the banner block
     * that established it (so the stale sweep can later notice if it's blown up), and plays the
     * founding fanfare. Returns null on success, else the failure lang key. Called from the
     * banner's "Establish outpost here" action.
     */
    public static String tryEstablish(ServerLevel sl, ServerPlayer player, BlockPos pos) {
        if (sl.dimension() != Level.OVERWORLD) return "bannerbound.outpost.overworld_only";
        SettlementData data = SettlementData.get(sl.getServer().overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null) return "bannerbound.outpost.no_settlement";
        if (!ResearchManager.hasFlag(settlement, FLAG_OUTPOST)) return "bannerbound.outpost.not_researched";
        ChunkPos cp = new ChunkPos(pos);
        long packed = cp.toLong();
        if (settlement.claimedChunks().contains(packed)) return "bannerbound.outpost.inside_territory";
        // Outposts may sit NEAR a city-state, but not on its claimed territory.
        if (com.bannerbound.core.citystate.CityStateData.get(sl.getServer().overworld())
                .getByChunk(packed) != null) return "bannerbound.outpost.in_city_state";
        if (data.getByChunk(packed) != null) return "bannerbound.outpost.chunk_taken";
        if (data.getByWorkingClaim(packed) != null) return "bannerbound.outpost.chunk_taken";
        if (settlement.workingClaims().size() >= maxOutposts(settlement)) return "bannerbound.outpost.limit";
        if (!withinRange(settlement, cp)) return "bannerbound.outpost.too_far";
        if (!data.claimWorkingChunk(settlement, cp)) return "bannerbound.outpost.chunk_taken";
        settlement.setOutpostBanner(packed, pos);
        data.setDirty();
        player.displayClientMessage(
            Component.translatable("bannerbound.outpost.established",
                settlement.workingClaims().size(), maxOutposts(settlement))
                .withStyle(ChatFormatting.GREEN), true);
        // Founding fanfare — same celebratory voice as a town-hall food deposit (bell + sparks),
        // so "the claim took" is felt in the world, not just read in chat.
        sl.playSound(null, pos, net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(),
            net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.2f);
        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
            pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 16, 0.6, 0.6, 0.6, 0.0);
        return null;
    }

    /**
     * The outpost is lost: drop the working claim, remove the banner-owned work marker (miner
     * deposit / crop field — pens are unbound, not destroyed), and — UNLESS a member dismantled
     * it — sound the alarm faction-wide (on-site toll, faction-wide toll, red broadcast, ~1h
     * ALERT status so offline members still learn of it). The member "dismantled" toast is sent
     * by the break handler (it knows the breaker); this handles the claim drop + the hostile
     * alarm, matching the pre-refactor split.
     */
    public static void loseOutpost(ServerLevel sl, Settlement owner, BlockPos pos, boolean memberBreak) {
        MinecraftServer server = sl.getServer();
        SettlementData data = SettlementData.get(server.overworld());
        ChunkPos cp = new ChunkPos(pos);
        if (data.getByWorkingClaim(cp.toLong()) == null) return; // no live claim here
        data.unclaimWorkingChunk(owner, cp);
        com.bannerbound.core.api.world.BlockSelection marker = findWorkMarker(sl, owner, cp, null);
        if (marker != null) {
            com.bannerbound.core.api.world.BlockSelectionRegistry.get(server.overworld())
                .unregister(marker.rodId());
            com.bannerbound.core.world.SelectionBroadcaster.broadcast(server);
        }
        if (!memberBreak) {
            sl.playSound(null, pos, net.minecraft.sounds.SoundEvents.BELL_BLOCK,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.5f, 0.6f);
            // The loss SURVIVES the moment: a town-hall Statuses entry (~1h real) so members who
            // were OFFLINE still learn of it when they next check in. Stopgap — proper offline-event
            // delivery is OFFLINE_PLAY_PLAN.md's project.
            owner.addStatusEffect(new StatusEffect(
                UUID.randomUUID(), "bannerbound.status.outpost_lost",
                java.util.List.of(pos.getX() + ", " + pos.getZ()),
                StatusEffectIcon.ALERT, 0, 72_000));
            SettlementManager.broadcastStatusEffectsToMembers(server, owner);
            for (UUID memberId : owner.members()) {
                ServerPlayer member = server.getPlayerList().getPlayer(memberId);
                if (member == null) continue;
                member.sendSystemMessage(Component.translatable("bannerbound.outpost.lost",
                        pos.getX(), pos.getZ())
                    .withStyle(ChatFormatting.RED));
                // The toll reaches the WHOLE faction, wherever they are — the alarm is half the
                // message. Members near the site already heard the world toll, so skip them.
                boolean heardOnSite = member.level() == sl
                    && member.blockPosition().closerThan(pos, 48);
                if (!heardOnSite) {
                    member.playNotifySound(net.minecraft.sounds.SoundEvents.BELL_BLOCK,
                        net.minecraft.sounds.SoundSource.AMBIENT, 1.0f, 0.6f);
                }
            }
        }
    }

    /**
     * Stale-registration sweep: re-checks each of the settlement's outpost banner positions and,
     * if the block is gone (explosion, piston, {@code /setblock} — removals that fire no break
     * event), treats the outpost as struck down. Only loaded positions are judged; an unloaded
     * banner is presumed standing. Legacy claims with no recorded banner pos are skipped. Called
     * from the once-a-second banner sweep, mirroring {@link FactionBanner#validate}.
     */
    public static void validateOutposts(ServerLevel sl, Settlement owner) {
        if (owner.workingClaims().isEmpty()) return;
        // Snapshot — loseOutpost mutates workingClaims.
        for (long packed : new java.util.ArrayList<>(owner.workingClaims())) {
            BlockPos pos = owner.outpostBannerPos(packed);
            if (pos == null || !sl.isLoaded(pos)) continue;
            if (!FactionBanner.isBanner(sl.getBlockState(pos))) {
                loseOutpost(sl, owner, pos, false);
            }
        }
    }

    // ─── Management / establish screen ───────────────────────────────────────────────────────

    /**
     * Builds the outpost's full status (deposit / storage / lodging / appointed miner /
     * candidates) and sends the management screen for an ALREADY-established outpost. Also the
     * post-assign refresh — the assign handler calls this after mutating the marker so the open
     * screen live-updates.
     */
    public static void openScreen(ServerLevel sl, ServerPlayer sp, BlockPos bannerPos) {
        SettlementData data = SettlementData.get(sl.getServer().overworld());
        ChunkPos cp = new ChunkPos(bannerPos);
        Settlement owner = data.getByWorkingClaim(cp.toLong());
        if (owner == null) return;
        com.bannerbound.core.territory.ChunkResource type =
            com.bannerbound.core.territory.ChunkResources.typeAt(sl, cp);
        boolean ore = com.bannerbound.core.territory.BoulderLayout.isOreChunk(type);
        boolean material = com.bannerbound.core.territory.MaterialDepositLayout.isMaterialChunk(type);
        String resourceName = type != com.bannerbound.core.territory.ChunkResource.NONE
            ? type.name().toLowerCase(java.util.Locale.ROOT) : "";
        String expectedJob = expectedJob(type);
        String markerType = markerTypeFor(type);
        boolean storage = com.bannerbound.core.entity.MinerWorkGoal.findOutpostStorage(sl, cp, bannerPos) != null;
        int beds = countRoofedBeds(sl, cp, bannerPos.getY());

        com.bannerbound.core.api.world.BlockSelection marker =
            markerType == null ? null : findWorkMarker(sl, owner, cp, markerType);
        // STALE APPOINTMENT cleanup: the bound worker died (gone from the roster) or was
        // re-jobbed — the post auto-vacates instead of pointing at a ghost forever. A miner
        // marker is banner-owned, so it's removed outright; a herder PEN survives (it carries
        // the player's animal/keep config) and just unbinds.
        if (marker != null && !marker.targetsAllWorkers()) {
            java.util.UUID boundId = marker.assignedCitizenId();
            boolean dead = rosterName(owner, boundId) == null;
            boolean reJobbed = sl.getEntity(boundId) instanceof com.bannerbound.core.entity.CitizenEntity bc
                && !java.util.Objects.equals(expectedJob, bc.getJobType());
            if (dead || reJobbed) {
                com.bannerbound.core.api.world.BlockSelectionRegistry registry =
                    com.bannerbound.core.api.world.BlockSelectionRegistry.get(sl.getServer().overworld());
                String mt = marker.workstationType();
                if (com.bannerbound.core.entity.MinerWorkGoal.SELECTION_TYPE.equals(mt)
                        || com.bannerbound.core.entity.FarmerWorkGoal.OUTPOST_SELECTION_TYPE.equals(mt)
                        || (com.bannerbound.core.entity.DiggerWorkGoal.SELECTION_TYPE.equals(mt)
                            && com.bannerbound.core.territory.MaterialDepositLayout
                                .isMaterialPacked(marker.seedItemId()))) {
                    registry.unregister(marker.rodId());   // banner-owned (miner deposit / crop field)
                    marker = null;
                } else {
                    marker = marker.withAssignedCitizen(null);
                    registry.register(marker);
                }
                com.bannerbound.core.world.SelectionBroadcaster.broadcast(sl.getServer());
            }
        }
        boolean markerOpen = marker != null && marker.targetsAllWorkers();
        String assignedName = "";
        if (marker != null && !markerOpen) {
            // Roster-backed name: correct even while the worker entity is unloaded far away.
            String n = rosterName(owner, marker.assignedCitizenId());
            assignedName = cleanName(n != null ? n : "Worker");
        }
        // Vein gauge: how many ore faces are mineable RIGHT NOW vs the deposit's full face count —
        // drives the screen's vein bar, and 0 ready = "she's waiting for the next refresh wave".
        int veinReady = -1;
        int veinTotal = 0;
        int richness = ore ? com.bannerbound.core.territory.BoulderLayout.richness(sl.getSeed(), cp) : -1;
        if (ore) {
            Integer baseY = marker != null
                ? com.bannerbound.core.entity.MinerWorkGoal.mineBaseY(marker.seedItemId())
                : com.bannerbound.core.territory.BoulderLayout.locateBaseY(sl, cp, type).orElse(Integer.MIN_VALUE);
            if (baseY != Integer.MIN_VALUE) {
                veinReady = 0;
                net.minecraft.world.level.block.Block oreBlock =
                    com.bannerbound.core.territory.BoulderLayout.oreBlock(type).getBlock();
                for (com.bannerbound.core.territory.BoulderLayout.Spot s
                        : com.bannerbound.core.territory.BoulderLayout.spots(sl.getSeed(), cp, baseY)) {
                    if (!s.ore()) continue;
                    veinTotal++;
                    if (sl.getBlockState(s.pos()).is(oreBlock)) veinReady++;
                }
            }
        }
        if (material) {
            Integer baseY = marker != null
                ? com.bannerbound.core.territory.MaterialDepositLayout.materialBaseY(marker.seedItemId())
                : com.bannerbound.core.territory.MaterialDepositLayout.locateBaseY(sl, cp, type)
                    .orElse(Integer.MIN_VALUE);
            if (baseY != Integer.MIN_VALUE) {
                veinReady = 0;
                net.minecraft.world.level.block.Block sourceBlock =
                    com.bannerbound.core.territory.MaterialDepositLayout.sourceBlock(type).getBlock();
                for (com.bannerbound.core.territory.MaterialDepositLayout.Spot s
                        : com.bannerbound.core.territory.MaterialDepositLayout
                            .spots(sl.getSeed(), cp, baseY, type)) {
                    if (!s.source()) continue;
                    veinTotal++;
                    if (sl.getBlockState(s.pos()).is(sourceBlock)) veinReady++;
                }
            }
        }
        // Assignable candidates: loaded settlement citizens holding the chunk's expected job
        // (miner for ore, herder for livestock) — minus the one already appointed here.
        java.util.UUID appointedId = marker != null && !markerOpen ? marker.assignedCitizenId() : null;
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        if (expectedJob != null) {
            for (com.bannerbound.core.entity.CitizenEntity c : sl.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(
                        com.bannerbound.core.entity.CitizenEntity.class),
                    c -> c.isAlive() && owner.id().equals(c.getSettlementId())
                        && expectedJob.equals(c.getJobType()))) {
                if (c.getUUID().equals(appointedId)) continue;
                ids.add(c.getUUID().toString());
                names.add(cleanName(c.getCustomName() != null ? c.getCustomName().getString() : "Worker"));
            }
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
            new com.bannerbound.core.network.OpenOutpostScreenPayload(bannerPos, resourceName, storage,
                beds, veinReady, veinTotal, richness, markerOpen, assignedName, ids, names,
                owner.workingClaims().size(), maxOutposts(owner), true));
    }

    /**
     * Sends the "establish" variant of the screen for a NOT-yet-claimed banner standing on a
     * valid outpost site: the chunk's resource, lodging/storage readiness, the settlement's
     * slot count, and an "Establish outpost here" button. Confirming runs {@link #tryEstablish}.
     */
    public static void openEstablishScreen(ServerLevel sl, ServerPlayer sp, BlockPos bannerPos) {
        SettlementData data = SettlementData.get(sl.getServer().overworld());
        Settlement mine = data.getByPlayer(sp.getUUID());
        if (mine == null) return;
        ChunkPos cp = new ChunkPos(bannerPos);
        com.bannerbound.core.territory.ChunkResource type =
            com.bannerbound.core.territory.ChunkResources.typeAt(sl, cp);
        String resourceName = type != com.bannerbound.core.territory.ChunkResource.NONE
            ? type.name().toLowerCase(java.util.Locale.ROOT) : "";
        int richness = com.bannerbound.core.territory.BoulderLayout.isOreChunk(type)
            ? com.bannerbound.core.territory.BoulderLayout.richness(sl.getSeed(), cp) : -1;
        boolean storage = com.bannerbound.core.entity.MinerWorkGoal.findOutpostStorage(sl, cp, bannerPos) != null;
        int beds = countRoofedBeds(sl, cp, bannerPos.getY());
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
            new com.bannerbound.core.network.OpenOutpostScreenPayload(bannerPos, resourceName, storage,
                beds, -1, 0, richness, false, "", java.util.List.of(), java.util.List.of(),
                mine.workingClaims().size(), maxOutposts(mine), false));
    }

    /** Citizen display names carry a private-use-area job-glyph char that only the name-tag font
     *  can draw — in a plain GUI font it renders as a tofu box. Strip it for screen text. */
    private static String cleanName(String name) {
        return name.replaceAll("[\\uE000-\\uF8FF]", "").trim();
    }

    /** Livestock chunk → the outpost's workforce is a HERDER (pen-based) rather than a miner. */
    private static boolean isLivestockChunk(com.bannerbound.core.territory.ChunkResource t) {
        return switch (t) {
            case HORSES, CATTLE, PIGS, CHICKENS, SHEEP -> true;
            default -> false;
        };
    }

    /** The job id an outpost on this chunk appoints (miner for ore, herder for livestock, farmer for
     *  a crop field), or null when the chunk supports no outpost work yet (fish — later). */
    public static String expectedJob(com.bannerbound.core.territory.ChunkResource t) {
        if (com.bannerbound.core.territory.BoulderLayout.isOreChunk(t)) {
            return com.bannerbound.core.entity.MinerWorkGoal.JOB_TYPE_ID;
        }
        if (com.bannerbound.core.territory.MaterialDepositLayout.isMaterialChunk(t)) {
            return com.bannerbound.core.entity.DiggerWorkGoal.JOB_TYPE_ID;
        }
        if (isLivestockChunk(t)) return com.bannerbound.core.entity.HerderWorkGoal.JOB_TYPE_ID;
        if (com.bannerbound.core.territory.CropChunks.isCropChunk(t)) {
            return com.bannerbound.core.entity.FarmerWorkGoal.JOB_TYPE_ID;
        }
        return null;
    }

    /** The rod-selection type the banner manages for this chunk, or null. */
    private static String markerTypeFor(com.bannerbound.core.territory.ChunkResource t) {
        if (com.bannerbound.core.territory.BoulderLayout.isOreChunk(t)) {
            return com.bannerbound.core.entity.MinerWorkGoal.SELECTION_TYPE;
        }
        if (com.bannerbound.core.territory.MaterialDepositLayout.isMaterialChunk(t)) {
            return com.bannerbound.core.entity.DiggerWorkGoal.SELECTION_TYPE;
        }
        if (isLivestockChunk(t)) return com.bannerbound.core.entity.HerderWorkGoal.SELECTION_TYPE;
        if (com.bannerbound.core.territory.CropChunks.isCropChunk(t)) {
            return com.bannerbound.core.entity.FarmerWorkGoal.OUTPOST_SELECTION_TYPE;
        }
        return null;
    }

    /** The outpost chunk's work marker (miner deposit / herder pen), or null. {@code selectionType}
     *  null = match either kind (banner-fall cleanup). The banner owns at most one. */
    private static com.bannerbound.core.api.world.BlockSelection findWorkMarker(
            ServerLevel sl, Settlement owner, ChunkPos cp, @org.jetbrains.annotations.Nullable String selectionType) {
        for (com.bannerbound.core.api.world.BlockSelection sel
                : com.bannerbound.core.api.world.BlockSelectionRegistry.get(sl.getServer().overworld())
                    .getForSettlement(owner.id())) {
            if (sel.kind() != com.bannerbound.core.api.world.BlockSelection.Kind.WORKSTATION) continue;
            String t = sel.workstationType();
            boolean matches = selectionType != null ? selectionType.equals(t)
                : com.bannerbound.core.entity.MinerWorkGoal.SELECTION_TYPE.equals(t)
                    || com.bannerbound.core.entity.HerderWorkGoal.SELECTION_TYPE.equals(t)
                    || com.bannerbound.core.entity.FarmerWorkGoal.OUTPOST_SELECTION_TYPE.equals(t)
                    || (com.bannerbound.core.entity.DiggerWorkGoal.SELECTION_TYPE.equals(t)
                        && com.bannerbound.core.territory.MaterialDepositLayout
                            .isMaterialPacked(sel.seedItemId()));
            if (!matches) continue;
            if (com.bannerbound.core.entity.DiggerWorkGoal.SELECTION_TYPE.equals(t)
                    && !com.bannerbound.core.territory.MaterialDepositLayout
                        .isMaterialPacked(sel.seedItemId())) {
                continue;
            }
            if (cp.equals(new ChunkPos(new BlockPos(sel.minX(), sel.minY(), sel.minZ())))) return sel;
        }
        return null;
    }

    /** Make {@code citizenId} a RESIDENT OF THE OUTPOST: anchor its persistent {@code outpostSite}
     *  (so patrol/idle/sleep stay on site and it never wanders back to town) and evict any settlement
     *  house it held (it lives out here now). Anchor = the chunk centre at banner height — an
     *  approximate home point; the work goal refines it to the exact work block while it runs. No-op
     *  on the entity if it's unloaded (the work goal sets it when the worker next loads + scans). */
    private static void bindOutpostResident(ServerLevel sl, Settlement owner, ChunkPos cp,
                                            BlockPos bannerPos, java.util.UUID citizenId) {
        if (citizenId == null) return;
        BlockPos anchor = new BlockPos(cp.getMinBlockX() + 8, bannerPos.getY(), cp.getMinBlockZ() + 8);
        if (sl.getEntity(citizenId) instanceof com.bannerbound.core.entity.CitizenEntity c) {
            c.setOutpostSite(anchor);
        }
        Home home = owner.getHomeFor(citizenId);
        if (home != null) {
            home.removeResident(citizenId);
            SettlementData.get(sl).setDirty();
        }
    }

    /** Reverse of {@link #bindOutpostResident}: the worker no longer lives at an outpost. Clears its
     *  residence so {@code tryAutoAssignHome} re-homes it in town. (Home left for the auto-poll.) */
    private static void unbindOutpostResident(ServerLevel sl, @org.jetbrains.annotations.Nullable java.util.UUID citizenId) {
        if (citizenId != null && sl.getEntity(citizenId) instanceof com.bannerbound.core.entity.CitizenEntity c) {
            c.setOutpostSite(null);
        }
    }

    /** Roster lookup (works while the entity is UNLOADED — the roster is settlement data). */
    private static String rosterName(Settlement owner, java.util.UUID citizenId) {
        for (com.bannerbound.core.api.settlement.Citizen c : owner.citizens()) {
            if (c.entityId().equals(citizenId)) return c.name();
        }
        return null;
    }

    /**
     * Appoints {@code citizenId} as the outpost's worker — miner on ore chunks (creating or
     * re-binding the banner-owned deposit marker), herder on livestock chunks (re-binding the
     * existing rod-marked PEN — pens carry player config and are never auto-created). A null
     * {@code citizenId} recalls the worker (miner marker removed; herder pen unbound).
     * Returns null on success, else the failure's lang key.
     */
    @org.jetbrains.annotations.Nullable
    public static String setOutpostWorker(ServerLevel sl, Settlement owner, BlockPos bannerPos,
                                          @org.jetbrains.annotations.Nullable java.util.UUID citizenId,
                                          java.util.UUID actingPlayer) {
        ServerLevel overworld = sl.getServer().overworld();
        com.bannerbound.core.api.world.BlockSelectionRegistry registry =
            com.bannerbound.core.api.world.BlockSelectionRegistry.get(overworld);
        ChunkPos cp = new ChunkPos(bannerPos);
        com.bannerbound.core.territory.ChunkResource type =
            com.bannerbound.core.territory.ChunkResources.typeAt(sl, cp);
        String markerType = markerTypeFor(type);
        if (markerType == null) return "bannerbound.outpost.no_deposit";
        boolean ore = com.bannerbound.core.territory.BoulderLayout.isOreChunk(type);
        boolean crop = com.bannerbound.core.territory.CropChunks.isCropChunk(type);
        boolean material = com.bannerbound.core.territory.MaterialDepositLayout.isMaterialChunk(type);
        com.bannerbound.core.api.world.BlockSelection existing = findWorkMarker(sl, owner, cp, markerType);
        if (citizenId == null) {
            if (existing != null) {
                // Recalled → the worker no longer lives here; clear its outpost residence so it can be
                // re-homed in town and resume settlement life.
                if (!existing.targetsAllWorkers()) unbindOutpostResident(overworld, existing.assignedCitizenId());
                if (ore || crop || material) {
                    registry.unregister(existing.rodId());   // banner-owned; recreated on appoint
                } else {
                    registry.register(existing.withAssignedCitizen(null));   // pen survives, unbound
                }
                com.bannerbound.core.world.SelectionBroadcaster.broadcast(sl.getServer());
            }
            return null;
        }
        // Appointed (here or in any branch below) → this citizen now LIVES at the outpost. Free the
        // previous occupant (reappointment) and anchor the new one there + give up its town house.
        if (existing != null && !existing.targetsAllWorkers()) {
            java.util.UUID prev = existing.assignedCitizenId();
            if (prev != null && !prev.equals(citizenId)) unbindOutpostResident(overworld, prev);
        }
        bindOutpostResident(overworld, owner, cp, bannerPos, citizenId);
        if (existing != null) {
            registry.register(existing.withAssignedCitizen(citizenId));
            com.bannerbound.core.world.SelectionBroadcaster.broadcast(sl.getServer());
            return null;
        }
        if (crop) {
            // Banner-owned crop field over the chunk's central region. The farmer tills/plants the
            // chunk's crop (seedItemId) from the hauled-in seed chest and harvests at 2× (crop-chunk
            // bonus); it wires its own in-chunk seed source + drop-off in FarmerWorkGoal each cycle.
            int baseY = com.bannerbound.core.territory.BoulderLayout.groundSurfaceY(
                sl, cp.getMinBlockX() + 8, cp.getMinBlockZ() + 8);
            BlockPos lo = new BlockPos(cp.getMinBlockX() + 2, baseY - 5, cp.getMinBlockZ() + 2);
            BlockPos hi = new BlockPos(cp.getMinBlockX() + 14, baseY + 1, cp.getMinBlockZ() + 14);
            net.minecraft.resources.ResourceLocation seedKey = net.minecraft.core.registries.BuiltInRegistries
                .ITEM.getKey(com.bannerbound.core.territory.CropChunks.seedFor(type));
            com.bannerbound.core.api.world.BlockSelection marker =
                com.bannerbound.core.api.world.BlockSelection.workstation(
                    java.util.UUID.randomUUID(), owner.id(), owner.color().ordinal(),
                    lo, hi, com.bannerbound.core.entity.FarmerWorkGoal.OUTPOST_SELECTION_TYPE,
                    actingPlayer, seedKey.toString())
                .withAssignedCitizen(citizenId);
            registry.register(marker);
            com.bannerbound.core.world.SelectionBroadcaster.broadcast(sl.getServer());
            return null;
        }
        if (material) {
            if (com.bannerbound.core.territory.MaterialDepositLayout.isStoneBoulder(type)
                    && !com.bannerbound.core.api.research.ResearchManager.hasFlag(
                        owner, com.bannerbound.core.social.WorkstationNames.FLAG_QUARRY)) {
                return "bannerbound.foremans_rod.not_researched";
            }
            int baseY = com.bannerbound.core.territory.MaterialDepositLayout.locateBaseY(sl, cp, type)
                .orElseGet(() -> com.bannerbound.core.territory.MaterialDepositLayout.dress(sl, cp));
            if (baseY == Integer.MIN_VALUE) return "bannerbound.outpost.no_deposit";
            BlockPos anchor = new BlockPos(cp.getMinBlockX() + 8, baseY, cp.getMinBlockZ() + 8);
            com.bannerbound.core.api.world.BlockSelection marker =
                com.bannerbound.core.api.world.BlockSelection.workstation(
                    java.util.UUID.randomUUID(), owner.id(), owner.color().ordinal(),
                    anchor, anchor, com.bannerbound.core.entity.DiggerWorkGoal.SELECTION_TYPE,
                    actingPlayer,
                    com.bannerbound.core.territory.MaterialDepositLayout.packDeposit(type, baseY))
                .withAssignedCitizen(citizenId);
            registry.register(marker);
            com.bannerbound.core.world.SelectionBroadcaster.broadcast(sl.getServer());
            return null;
        }
        if (!ore) {
            // No pen marked in this chunk yet — pens need fences + an animal choice, which only
            // the Foreman's Rod flow provides. Tell the player what to build.
            return "bannerbound.outpost.no_pen";
        }
        if (com.bannerbound.core.territory.BoulderLayout.dropFor(type).isEmpty()) {
            return "bannerbound.outpost.no_deposit";
        }
        int baseY = com.bannerbound.core.territory.BoulderLayout.locateBaseY(sl, cp, type)
            .orElseGet(() -> com.bannerbound.core.territory.BoulderLayout.dress(sl, cp));
        BlockPos anchor = new BlockPos(cp.getMinBlockX() + 8, baseY + 1, cp.getMinBlockZ() + 8);
        com.bannerbound.core.api.world.BlockSelection marker =
            com.bannerbound.core.api.world.BlockSelection.workstation(
                java.util.UUID.randomUUID(), owner.id(), owner.color().ordinal(),
                anchor, anchor, com.bannerbound.core.entity.MinerWorkGoal.SELECTION_TYPE,
                actingPlayer,
                com.bannerbound.core.entity.MinerWorkGoal.packMine(type, baseY))
            .withAssignedCitizen(citizenId);
        registry.register(marker);
        com.bannerbound.core.world.SelectionBroadcaster.broadcast(sl.getServer());
        return null;
    }

    /** Roofed bed HEADs in the outpost chunk (same roof rule as SleepGoal's outpost lodging:
     *  any motion-blocking block within 6 above — walls not required). */
    private static int countRoofedBeds(ServerLevel sl, ChunkPos cp, int aroundY) {
        int count = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = cp.getMinBlockX(); x <= cp.getMaxBlockX(); x++) {
            for (int z = cp.getMinBlockZ(); z <= cp.getMaxBlockZ(); z++) {
                for (int y = aroundY - 12; y <= aroundY + 12; y++) {
                    m.set(x, y, z);
                    BlockState bs = sl.getBlockState(m);
                    if (!(bs.getBlock() instanceof net.minecraft.world.level.block.BedBlock)) continue;
                    if (bs.getValue(net.minecraft.world.level.block.BedBlock.PART)
                            != net.minecraft.world.level.block.state.properties.BedPart.HEAD) continue;
                    boolean roofed = false;
                    for (int dy = 1; dy <= 6 && !roofed; dy++) {
                        roofed = sl.getBlockState(m.offset(0, dy, 0)).blocksMotion();
                    }
                    if (roofed) count++;
                }
            }
        }
        return count;
    }
}
