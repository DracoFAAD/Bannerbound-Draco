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
 * Server-side heartbeat for every settlement: {@code tickAll} runs each server tick as both the
 * immigration accumulator and the per-settlement housekeeping loop (status effects, labor
 * auto-assign, governance timers, policies, and a once-per-second broadcast fan-out to members,
 * so an open {@link com.bannerbound.core.client.TownHallScreen} reads live progress without
 * request/reply chatter).
 * <p>
 * Growth model: CULTURE is the growth currency - it accumulates (rate/20 per tick) toward the
 * population-scaled cost and is spent per immigrant. FOOD is a GATE, not a cost: growth requires
 * net food >= 0, and the food bar (foodStored) is only a survival buffer banking the net trend
 * toward/away from starvation - the old per-citizen fill/spend/refill cycle is gone. The
 * per-immigrant cooldown (Config.IMMIGRATION_MIN_SECONDS_BETWEEN) is the real pacing lever since
 * the culture bar fills fast. The immigration floor is a FLOOR, not a lifetime cap: a settlement
 * whose citizens all died drops below it and grows again, and below IMMIGRATION_FOOD_GRACE_POP
 * the food gate is waived entirely - a wiped-out government-era settlement has no workers, so it
 * can never run a surplus and would otherwise be soft-locked forever. The lifetime immigrant
 * counter bumped in spawnImmigrant never decrements (one-shot population seed, not a running tap).
 * <p>
 * Dormancy ("frozen in amber"; recomputed by SettlementManager.refreshDormancy earlier in the
 * server tick): while no member is online the larder scan is skipped (which freezes stored-food
 * spoilage, since LarderHooks.process runs inside LarderService.refresh), the food/culture bar
 * commits and the immigrant spawn are skipped, and hourly policy upkeep is frozen - the
 * settlement stays exactly as the last player left it even if its chunks are force-loaded.
 * Population cannot change while dormant, so the peak mark is unaffected by the skip.
 * <p>
 * Once per second the loop refreshes the larder + food-economy stats, notes the population peak
 * (the starvation crisis demands food for the tribe's high-water mark, not for the survivors of
 * a die-off), rebalances gatherer labor (anarchy always; under a government only while
 * auto-assign is on), recomputes the chiefdom regent (no-op heartbeat covering edge cases the
 * login events miss), and pushes PopulationStatePayload plus the Mathematics-gated Statistics
 * payloads (entries capped at WORKFORCE_ROSTER_CAP to bound packet size), the change-only food
 * warning (STARVING outranks LOW; a settlement with zero consumption never warns; LOW requires
 * under a day of reserve AND a negative trend so a stockpile still filling never warns),
 * suggestion/policy/labor state, and ChatVoteManager expiry. Every tick it drains governance
 * timers: vote pull-back when the choice window closes mid-vote (pop dropped below floor), chief
 * election timeout (5 min, resolved by top vote), pending chief/government enactment once the
 * reveal animation has played, pending disband (removes the settlement, so the loop skips its
 * remaining work), the once-per-day dawn coup check and dusk pre-warning (day time >= 12000),
 * and hourly policy upkeep (gated so no-policy settlements pay nothing). The Code of Laws prompt
 * fires once per settlement ever (flag persists in NBT), and stage-ups fire a one-shot chat +
 * fireworks celebration (checked every tick so the set_population/add_population debug commands
 * trigger it too).
 * <p>
 * Spawning: spawnImmigrant places a real roster citizen near the town hall and returns false if
 * the town hall chunk is unloaded (the caller refunds the culture cost). The drawn name is
 * re-read from the entity after initializeCitizen because it is baked into the settlement
 * language there - roster and announcement must match the name tag. Compliance at spawn keys off
 * government, not population: anarchy rolls 10-40 (it gates consent to player-REQUESTED job
 * switches; anarchy citizens always work), any government spawns at 100, matching the jump
 * existing citizens take at enactment. spawnSimCitizen is the throwaway /bannerbound simulate
 * variant: same pipeline but no roster entry, no immigration counter, and markSimulated so it is
 * never persisted (the caller discards it). Open: revalidateWorkstationBuildings is inert - the
 * workstation registry is always empty since workstation blocks were removed - and should be
 * excised with the rest of that plumbing.
 */
public final class ImmigrationManager {
    private static int tickCounter = 0;
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

        // Copy first: enactPendingDisband removes settlements mid-loop -> CME on the live values view.
        for (Settlement s : new java.util.ArrayList<>(data.all())) {
            if (broadcastTick) {
                if (!s.isDormant()) {
                    com.bannerbound.core.api.settlement.food.LarderService.refresh(overworld, s);
                    s.tickFoodEconomyStats(overworld.getGameTime());
                    s.notePopulationPeak();
                }
            }
            if (s.tickStatusEffects()) {
                SettlementManager.broadcastStatusEffectsToMembers(server, s);
                anyChange = true;
            }
            if (s.tickFoodSourcePulses()) {
                anyChange = true;
            }

            if (broadcastTick
                    && (s.governmentType() == Settlement.Government.NONE || s.laborAutoAssign())) {
                com.bannerbound.core.entity.AnarchyJobDistributor.tick(overworld, s);
            }

            double effectiveFoodPerSec = s.effectiveFoodPerSecond();
            double effectiveCulturePerSec = s.effectiveCulturePerSecond(overworld);
            double newFood = Math.max(0.0,
                Math.min(s.foodCap(), s.foodStored() + effectiveFoodPerSec / 20.0));
            double newCulture = Math.max(0.0,
                Math.min(s.cultureCap(), s.cultureStored() + effectiveCulturePerSec / 20.0));
            double cultureCost = s.nextCultureCost();

            long now = overworld.getGameTime();
            long cooldownTicks = (long) (com.bannerbound.core.Config.IMMIGRATION_MIN_SECONDS_BETWEEN.get() * 20.0);
            boolean cooldownElapsed = now - s.lastImmigrationTick() >= cooldownTicks;

            boolean immigrated = false;
            boolean recovering = s.population() < IMMIGRATION_FOOD_GRACE_POP;
            boolean fed = recovering || effectiveFoodPerSec >= -1.0e-6;
            // Dormant: skip spawn AND bar commits, or a force-loaded offline tribe keeps growing.
            if (!s.isDormant()) {
                if (fed && newCulture >= cultureCost && cooldownElapsed
                    && s.population() < s.immigrationFloor()) {
                    newCulture -= cultureCost;
                    if (spawnImmigrant(overworld, s, true)) {
                        immigrated = true;
                        anyChange = true;
                        s.setLastImmigrationTick(now);
                    } else {
                        newCulture += cultureCost;
                    }
                }
                s.setFoodStored(newFood);
                s.setCultureStored(newCulture);
            }
            if (immigrated) {
                anyChange = true;
                if (!s.codeOfLawsPromptShown() && s.population() >= s.immigrationFloor()) {
                    s.setCodeOfLawsPromptShown(true);
                    SettlementManager.broadcastToSettlement(server, s,
                        Component.translatable("bannerbound.laws.enacting")
                            .withStyle(ChatFormatting.GOLD));
                }
            }
            if (checkStageUp(server, overworld, s)) {
                anyChange = true;
            }

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
            if (s.pendingChiefId() != null
                    && overworld.getGameTime() >= s.pendingChiefEnactTick()) {
                SettlementManager.enactPendingChief(server, s, data);
                anyChange = true;
            }
            if (s.pendingGovernmentType() != null
                    && overworld.getGameTime() >= s.pendingGovernmentEnactTick()) {
                SettlementManager.enactPendingGovernment(server, s, data);
                anyChange = true;
            }
            if (s.hasPendingDisband()
                    && overworld.getGameTime() >= s.pendingDisbandEnactTick()) {
                SettlementManager.enactPendingDisband(server, s, data);
                anyChange = true;
                continue;
            }
            long today = overworld.getDayTime() / 24_000L;
            if (today != s.lastCoupCheckDay()) {
                if (s.lastCoupCheckDay() != -1L) {
                    SettlementManager.dawnCoupCheck(server, s);
                    anyChange = true;
                }
                s.setLastCoupCheckDay(today);
            }
            long timeOfDay = overworld.getDayTime() % 24_000L;
            if (timeOfDay >= 12_000L && today != s.lastDuskWarnDay()) {
                SettlementManager.duskWarningCheck(server, s);
                s.setLastDuskWarnDay(today);
            }
            if (broadcastTick && s.governmentType() == Settlement.Government.CHIEFDOM) {
                SettlementManager.recomputeRegent(server, s);
            }
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
                com.bannerbound.core.codex.CodexManager.onPopulationTick(server, s);
                broadcastWorkforceStatsIfEnabled(server, s);
                broadcastWorkshopStatsIfEnabled(server, s);
                broadcastFoodWarningIfChanged(server, s);
                if (s.governmentType() == Settlement.Government.CHIEFDOM && s.hasAnySuggestions()) {
                    SettlementManager.broadcastSuggestionState(server, s);
                    SettlementManager.broadcastPolicyState(server, s);
                    SettlementManager.broadcastPaletteState(server, s);
                    SettlementManager.broadcastExtraSuggestions(server, s);
                }
                SettlementManager.broadcastLaborState(server, s);
                revalidateWorkstationBuildings(overworld, s);
            }
        }
        if (broadcastTick) {
            ChatVoteManager.tick(server);
        }
        if (anyChange) {
            data.setDirty();
        }
    }

    private static void revalidateWorkstationBuildings(ServerLevel level, Settlement s) {
        for (com.bannerbound.core.api.settlement.Workstation ws : s.workstations().values()) {
            BlockPos pos = ws.pos();
            if (!level.isLoaded(pos)) continue;
            // Validate at placement tier: ENCLOSED rules would invalidate ancient roof-only stations every tick.
            com.bannerbound.core.building.BuildingValidator.BuildingTier tier =
                com.bannerbound.core.building.WorkstationPlacement.tierFor(ws.type());
            com.bannerbound.core.building.BuildingValidator.Result r =
                com.bannerbound.core.building.BuildingValidator.validate(level, pos, tier);
            ws.setBuildingValid(r.valid());
        }
    }

    public static boolean spawnImmigrant(ServerLevel level, Settlement s, boolean announce) {
        BlockPos thp = s.townHallPos();
        if (thp == null) return false;
        if (!level.isLoaded(thp)) return false;

        BlockPos spawnPos = findSpawnPos(level, thp);
        CitizenEntity entity = BannerboundCore.CITIZEN.get().create(level);
        if (entity == null) return false;

        CitizenGender gender = level.random.nextBoolean() ? CitizenGender.MALE : CitizenGender.FEMALE;
        String name = com.bannerbound.core.api.settlement.data.CitizenNameLoader.randomName(
            level.random, s.age(), gender);
        entity.initializeCitizen(s.id(), name, gender, s.age(), s.identityFormatting());
        name = entity.getCitizenName();
        if (s.governmentType() == Settlement.Government.NONE) {
            entity.setCompliance(10 + level.random.nextInt(31));
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

        s.addCitizen(new Citizen(entity.getUUID(), name));
        s.recordImmigration();

        if (announce) announceImmigration(level, s, name);
        return true;
    }

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

    private static void spawnCelebrationFireworks(ServerLevel level, Settlement s) {
        BlockPos th = s.townHallPos();
        if (th == null || !level.isLoaded(th)) return;
        int[] palette = { 0xE74C3C, 0xF1C40F, 0x2ECC71, 0x3498DB, 0x9B59B6, 0xE67E22 };
        for (int i = 0; i < 6; i++) {
            double fx = th.getX() + 0.5 + (level.random.nextDouble() - 0.5) * 8.0;
            double fz = th.getZ() + 0.5 + (level.random.nextDouble() - 0.5) * 8.0;
            double fy = th.getY() + 6.0;
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

    public static final String STATISTICS_FLAG = "bannerbound.unlock.statistics";
    private static final int WORKFORCE_ROSTER_CAP = 80;

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
                // status -1 = citizen entity not loaded; the client renders the row as such.
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

    private static void broadcastFoodWarningIfChanged(MinecraftServer server, Settlement s) {
        int level;
        double consumption = s.foodConsumptionPerSecond();
        if (consumption <= 0.0) {
            level = com.bannerbound.core.network.SettlementFoodWarningPayload.LEVEL_OK;
        } else if (s.isStarving()) {
            level = com.bannerbound.core.network.SettlementFoodWarningPayload.LEVEL_STARVING;
        } else {
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
