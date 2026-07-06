package com.bannerbound.core.api.settlement;

import java.util.UUID;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.network.PopulationStatePayload;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Per-tick accumulator that turns a settlement's food/culture production into new citizens.
 * Each tick adds {@code (foodPerSecond / 20, culturePerSecond / 20)} to the stored pools. When
 * both pools reach the population-scaled cost, the cost is subtracted, a {@link CitizenEntity}
 * is spawned near the town hall, and the roster is updated.
 * <p>
 * Broadcasts a {@link PopulationStatePayload} once per second to every member of every
 * settlement so any open {@link com.bannerbound.core.client.TownHallScreen} reads live
 * progress without per-frame request/reply chatter.
 */
public final class ImmigrationManager {
    private static int tickCounter = 0;
    /** Below this population a settlement immigrates on CULTURE alone — the food surplus gate is waived.
     *  A government-era settlement that loses all its citizens has no workers, so it can never run a food
     *  surplus; without this waiver a wiped-out tribe would be permanently soft-locked (the food gate
     *  could never pass again). Above it, a food surplus is required to grow. At founding the anarchy
     *  food trickle is already a surplus, so this only ever matters for recovery. */
    private static final int IMMIGRATION_FOOD_GRACE_POP = 2;

    private ImmigrationManager() {
    }

    public static void tickAll(MinecraftServer server) {
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        SettlementData data = SettlementData.get(overworld);
        tickCounter++;
        boolean broadcastTick = (tickCounter % 20 == 0);
        boolean anyChange = false;

        // Snapshot: a tribe-backed disband (enactPendingDisband below) removes its settlement
        // from `data`, which would otherwise CME this iteration over the live values view.
        for (Settlement s : new java.util.ArrayList<>(data.all())) {
            // Settlement food: rescan claimed storage into the passive stored-food value + food/sec
            // (LarderService), then update the per-source production-rate stats. The abstract food bar
            // (foodStored) accumulates the net trend in the immigration block below.
            if (broadcastTick) {
                // Dormancy (no member online → frozen "in amber") is recomputed at the top of the
                // server tick by SettlementManager.refreshDormancy, before every consumer.
                // Skip the larder scan while dormant — this freezes stored-food SPOILAGE (LarderHooks.process
                // runs inside refresh) and the storedFoodValue/rate churn, and saves the scan cost for an
                // unattended colony. Population can't change while dormant, so the peak mark is unaffected.
                if (!s.isDormant()) {
                    com.bannerbound.core.api.settlement.food.LarderService.refresh(overworld, s);
                    s.tickFoodEconomyStats(overworld.getGameTime());
                    // Track the population high-water mark so the starvation crisis demands food for the
                    // full tribe the settlement grew into, not for whoever survives a die-off.
                    s.notePopulationPeak();
                }
            }
            // Tick the per-settlement status effects every tick (decrement remaining; drop
            // expired). If anything was removed, push the new list to members so the Statuses
            // tab's progress bars converge to the server truth instead of drifting on local
            // tick counts alone.
            if (s.tickStatusEffects()) {
                SettlementManager.broadcastStatusEffectsToMembers(server, s);
                anyChange = true;
            }
            if (s.tickFoodSourcePulses()) {
                anyChange = true;
            }

            // Labor distribution: once per second, balance gatherer-job staffing toward the
            // settlement's weighted priority targets — employ idle adults, re-skill one over-staffed
            // worker. Runs in anarchy always, and under a government only while auto-assign is left on
            // (a chief/council can switch it off to assign per-citizen).
            if (broadcastTick
                    && (s.governmentType() == Settlement.Government.NONE || s.laborAutoAssign())) {
                com.bannerbound.core.entity.AnarchyJobDistributor.tick(overworld, s);
            }

            // Immigration: CULTURE is the growth currency — it accumulates toward the population-scaled
            // cost and is spent on each new citizen (the visible "progress to next citizen" bar). FOOD is
            // a GATE, not a cost: a settlement only grows while it is feeding itself (net food ≥ 0), so
            // you can't attract newcomers you can't feed. The food bar (foodStored) is a survival BUFFER
            // that banks the net food trend (passive stored-food income − consumption), clamped to the
            // cap; a deficit drains it toward 0 (→ starvation). It is NOT spent per citizen — the old
            // per-citizen fill→spend→refill cycle (you had to dip out of surplus and back in for every
            // immigrant) was confusing and is gone.
            double effectiveFoodPerSec = s.effectiveFoodPerSecond();
            double effectiveCulturePerSec = s.effectiveCulturePerSecond(overworld);
            double newFood = Math.max(0.0,
                Math.min(s.foodCap(), s.foodStored() + effectiveFoodPerSec / 20.0));
            double newCulture = Math.max(0.0,
                Math.min(s.cultureCap(), s.cultureStored() + effectiveCulturePerSec / 20.0));
            double cultureCost = s.nextCultureCost();

            // Minimum spacing between immigrants (the real pacing lever — the culture bar fills quickly).
            long now = overworld.getGameTime();
            long cooldownTicks = (long) (com.bannerbound.core.Config.IMMIGRATION_MIN_SECONDS_BETWEEN.get() * 20.0);
            boolean cooldownElapsed = now - s.lastImmigrationTick() >= cooldownTicks;

            boolean immigrated = false;
            // FLOOR, not a lifetime cap: a settlement whose citizens all died drops back below the
            // floor and immigrates again, so the player can never be permanently soft-locked. Below the
            // food-grace population the food gate is WAIVED (culture + cooldown only) — a wiped-out,
            // government-era settlement has no workers, so it can never be in surplus and requiring food
            // would lock it out forever. Above the grace pop, a food surplus is required to grow.
            boolean recovering = s.population() < IMMIGRATION_FOOD_GRACE_POP;
            boolean fed = recovering || effectiveFoodPerSec >= -1.0e-6;
            // Frozen in amber: while every member is offline (dormant) the settlement neither loses food
            // (consumption is gated to 0 and the larder scan is skipped above) NOR grows. Skip the
            // immigration spawn and the food/culture bar commits so culture, the food bar, and population
            // are exactly as the last player left them — like single-player, where the server simply stops
            // ticking for an absent world. (Consumption being 0 would otherwise leave the food gate open
            // and let a force-loaded dormant tribe keep accruing culture and taking in immigrants.)
            if (!s.isDormant()) {
                if (fed && newCulture >= cultureCost && cooldownElapsed
                    && s.population() < s.immigrationFloor()) {
                    newCulture -= cultureCost;   // food is a gate, not a cost — only culture is spent
                    if (spawnImmigrant(overworld, s, true)) {
                        immigrated = true;
                        anyChange = true;
                        s.setLastImmigrationTick(now);
                    } else {
                        newCulture += cultureCost;   // spawn failed (town hall not loaded) — refund
                    }
                }
                s.setFoodStored(newFood);
                s.setCultureStored(newCulture);
            }
            if (immigrated) {
                anyChange = true;
                // Code of Laws one-shot: the first time population hits the era's immigration
                // floor, broadcast the prompt so players know they can choose a government.
                // The promptShown flag persists in NBT — reload won't re-broadcast, and a
                // later population dip + refill won't either (the prompt only "fires" once
                // per settlement, ever).
                if (!s.codeOfLawsPromptShown() && s.population() >= s.immigrationFloor()) {
                    s.setCodeOfLawsPromptShown(true);
                    SettlementManager.broadcastToSettlement(server, s,
                        Component.translatable("bannerbound.laws.enacting")
                            .withStyle(ChatFormatting.GOLD));
                }
            }
            // Settlement stage-up: one-shot celebration (chat + fireworks) when growth crosses into
            // a new named stage. Checked every tick so it fires for both natural immigration and the
            // /bannerbound ... set_population|add_population debug commands.
            if (checkStageUp(server, overworld, s)) {
                anyChange = true;
            }

            // Selection-window housekeeping — runs every tick so we react promptly to
            // pop drops or election timeouts. Two cases:
            //   (a) Pull-back: a choose-government vote is active but the window closed
            //       (pop dropped below floor mid-vote, e.g. admin /kill). Abort + broadcast
            //       so the prompt button reappears once pop recovers.
            //   (b) Election timeout: chief election ran > 5 min — resolve by top vote.
            if (s.isGovernmentVoteActive() && !s.governmentChoiceWindowOpen()) {
                s.clearGovernmentVote();
                SettlementManager.broadcastToSettlement(server, s,
                    Component.translatable("bannerbound.government.vote.paused")
                        .withStyle(ChatFormatting.GRAY));
                anyChange = true;
            }
            if (s.isChiefElectionActive()) {
                long electionAgeMs = System.currentTimeMillis() - s.chiefElectionStartedMs();
                if (electionAgeMs > 5L * 60L * 1000L) {
                    SettlementManager.resolveChiefElectionByTopVote(server, s, data);
                    anyChange = true;
                }
            }
            // Pending chief installation — tribe-vote reveal has been displaying for the
            // scheduled animation duration; now actually install the winner. Cleared on hit
            // so subsequent ticks don't re-fire.
            if (s.pendingChiefId() != null
                    && overworld.getGameTime() >= s.pendingChiefEnactTick()) {
                SettlementManager.enactPendingChief(server, s, data);
                anyChange = true;
            }
            // Same drainage for the Choose-Government tribe-vote tiebreaker.
            if (s.pendingGovernmentType() != null
                    && overworld.getGameTime() >= s.pendingGovernmentEnactTick()) {
                SettlementManager.enactPendingGovernment(server, s, data);
                anyChange = true;
            }
            // Tribe-backed (Opinionated Crowd) disband: the citizens' confirming reveal has
            // played for its scheduled duration — dissolve now. This removes the settlement, so
            // skip the rest of this iteration's per-settlement work for it.
            if (s.hasPendingDisband()
                    && overworld.getGameTime() >= s.pendingDisbandEnactTick()) {
                SettlementManager.enactPendingDisband(server, s, data);
                anyChange = true;
                continue;
            }
            // Step 13 v2: settlement-level dawn coup check. Day boundary detection — fire
            // once per in-game day. Runs in every gov type but the manager itself short-
            // circuits on non-CHIEFDOM, so the per-tick cost is negligible.
            long today = overworld.getDayTime() / 24_000L;
            if (today != s.lastCoupCheckDay()) {
                if (s.lastCoupCheckDay() != -1L) {
                    SettlementManager.dawnCoupCheck(server, s);
                    anyChange = true;
                }
                s.setLastCoupCheckDay(today);
            }
            // Dusk pre-warning: once the day rolls into evening (>=12000), broadcast the day's
            // looming consequences (strikes, coup, homelessness) so the dawn checks above are
            // never a surprise. Fired once per in-game day.
            long timeOfDay = overworld.getDayTime() % 24_000L;
            if (timeOfDay >= 12_000L && today != s.lastDuskWarnDay()) {
                SettlementManager.duskWarningCheck(server, s);
                s.setLastDuskWarnDay(today);
            }
            // Step 15: regent recompute heartbeat. The PlayerLogged*Event handlers cover the
            // common case, but a once-per-second sweep catches edge cases (server crash
            // recovery, an admin command silently changing chief, etc.) without measurable
            // cost — recomputeRegent is no-op when the state already matches.
            if (broadcastTick && s.governmentType() == Settlement.Government.CHIEFDOM) {
                SettlementManager.recomputeRegent(server, s);
            }
            // Policies: hourly upkeep (Nightshift/Domestication thoughts + Opinionated bonus).
            // Gated on having something to do so the common no-policy settlement pays nothing,
            // and frozen while dormant like every other per-settlement drain.
            if (!s.isDormant()
                    && (!s.activePolicies().isEmpty() || s.policyOpinionatedBonusExpiry() > 0L)) {
                long policyHour = overworld.getGameTime() / 1000L;
                if (policyHour != s.lastPolicyHour()) {
                    s.setLastPolicyHour(policyHour);
                    PolicyEffects.tickHourly(server, s);
                    anyChange = true;
                }
            }
            if (broadcastTick) {
                broadcastState(server, s);
                // Statistics-tab data (who/what/why + per-workshop rates) — only for settlements that
                // have researched Mathematics; piggybacks the once-per-second cadence.
                broadcastWorkforceStatsIfEnabled(server, s);
                broadcastWorkshopStatsIfEnabled(server, s);
                // Settlement-wide food warning: tell EVERY member (not just whoever has a screen
                // open) when food is running out. Only broadcasts when the bucket changes, so it
                // never spams packets. Piggybacks the once-per-second cadence.
                broadcastFoodWarningIfChanged(server, s);
                // Live suggestion sync (#7): keep an open chief's Suggestions tab current as members
                // add/retract suggestions. Cheap once-per-second re-broadcast piggybacking this
                // cadence; only does packet work when the settlement actually has suggestions.
                if (s.governmentType() == Settlement.Government.CHIEFDOM && s.hasAnySuggestions()) {
                    SettlementManager.broadcastSuggestionState(server, s);
                    SettlementManager.broadcastPolicyState(server, s);
                    SettlementManager.broadcastPaletteState(server, s);
                    SettlementManager.broadcastExtraSuggestions(server, s);
                }
                // Live labor counts for an open Town Hall "Labor" tab so re-skilling is visible in
                // real time (no-op build cost when no members are online — see broadcastLaborState).
                SettlementManager.broadcastLaborState(server, s);
                revalidateWorkstationBuildings(overworld, s);
            }
        }
        // Expire overdue council chat-votes (exile/tablet) — global sweep, not per-settlement.
        if (broadcastTick) {
            ChatVoteManager.tick(server);
        }
        if (anyChange) {
            data.setDirty();
        }
    }

    /**
     * Re-runs {@link com.bannerbound.core.building.BuildingValidator} on every workstation
     * in {@code s} whose chunk is loaded. Updates the cached {@code buildingValid} flag so
     * workstation goals can yield while the building is broken without losing the assignment.
     * Unloaded chunks keep their previous flag value — we don't have visibility to validate.
     */
    private static void revalidateWorkstationBuildings(ServerLevel level, Settlement s) {
        // NOTE: the workstation registry is now always empty (workstation blocks were removed), so
        // this loop is inert. Kept as a no-op until the registry plumbing is fully excised.
        for (com.bannerbound.core.api.settlement.Workstation ws : s.workstations().values()) {
            BlockPos pos = ws.pos();
            if (!level.isLoaded(pos)) continue;
            // Validate at the tier the workstation was placed under. Without this, ancient
            // roof-only workstations get re-validated under the strict ENCLOSED rules and
            // immediately marked invalid every tick (no walls → fail), which silently disables
            // their worker goals.
            com.bannerbound.core.building.BuildingValidator.BuildingTier tier =
                com.bannerbound.core.building.WorkstationPlacement.tierFor(ws.type());
            com.bannerbound.core.building.BuildingValidator.Result r =
                com.bannerbound.core.building.BuildingValidator.validate(level, pos, tier);
            ws.setBuildingValid(r.valid());
        }
    }

    /**
     * Spawns one real roster citizen near the town hall (the natural-immigration path), reusable by
     * debug commands. {@code announce} controls the "X arrived" member broadcast (false for bulk
     * spawns so they don't spam chat). Returns false if the town hall chunk isn't loaded.
     */
    public static boolean spawnImmigrant(ServerLevel level, Settlement s, boolean announce) {
        BlockPos thp = s.townHallPos();
        if (thp == null) return false;
        // Only spawn when the town hall chunk is loaded — otherwise hold off.
        if (!level.isLoaded(thp)) return false;

        BlockPos spawnPos = findSpawnPos(level, thp);
        CitizenEntity entity = BannerboundCore.CITIZEN.get().create(level);
        if (entity == null) return false;

        // Roll gender 50/50, then draw a name from that gender's pool for the settlement's era.
        CitizenGender gender = level.random.nextBoolean() ? CitizenGender.MALE : CitizenGender.FEMALE;
        String name = com.bannerbound.core.api.settlement.data.CitizenNameLoader.randomName(
            level.random, s.age(), gender);
        entity.initializeCitizen(s.id(), name, gender, s.age(), s.identityFormatting());
        // initializeCitizen baked the raw draw into the settlement language — use that styled name
        // for the roster + announcement so every surface reads it the same way the name tag does.
        name = entity.getCitizenName();
        // Compliance at spawn keys off whether a government has been enacted, NOT population:
        //  • Anarchy (no government): citizens arrive with a wide spread of compliance (random
        //    10-40). In anarchy this no longer gates work — self-organized citizens always work —
        //    it governs whether a citizen consents to a player-REQUESTED job switch: a stubborn
        //    (low-compliance) one is likely to refuse and keep its current gatherer job.
        //  • Once any government is in place: new citizens start fully compliant (100), matching
        //    the jump every existing citizen takes the moment the government is enacted.
        if (s.governmentType() == Settlement.Government.NONE) {
            entity.setCompliance(10 + level.random.nextInt(31)); // 10..40 inclusive
        } else {
            entity.setCompliance(100);
        }
        entity.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
            level.random.nextFloat() * 360.0f, 0.0f);
        entity.finalizeSpawn(level, level.getCurrentDifficultyAt(spawnPos),
            MobSpawnType.MOB_SUMMONED, null);
        if (!level.addFreshEntity(entity)) {
            return false;
        }

        // Record on the settlement's roster + bump the lifetime immigrant counter. The counter
        // never decrements — dying / exiled immigrants don't refund a slot, matching the user's
        // "each settlement has a cap of 7 immigration" design (it's a one-shot population seed,
        // not a running tap).
        s.addCitizen(new Citizen(entity.getUUID(), name));
        s.recordImmigration();

        if (announce) announceImmigration(level, s, name);
        return true;
    }

    /**
     * Spawns a throwaway crowd-simulation citizen bound to {@code s} for the {@code /bannerbound
     * simulate} stress test. Mirrors {@link #spawnNewCitizen} (create → initialize → finalize →
     * add) but deliberately does NOT touch the roster ({@link Settlement#addCitizen}) or the
     * lifetime immigration counter, and flags the entity {@link CitizenEntity#markSimulated()} so
     * it is never persisted. Returns the spawned entity, or {@code null} if the town hall chunk
     * isn't loaded. The caller is responsible for discarding it when the simulation ends.
     */
    public static CitizenEntity spawnSimCitizen(ServerLevel level, Settlement s) {
        BlockPos thp = s.townHallPos();
        if (thp == null || !level.isLoaded(thp)) return null;

        BlockPos spawnPos = findSpawnPos(level, thp);
        CitizenEntity entity = BannerboundCore.CITIZEN.get().create(level);
        if (entity == null) return null;

        CitizenGender gender = level.random.nextBoolean() ? CitizenGender.MALE : CitizenGender.FEMALE;
        String name = com.bannerbound.core.api.settlement.data.CitizenNameLoader.randomName(
            level.random, s.age(), gender);
        entity.initializeCitizen(s.id(), name, gender, s.age(), s.identityFormatting());
        entity.setCompliance(100);
        entity.markSimulated();
        entity.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
            level.random.nextFloat() * 360.0f, 0.0f);
        entity.finalizeSpawn(level, level.getCurrentDifficultyAt(spawnPos),
            MobSpawnType.MOB_SUMMONED, null);
        if (!level.addFreshEntity(entity)) return null;
        return entity;
    }

    /** Fires a one-shot stage-up celebration if the settlement has grown into a higher stage.
     *  Returns true if a stage-up was announced (so the caller marks data dirty). */
    private static boolean checkStageUp(MinecraftServer server, ServerLevel level, Settlement s) {
        SettlementStage cur = s.stage();
        if (cur.ordinal() <= s.lastAnnouncedStage().ordinal()) return false;
        s.setLastAnnouncedStage(cur);
        SettlementManager.broadcastToSettlement(server, s,
            Component.translatable("bannerbound.stage." + cur.key() + ".reached", s.name())
                .withStyle(ChatFormatting.GOLD));
        spawnCelebrationFireworks(level, s);
        return true;
    }

    /** A small volley of colourful fireworks above the town hall to mark a stage-up. */
    private static void spawnCelebrationFireworks(ServerLevel level, Settlement s) {
        BlockPos th = s.townHallPos();
        if (th == null || !level.isLoaded(th)) return;
        int[] palette = { 0xE74C3C, 0xF1C40F, 0x2ECC71, 0x3498DB, 0x9B59B6, 0xE67E22 };
        for (int i = 0; i < 6; i++) {
            double fx = th.getX() + 0.5 + (level.random.nextDouble() - 0.5) * 8.0;
            double fz = th.getZ() + 0.5 + (level.random.nextDouble() - 0.5) * 8.0;
            double fy = th.getY() + 6.0;   // launch above the structure, not inside it
            net.minecraft.world.item.ItemStack rocket =
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FIREWORK_ROCKET);
            int c1 = palette[level.random.nextInt(palette.length)];
            int c2 = palette[level.random.nextInt(palette.length)];
            net.minecraft.world.item.component.FireworkExplosion explosion =
                new net.minecraft.world.item.component.FireworkExplosion(
                    net.minecraft.world.item.component.FireworkExplosion.Shape.LARGE_BALL,
                    it.unimi.dsi.fastutil.ints.IntList.of(c1, c2),
                    it.unimi.dsi.fastutil.ints.IntList.of(),
                    true, true);
            rocket.set(net.minecraft.core.component.DataComponents.FIREWORKS,
                new net.minecraft.world.item.component.Fireworks(1, java.util.List.of(explosion)));
            level.addFreshEntity(new net.minecraft.world.entity.projectile.FireworkRocketEntity(
                level, fx, fy, fz, rocket));
        }
    }

    private static void announceImmigration(Level level, Settlement s, String name) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        MinecraftServer server = serverLevel.getServer();
        if (server == null) return;
        Component msg = Component.translatable("bannerbound.immigration.arrived", name, s.name())
            .withStyle(ChatFormatting.GREEN);
        for (UUID memberId : s.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m != null) {
                m.sendSystemMessage(msg);
            }
        }
    }

    private static BlockPos findSpawnPos(ServerLevel level, BlockPos townHall) {
        RandomSource rng = level.random;
        for (int attempt = 0; attempt < 12; attempt++) {
            int dx = rng.nextInt(7) - 3;
            int dz = rng.nextInt(7) - 3;
            if (dx == 0 && dz == 0) continue;
            BlockPos candidate = townHall.offset(dx, 1, dz);
            BlockPos ground = findGround(level, candidate);
            if (ground != null) return ground;
        }
        return townHall.above();
    }

    private static BlockPos findGround(ServerLevel level, BlockPos around) {
        BlockPos.MutableBlockPos cursor = around.mutable();
        for (int dy = 2; dy >= -3; dy--) {
            cursor.set(around.getX(), around.getY() + dy, around.getZ());
            if (!level.getBlockState(cursor).isAir()) continue;
            BlockPos below = cursor.below();
            if (level.getBlockState(below).isSolid()) {
                return cursor.immutable();
            }
        }
        return null;
    }

    public static PopulationStatePayload buildPayload(ServerLevel level, Settlement s) {
        // Effective per-second rates — same numbers the accumulator drains by + the culture
        // tree consumes. Single source of truth for both food (Settlement.effectiveFood...)
        // and culture (Settlement.effectiveCulture...).
        return new PopulationStatePayload(
            s.id().toString(),
            s.population(),
            s.populationMaximum(),
            s.effectiveFoodPerSecond(),
            s.effectiveCulturePerSecond(level),
            s.foodStored(),
            s.cultureStored(),
            s.storedFoodValue(),
            s.storedFoodPerSecond(),
            s.nextFoodCost(),
            s.nextCultureCost(),
            s.foodCap(),
            s.cultureCap(),
            s.governmentType().ordinal(),
            new java.util.ArrayList<>(s.members()),
            s.foodConsumptionPerSecond(),
            s.foodProductionRates(),
            // Territory-appeal share of the culture rate — same sum folded into
            // effectiveCulturePerSecond above, broken out for the Town Hall culture tooltip.
            level == null ? 0.0 : ChunkBeautyManager.cultureBonus(level, s)
        );
    }

    public static void broadcastState(MinecraftServer server, Settlement s) {
        if (server == null || s == null) return;
        PopulationStatePayload payload = buildPayload(server.overworld(), s);
        for (UUID memberId : s.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m != null) {
                PacketDistributor.sendToPlayer(m, payload);
            }
        }
    }

    /** Research flag (Mathematics) that unlocks the Town Hall Statistics tab. */
    public static final String STATISTICS_FLAG = "bannerbound.unlock.statistics";
    /** Cap the roster snapshot so the payload stays bounded on a huge settlement (Phase 1). */
    private static final int WORKFORCE_ROSTER_CAP = 80;

    /** Once-a-second roster snapshot for the Statistics tab — only built/sent for settlements that have
     *  researched Mathematics (no point computing/sending it otherwise). Each entry is a citizen's name,
     *  job, and derived {@link com.bannerbound.core.entity.CitizenWorkStatus} (ordinal -1 = unloaded). */
    public static void broadcastWorkforceStatsIfEnabled(MinecraftServer server, Settlement s) {
        if (server == null || s == null) return;
        if (!com.bannerbound.core.api.research.ResearchManager.hasFlag(s, STATISTICS_FLAG)) return;
        boolean anarchy = s.governmentType() == Settlement.Government.NONE;
        ServerLevel overworld = server.overworld();
        java.util.List<com.bannerbound.core.network.WorkforceStatsPayload.Entry> entries = new java.util.ArrayList<>();
        for (Citizen c : s.citizens()) {
            if (entries.size() >= WORKFORCE_ROSTER_CAP) break;
            if (overworld.getEntity(c.entityId()) instanceof CitizenEntity ce) {
                String job = ce.getJobType() == null ? "" : ce.getJobType();
                int status = com.bannerbound.core.entity.CitizenWorkStatus.derive(ce, s, anarchy).ordinal();
                entries.add(new com.bannerbound.core.network.WorkforceStatsPayload.Entry(
                    c.name(), job, status, ce.getId()));
            } else {
                entries.add(new com.bannerbound.core.network.WorkforceStatsPayload.Entry(
                    c.name(), "", -1, -1));
            }
        }
        com.bannerbound.core.network.WorkforceStatsPayload payload =
            new com.bannerbound.core.network.WorkforceStatsPayload(s.id().toString(), entries);
        for (UUID memberId : s.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m != null) {
                PacketDistributor.sendToPlayer(m, payload);
            }
        }
    }

    /** Once-a-second per-workshop stats for the Statistics tab (Phase 2) — staffing, output rate, and
     *  pending backlog. Ticks each workshop's rate EMA and broadcasts, only for statistics-enabled
     *  settlements. */
    public static void broadcastWorkshopStatsIfEnabled(MinecraftServer server, Settlement s) {
        if (server == null || s == null) return;
        if (!com.bannerbound.core.api.research.ResearchManager.hasFlag(s, STATISTICS_FLAG)) return;
        java.util.List<com.bannerbound.core.network.WorkshopStatsPayload.Entry> entries = new java.util.ArrayList<>();
        for (Workshop w : s.workshops().values()) {
            if (entries.size() >= WORKFORCE_ROSTER_CAP) break;
            w.tickStats();
            entries.add(new com.bannerbound.core.network.WorkshopStatsPayload.Entry(
                w.customName(), w.derivedTypeId(), w.workers().size(), w.capacity(),
                w.status().ordinal(), w.outputRatePerSecond(), w.pendingOrders()));
        }
        com.bannerbound.core.network.WorkshopStatsPayload payload =
            new com.bannerbound.core.network.WorkshopStatsPayload(s.id().toString(), entries);
        for (UUID memberId : s.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m != null) {
                PacketDistributor.sendToPlayer(m, payload);
            }
        }
    }

    /**
     * Computes the current food-warning bucket from the settlement food reserve and, only when it
     * changed, pushes a {@link com.bannerbound.core.network.SettlementFoodWarningPayload} to every
     * member. STARVING (empty bar with active consumption) outranks LOW (under a day of reserve and
     * trending down); a settlement with no consumption yet (pre-government / anarchy) never warns.
     */
    private static void broadcastFoodWarningIfChanged(MinecraftServer server, Settlement s) {
        int level;
        double consumption = s.foodConsumptionPerSecond();
        if (consumption <= 0.0) {
            level = com.bannerbound.core.network.SettlementFoodWarningPayload.LEVEL_OK;
        } else if (s.isStarving()) {
            level = com.bannerbound.core.network.SettlementFoodWarningPayload.LEVEL_STARVING;
        } else {
            // Warn "low" when there's under a day of reserve left AND it's actually trending down — a
            // settlement still filling its stockpile shouldn't get a low-food banner.
            boolean low = s.reserveDays() < 1.0 && s.effectiveFoodPerSecond() < 0.0;
            level = low
                ? com.bannerbound.core.network.SettlementFoodWarningPayload.LEVEL_LOW
                : com.bannerbound.core.network.SettlementFoodWarningPayload.LEVEL_OK;
        }
        if (level == s.lastFoodWarningLevel()) return;
        s.setLastFoodWarningLevel(level);
        com.bannerbound.core.network.SettlementFoodWarningPayload payload =
            new com.bannerbound.core.network.SettlementFoodWarningPayload(level);
        for (UUID memberId : s.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m != null) {
                PacketDistributor.sendToPlayer(m, payload);
            }
        }
    }

    public static void sendStateTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null) return;
        PacketDistributor.sendToPlayer(player, buildPayload(server.overworld(), s));
    }
}
