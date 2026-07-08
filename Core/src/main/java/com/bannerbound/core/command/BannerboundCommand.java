package com.bannerbound.core.command;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.research.ResearchDefinition;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.research.data.ResearchTreeLoader;
import com.bannerbound.core.api.settlement.DiplomacyManager;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.ImmigrationManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.SettlementManager;
import com.bannerbound.core.api.walls.DefaultWallDesigns;
import com.bannerbound.core.api.walls.WallData;
import com.bannerbound.core.api.walls.WallLayoutEngine;
import com.bannerbound.core.api.walls.WallPlan;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.social.SocialEvents;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * All Bannerbound admin + player commands, rooted at /bannerbound; registered from
 * onRegisterCommands off the RegisterCommandsEvent bus. Subsumes the old /settlement command (its
 * subcommands are direct children here). Every subcommand is a static execute* returning a
 * Brigadier result count; most are op-only (level 2) debug/test harnesses for the big systems
 * (barbarians, city-states, faith sky, crisis, codex/Chronicle, population + brain_report, chunk
 * typing, walls, diplomacy, simulate / trader_simulate).
 *
 * <p>Permission model: only a few subcommands are player-facing with no gate (leave, world
 * get_age, codex open, vote, the walls preview/status views). chunkclaim and join are op-gated on
 * purpose - they bypass the claim economy and the membership-consent flow respectively, so they
 * must never be reachable by ordinary players on a server (pre-playtest audit A2/A3). When adding
 * a subcommand, default to .requires(hasPermission(2)) unless it is deliberately player-facing.
 *
 * <p>Target-arg gotcha: settlement-name args come in two flavours and the wrong suggester breaks
 * name lookup. targetSuggestionsQuotable() (escapeIfRequired) pairs with StringArgumentType.string()
 * args - Brigadier reads the quoted form back as the bare name. targetSuggestionsBare() pairs with
 * greedyString args - quoting would survive verbatim into the parsed value. The literal "all"
 * broadcasts a change to every settlement. Generation rates (set_/add_*) clamp at 0, so a negative
 * add_* that would go below zero just zeroes out.
 *
 * <p>Behaviours folded in from method docs: force_max_age's "none" clears the cap (stored as the
 * last era); unresearch serves both the science and culture trees from one command; unlock walks
 * the prereq chain and completes anything missing; set_relationship targets citizens by any vanilla
 * selector (each carries a scoreboard tag equal to its bare name), bypasses the FAMILY guard and
 * writes symmetrically so both sides agree; walls layout freezes the plan (exercising the WallData
 * NBT round-trip) while cancel forgets the plan but never the placed blocks.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class BannerboundCommand {
    private BannerboundCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static SuggestionProvider<CommandSourceStack> eraSuggestions() {
        return (ctx, builder) -> {
            for (Era e : Era.values()) {
                builder.suggest(e.key());
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> forceMaxAgeSuggestions() {
        return (ctx, builder) -> {
            builder.suggest("none");
            for (Era e : Era.values()) {
                builder.suggest(e.key());
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> researchSuggestions() {
        return (ctx, builder) -> {
            for (String id : ResearchTreeLoader.getAll().keySet()) {
                builder.suggest(id);
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> crisisSuggestions() {
        return (ctx, builder) -> {
            for (String id : com.bannerbound.core.crisis.CrisisDefinitionLoader.getAll().keySet()) {
                builder.suggest(id);
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> codexEntrySuggestions(boolean includeAll) {
        return (ctx, builder) -> {
            if (includeAll) builder.suggest("all");
            for (String id : com.bannerbound.core.codex.CodexEntryLoader.getAll().keySet()) {
                builder.suggest(id);
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> completedResearchSuggestions() {
        return (ctx, builder) -> {
            ServerPlayer player = ctx.getSource().getPlayer();
            if (player == null) return builder.buildFuture();
            SettlementData data = SettlementData.get(player.getServer().overworld());
            Settlement s = data.getByPlayer(player.getUUID());
            if (s == null) return builder.buildFuture();
            for (String id : s.completedResearches()) {
                builder.suggest(id);
            }
            for (String id : s.completedCultureResearches()) {
                builder.suggest(id);
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> targetSuggestionsQuotable() {
        return (ctx, builder) -> {
            builder.suggest("all");
            SettlementData data = SettlementData.get(ctx.getSource().getServer().overworld());
            for (Settlement s : data.all()) {
                builder.suggest(StringArgumentType.escapeIfRequired(s.name()));
                if (!s.factionName().equalsIgnoreCase(s.name())) {
                    builder.suggest(StringArgumentType.escapeIfRequired(s.factionName()));
                }
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> targetSuggestionsBare() {
        return (ctx, builder) -> {
            builder.suggest("all");
            SettlementData data = SettlementData.get(ctx.getSource().getServer().overworld());
            for (Settlement s : data.all()) {
                builder.suggest(s.name());
                if (!s.factionName().equalsIgnoreCase(s.name())) {
                    builder.suggest(s.factionName());
                }
            }
            return builder.buildFuture();
        };
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bannerbound")
            .then(Commands.literal("reset_world_age")
                .requires(s -> s.hasPermission(2))
                .executes(BannerboundCommand::executeResetWorldAge))
            .then(Commands.literal("gui")
                .then(Commands.literal("ancient")
                    .executes(BannerboundCommand::executeGuiAncient)))
            .then(Commands.literal("barbarian_known_tech")
                .requires(s -> s.hasPermission(2))
                .executes(BannerboundCommand::executeBarbarianKnownTech))
            .then(Commands.literal("list_barbarians")
                .requires(s -> s.hasPermission(2))
                .executes(BannerboundCommand::executeListBarbarians))
            .then(Commands.literal("spawn_barbarian")
                .requires(s -> s.hasPermission(2))
                .executes(ctx -> executeSpawnBarbarian(ctx, null))
                .then(Commands.argument("type", StringArgumentType.word())
                    .suggests((c, b) -> {
                        for (com.bannerbound.core.barbarian.CampType t
                                : com.bannerbound.core.barbarian.CampType.values()) {
                            b.suggest(t.name().toLowerCase(java.util.Locale.ROOT));
                        }
                        return b.buildFuture();
                    })
                    .executes(ctx -> executeSpawnBarbarian(ctx, StringArgumentType.getString(ctx, "type")))))
            .then(Commands.literal("clear_barbarians")
                .requires(s -> s.hasPermission(2))
                .executes(BannerboundCommand::executeClearBarbarians))
            .then(Commands.literal("nearest_barbarian_raid")
                .requires(s -> s.hasPermission(2))
                .executes(BannerboundCommand::executeNearestBarbarianRaid))
            .then(Commands.literal("nearby_barbarian_messenger")
                .requires(s -> s.hasPermission(2))
                .executes(BannerboundCommand::executeNearbyBarbarianMessenger))
            .then(Commands.literal("list_citystates")
                .requires(s -> s.hasPermission(2))
                .executes(BannerboundCommand::executeListCityStates))
            .then(Commands.literal("detect_citystate")
                .requires(s -> s.hasPermission(2))
                .executes(BannerboundCommand::executeDetectCityState))
            .then(Commands.literal("clear_citystates")
                .requires(s -> s.hasPermission(2))
                .executes(BannerboundCommand::executeClearCityStates))
            .then(Commands.literal("citystate_war")
                .requires(s -> s.hasPermission(2))
                .executes(BannerboundCommand::executeCityStateWar))
            .then(Commands.literal("sky")
                .requires(s -> s.hasPermission(2))
                .executes(BannerboundCommand::executeSkyInfo)
                .then(Commands.literal("reroll")
                    .executes(BannerboundCommand::executeSkyReroll)))
            .then(Commands.literal("reset_religion")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("settlement", StringArgumentType.string())
                    .suggests(targetSuggestionsQuotable())
                    .executes(BannerboundCommand::executeResetReligion)))
            .then(Commands.literal("ai_profiler")
                .requires(s -> s.hasPermission(2))
                .executes(BannerboundCommand::executeToggleProfiler))
            .then(Commands.literal("crisis")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("start")
                    .then(Commands.argument("crisis", StringArgumentType.greedyString())
                        .suggests(crisisSuggestions())
                        .executes(BannerboundCommand::executeCrisisStart)))
                .then(Commands.literal("clear")
                    .executes(BannerboundCommand::executeCrisisClear)))
            .then(Commands.literal("codex")
                .executes(BannerboundCommand::executeCodexOpen)
                .then(Commands.literal("unlock")
                    .requires(s -> s.hasPermission(2))
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("entry", StringArgumentType.greedyString())
                            .suggests(codexEntrySuggestions(true))
                            .executes(BannerboundCommand::executeCodexUnlock))))
                .then(Commands.literal("lock")
                    .requires(s -> s.hasPermission(2))
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("entry", StringArgumentType.greedyString())
                            .suggests(codexEntrySuggestions(true))
                            .executes(BannerboundCommand::executeCodexLock))))
                .then(Commands.literal("reset")
                    .requires(s -> s.hasPermission(2))
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(BannerboundCommand::executeCodexReset))))
            .then(Commands.literal("popup")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("show")
                    .then(Commands.argument("popup", StringArgumentType.greedyString())
                        .suggests(popupSuggestions())
                        .executes(BannerboundCommand::executePopupShow)))
                .then(Commands.literal("reset")
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(BannerboundCommand::executePopupReset))))
            .then(Commands.literal("chunktype")
                .requires(s -> s.hasPermission(2))
                .executes(BannerboundCommand::executeChunkType)
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 16))
                    .executes(BannerboundCommand::executeChunkTypeRadius)))
            .then(Commands.literal("chunkclaim")
                .requires(s -> s.hasPermission(2))
                .executes(BannerboundCommand::executeChunkClaim))
            .then(Commands.literal("leave")
                .executes(BannerboundCommand::executeLeave))
            // No permission gate: clickable [Yes]/[No] must reach players; ChatVoteManager checks membership.
            .then(Commands.literal("vote")
                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                    .then(Commands.literal("yes")
                        .executes(ctx -> executeChatVote(ctx, true)))
                    .then(Commands.literal("no")
                        .executes(ctx -> executeChatVote(ctx, false)))))
            .then(Commands.literal("join")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                    .suggests((ctx, builder) -> {
                        SettlementData data = SettlementData.get(ctx.getSource().getServer().overworld());
                        for (Settlement s : data.all()) {
                            builder.suggest(s.name());
                            if (!s.factionName().equalsIgnoreCase(s.name())) {
                                builder.suggest(s.factionName());
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(BannerboundCommand::executeJoin)))
            .then(Commands.literal("set_age")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("era", StringArgumentType.word())
                    .suggests(eraSuggestions())
                    .executes(BannerboundCommand::executeSetAge)))
            .then(Commands.literal("world")
                .then(Commands.literal("set_age")
                    .requires(s -> s.hasPermission(2))
                    .then(Commands.argument("era", StringArgumentType.word())
                        .suggests(eraSuggestions())
                        .executes(BannerboundCommand::executeWorldSetAge)))
                .then(Commands.literal("get_age")
                    .executes(BannerboundCommand::executeWorldGetAge)))
            .then(Commands.literal("force_max_age")
                .requires(s -> s.hasPermission(2))
                .executes(BannerboundCommand::executeForceMaxAgeQuery)
                .then(Commands.argument("era", StringArgumentType.word())
                    .suggests(forceMaxAgeSuggestions())
                    .executes(BannerboundCommand::executeForceMaxAge)))
            .then(Commands.literal("unlock")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("research", StringArgumentType.greedyString())
                    .suggests(researchSuggestions())
                    .executes(BannerboundCommand::executeUnlock)))
            .then(Commands.literal("unresearch")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("research", StringArgumentType.greedyString())
                    .suggests(completedResearchSuggestions())
                    .executes(BannerboundCommand::executeUnresearch)))
            .then(Commands.literal("clear_rod_selection")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("target", StringArgumentType.greedyString())
                    .suggests(targetSuggestionsBare())
                    .executes(BannerboundCommand::executeClearRodSelection)))
            .then(Commands.literal("simulate")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("population", IntegerArgumentType.integer(1, 10000))
                    .then(Commands.argument("settlement", StringArgumentType.string())
                        .suggests(targetSuggestionsQuotable())
                        .then(Commands.argument("duration", IntegerArgumentType.integer(1, 3600))
                            .executes(BannerboundCommand::executeSimulate)))))
            .then(Commands.literal("simulate_stop")
                .requires(s -> s.hasPermission(2))
                .executes(BannerboundCommand::executeSimulateStop))
            .then(Commands.literal("trader_simulate")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("start", BlockPosArgument.blockPos())
                    .then(Commands.argument("end", BlockPosArgument.blockPos())
                        .executes(ctx -> executeTraderSimulate(ctx, true))
                        .then(Commands.argument("sailing", BoolArgumentType.bool())
                            .executes(ctx -> executeTraderSimulate(ctx, BoolArgumentType.getBool(ctx, "sailing")))))))
            .then(Commands.literal("trader_simulate_stop")
                .requires(s -> s.hasPermission(2))
                .executes(BannerboundCommand::executeTraderSimulateStop))
            .then(Commands.literal("walls")
                .then(Commands.literal("preview")
                    .executes(BannerboundCommand::executeWallsPreview))
                .then(Commands.literal("refine")
                    .executes(BannerboundCommand::executeWallsRefine))
                .then(Commands.literal("layout")
                    .executes(BannerboundCommand::executeWallsLayout))
                .then(Commands.literal("construct")
                    .executes(BannerboundCommand::executeWallsConstruct))
                .then(Commands.literal("cancel")
                    .executes(BannerboundCommand::executeWallsCancel))
                .then(Commands.literal("status")
                    .executes(BannerboundCommand::executeWallsStatus)))
            .then(Commands.literal("diplomacy")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("list")
                    .executes(BannerboundCommand::executeDiplomacyList))
                .then(Commands.literal("discover")
                    .then(Commands.argument("target", StringArgumentType.greedyString())
                        .suggests(targetSuggestionsBare())
                        .executes(BannerboundCommand::executeDiplomacyDiscover)))
                .then(Commands.literal("war")
                    .then(Commands.argument("target", StringArgumentType.greedyString())
                        .suggests(targetSuggestionsBare())
                        .executes(BannerboundCommand::executeDiplomacyWar)))
                .then(Commands.literal("peace")
                    .then(Commands.argument("target", StringArgumentType.greedyString())
                        .suggests(targetSuggestionsBare())
                        .executes(BannerboundCommand::executeDiplomacyPeace))))
            .then(Commands.literal("set_relationship")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("citizenA", EntityArgument.entity())
                    .then(Commands.argument("citizenB", EntityArgument.entity())
                        .then(Commands.argument("value", IntegerArgumentType.integer(-100, 100))
                            .executes(BannerboundCommand::executeSetRelationship)))))
            .then(Commands.argument("target", StringArgumentType.string())
                .requires(s -> s.hasPermission(2))
                .suggests(targetSuggestionsQuotable())
                .then(Commands.literal("set_food_generation")
                    .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                        .executes(ctx -> executeSetGeneration(ctx, GenerationStat.FOOD))))
                .then(Commands.literal("set_culture_generation")
                    .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                        .executes(ctx -> executeSetGeneration(ctx, GenerationStat.CULTURE))))
                .then(Commands.literal("set_science_generation")
                    .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                        .executes(ctx -> executeSetGeneration(ctx, GenerationStat.SCIENCE))))
                .then(Commands.literal("add_food_generation")
                    .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                        .executes(ctx -> executeAddGeneration(ctx, GenerationStat.FOOD))))
                .then(Commands.literal("add_culture_generation")
                    .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                        .executes(ctx -> executeAddGeneration(ctx, GenerationStat.CULTURE))))
                .then(Commands.literal("add_science_generation")
                    .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                        .executes(ctx -> executeAddGeneration(ctx, GenerationStat.SCIENCE))))
                .then(Commands.literal("set_population")
                    .then(Commands.argument("value", IntegerArgumentType.integer(0, 10000))
                        .executes(ctx -> executePopulation(ctx, false))))
                .then(Commands.literal("add_population")
                    .then(Commands.argument("value", IntegerArgumentType.integer(-10000, 10000))
                        .executes(ctx -> executePopulation(ctx, true))))
                .then(Commands.literal("brain_report")
                    .executes(BannerboundCommand::executeBrainReport)))
        );
    }

    private static int executeResetWorldAge(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        SettlementData data = SettlementData.get(server.overworld());
        data.resetWorldAge();
        SettlementManager.broadcastEraState(server);
        ctx.getSource().sendSuccess(() ->
            Component.translatable("bannerbound.command.reset_world_age.success")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int executeGuiAncient(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            new com.bannerbound.core.network.OpenAncientGuiPreviewPayload());
        ctx.getSource().sendSuccess(() ->
            Component.literal("Opening Ancient-era GUI preview…").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int executeBarbarianKnownTech(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        SettlementData data = SettlementData.get(server.overworld());
        Settlement lead = com.bannerbound.core.barbarian.BarbarianTech.mostAdvanced(data);
        java.util.Set<String> known = com.bannerbound.core.barbarian.BarbarianTech.campKnownTech(data);
        Era techEra = com.bannerbound.core.barbarian.BarbarianTech.techEra(known);
        com.bannerbound.core.barbarian.BarbarianCapability cap =
            com.bannerbound.core.barbarian.BarbarianTech.capability(known);
        String frontier = com.bannerbound.core.barbarian.BarbarianTech.frontier(data);
        String leadDesc = lead == null ? "(no settlements)"
            : lead.factionName() + " [" + lead.age().key() + ", "
                + lead.completedResearches().size() + " researched]";
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Barbarian tech — most advanced: " + leadDesc
                + "\nfrontier (excluded): " + (frontier == null ? "(none)" : frontier)
                + "\nknown (" + known.size() + "): " + (known.isEmpty() ? "(nothing yet)" : known)
                + "\ntechEra: " + techEra.key()
                + "\ncapability: weapon=" + cap.weaponKey() + " tier=" + cap.weaponTier()
                + " behaviors=" + cap.behaviors() + " squadWeight=" + cap.squadWeight())
            .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int executeListBarbarians(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        com.bannerbound.core.barbarian.BarbarianData data =
            com.bannerbound.core.barbarian.BarbarianData.get(server.overworld());
        java.util.Collection<com.bannerbound.core.barbarian.BarbarianCamp> camps = data.all();
        if (camps.isEmpty()) {
            ctx.getSource().sendSuccess(() ->
                Component.literal("No barbarian camps seeded yet.").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        ChunkPos from = ctx.getSource().getEntity() != null
            ? new ChunkPos(ctx.getSource().getEntity().blockPosition()) : new ChunkPos(0, 0);
        StringBuilder sb = new StringBuilder("Barbarian camps (" + camps.size() + "):");
        for (com.bannerbound.core.barbarian.BarbarianCamp c : camps) {
            ChunkPos cc = new ChunkPos(c.center);
            int dist = Math.max(Math.abs(cc.x - from.x), Math.abs(cc.z - from.z));
            sb.append("\n").append(c.name).append(" (").append(c.type).append(") @ ")
                .append(c.center.getX()).append(",")
                .append(c.center.getZ()).append(" (").append(dist).append(" chunks) biome=")
                .append(c.biome == null ? "?" : c.biome.getPath())
                .append(" members=").append(c.memberTarget)
                .append(" cmd=").append(c.commanderCount)
                .append(c.razed ? " [RAZED]" : "");
        }
        ctx.getSource().sendSuccess(() ->
            Component.literal(sb.toString()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int executeSpawnBarbarian(CommandContext<CommandSourceStack> ctx, String typeName)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var level = player.serverLevel();
        com.bannerbound.core.barbarian.CampType type = null;
        if (typeName != null) {
            type = com.bannerbound.core.barbarian.CampType.fromName(typeName);
            if (type == null) {
                ctx.getSource().sendFailure(Component.literal("Unknown camp type: " + typeName
                    + " (nomad/tribe/raider/marauder)"));
                return 0;
            }
        }
        var facing = player.getDirection();
        int bx = player.blockPosition().getX() + facing.getStepX() * 12;
        int bz = player.blockPosition().getZ() + facing.getStepZ() * 12;
        int by = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, bx, bz);
        BlockPos center = new BlockPos(bx, by, bz);
        com.bannerbound.core.barbarian.BarbarianCamp camp =
            com.bannerbound.core.barbarian.BarbarianCampManager.forceSpawn(level, center, type);
        ctx.getSource().sendSuccess(() -> Component.literal("Spawned " + camp.type + " camp at "
            + bx + "," + bz + " — walk within 64 blocks to realize it.").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int executeNearestBarbarianRaid(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean ok = com.bannerbound.core.barbarian.BarbarianCampManager
            .triggerNearestRaid(player.serverLevel(), player);
        ctx.getSource().sendSuccess(() -> Component.literal(ok
            ? "Raid dispatched from the nearest camp."
            : "No eligible camp nearby (need a settlement + a non-razed camp).")
            .withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        return ok ? 1 : 0;
    }

    private static int executeNearbyBarbarianMessenger(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var level = player.serverLevel();
        com.bannerbound.core.api.settlement.Settlement mine =
            SettlementData.get(level).getByPlayer(player.getUUID());
        if (mine == null) {
            ctx.getSource().sendFailure(Component.literal(
                "You must belong to a settlement to receive an envoy.").withStyle(ChatFormatting.RED));
            return 0;
        }
        com.bannerbound.core.barbarian.BarbarianData data =
            com.bannerbound.core.barbarian.BarbarianData.get(level);
        com.bannerbound.core.barbarian.BarbarianCamp nearest = null;
        double best = Double.MAX_VALUE;
        for (com.bannerbound.core.barbarian.BarbarianCamp c : data.all()) {
            if (c.razed) continue;
            double d = c.center.distSqr(player.blockPosition());
            if (d < best) {
                best = d;
                nearest = c;
            }
        }
        if (nearest == null) {
            ctx.getSource().sendFailure(Component.literal(
                "No non-razed camp nearby (try /bannerbound spawn_barbarian first).")
                .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        double dist = com.bannerbound.core.barbarian.MessengerManager.forceDispatch(level, nearest, mine);
        if (dist < 0) {
            final com.bannerbound.core.barbarian.BarbarianCamp fc = nearest;
            ctx.getSource().sendFailure(Component.literal(
                fc.name + " already has an envoy en route (or your hall is unset).")
                .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        final com.bannerbound.core.barbarian.BarbarianCamp fc = nearest;
        final int blocks = (int) Math.round(dist);
        ctx.getSource().sendSuccess(() -> Component.literal(
            fc.name + " (" + fc.type + ") is sending an envoy to your hall — " + blocks
                + " blocks out. It ghost-travels until within 64 blocks of a player, then walks in. "
                + "Stay near your hall; right-click it to parley.")
            .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int executeClearBarbarians(CommandContext<CommandSourceStack> ctx) {
        var level = ctx.getSource().getServer().overworld();
        int n = com.bannerbound.core.barbarian.BarbarianCampManager.clearAllCamps(level);
        ctx.getSource().sendSuccess(() -> Component.literal("Cleared " + n
            + " barbarian camp(s) + razed memory.").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int executeListCityStates(CommandContext<CommandSourceStack> ctx) {
        var level = ctx.getSource().getServer().overworld();
        var data = com.bannerbound.core.citystate.CityStateData.get(level);
        StringBuilder sb = new StringBuilder("City-states (" + data.all().size() + ")"
            + (com.bannerbound.core.citystate.CityStateManager.enabled() ? ":"
                : " [SYSTEM DISABLED — enableCityStates=false]:"));
        for (com.bannerbound.core.citystate.CityState cs : data.all()) {
            sb.append("\n").append(cs.name).append(" @ ")
                .append(cs.center.getX()).append(",").append(cs.center.getZ())
                .append(" diff=").append(cs.difficulty)
                .append(" pop=").append(cs.believedPop)
                .append(" (homes=").append(cs.countedHomes)
                .append(cs.popDrift == 0 ? "" : (cs.popDrift > 0 ? "+" : "") + cs.popDrift).append(")")
                .append(" biome=").append(cs.biome == null ? "?" : cs.biome.getPath())
                .append(" P=").append(String.format("%.2f", cs.prosperity))
                .append(" fed=").append(String.format("%.2f", cs.fedRatio))
                .append(" tech=").append(cs.knownTech.size())
                .append(" stock=").append(com.bannerbound.core.citystate.CityStateEconomy
                    .totalStockValue(cs)).append("v [")
                .append(com.bannerbound.core.citystate.CityStateEconomy.stockSummary(cs, 3))
                .append("]")
                .append(" chunks=").append(cs.resourceChunks.isEmpty() ? "-" : cs.resourceChunks)
                .append(" seeks=").append(cs.demands.isEmpty() ? "-"
                    : String.join(",", com.bannerbound.core.citystate.CityStateEconomy.demandItems(cs)))
                .append(cs.atWar ? " [WAR]" : "")
                .append(cs.bannerStamped ? "" : " [no-banner]");
        }
        ctx.getSource().sendSuccess(() ->
            Component.literal(sb.toString()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int executeDetectCityState(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        com.bannerbound.core.citystate.CityState cs = com.bannerbound.core.citystate.CityStateManager
            .forceDetectNearest(player.serverLevel(), player);
        ctx.getSource().sendSuccess(() -> Component.literal(cs == null
            ? "No village bell within range (stand nearer a village meeting point)."
            : "City-state '" + cs.name + "' detected at " + cs.center.getX() + "," + cs.center.getZ()
                + " — walk within 64 blocks to raise its banner.")
            .withStyle(cs == null ? ChatFormatting.YELLOW : ChatFormatting.GREEN), false);
        return cs == null ? 0 : 1;
    }

    private static int executeCityStateWar(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean ok = com.bannerbound.core.citystate.CityStateWarManager
            .forceWarNearest(player.serverLevel(), player);
        ctx.getSource().sendSuccess(() -> Component.literal(ok
            ? "War declared on the nearest city-state — walk to it to fight."
            : "No eligible city-state (need a settlement + a discovered city-state).")
            .withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        return ok ? 1 : 0;
    }

    private static int executeClearCityStates(CommandContext<CommandSourceStack> ctx) {
        var level = ctx.getSource().getServer().overworld();
        int n = com.bannerbound.core.citystate.CityStateManager.clearAll(level);
        ctx.getSource().sendSuccess(() -> Component.literal("Cleared " + n
            + " city-state record(s).").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int executeResetReligion(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String target = StringArgumentType.getString(ctx, "settlement").trim();
        SettlementData data = SettlementData.get(server.overworld());
        int count = 0;
        for (Settlement s : data.all()) {
            if (!"all".equalsIgnoreCase(target) && !s.matchesName(target)) continue;
            if (com.bannerbound.core.api.faith.FaithManager.resetReligion(server, s)) count++;
        }
        final int n = count;
        ctx.getSource().sendSuccess(
            () -> Component.translatable("bannerbound.command.reset_religion.success", n)
                .withStyle(ChatFormatting.GOLD),
            true);
        return n;
    }

    private static int executeSkyInfo(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        com.bannerbound.core.api.faith.FaithData faith =
            com.bannerbound.core.api.faith.FaithData.get(server.overworld());
        com.bannerbound.core.celestial.SkyField sky =
            com.bannerbound.core.celestial.SkyField.generate(faith.skySeed(),
                new com.bannerbound.core.celestial.WorldCalendar(
                    com.bannerbound.core.Config.calendarMonthDays()).yearDays());
        int speed = server.getGameRules().getInt(
            com.bannerbound.core.chat.BannerboundGameRules.CELESTIAL_SPEED);
        double days = server.overworld().getDayTime() / 24000.0 * speed;
        StringBuilder sb = new StringBuilder();
        sb.append("Sky seed: ").append(sky.seed)
          .append(" | stars: ").append(sky.stars.size())
          .append(" | planets: ").append(sky.planets.size())
          .append(" | speed x").append(speed)
          .append(" | day ").append(String.format("%.1f", days))
          .append(" | sun lon ").append(String.format("%.1f", sky.sunGeocentricLonDeg(days))).append("°");
        for (int i = 0; i < sky.planets.size(); i++) {
            com.bannerbound.core.celestial.Planet p = sky.planets.get(i);
            com.bannerbound.core.celestial.SkyField.PlanetView v = sky.view(p, days);
            sb.append(String.format("%nP%d: a=%.2f T=%.1fd lon=%.1f° dist=%.2f%s%s",
                i, p.a(), p.periodDays(), v.eclipticLonDeg(), v.distance(),
                p.rings() ? " rings" : "", p.moonCount() > 0 ? " moons=" + p.moonCount() : ""));
        }
        String out = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    private static int executeSkyReroll(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        com.bannerbound.core.api.faith.FaithData faithData =
            com.bannerbound.core.api.faith.FaithData.get(server.overworld());
        long seed = faithData.rerollSkySeed();
        for (com.bannerbound.core.api.faith.Faith faith : faithData.all()) {
            if (faith.constellations().isEmpty()) continue;
            faith.constellations().clear();
            com.bannerbound.core.api.faith.FaithManager.recomputeFaithEffects(server, faith);
            com.bannerbound.core.api.faith.FaithManager.syncConstellations(server, faith);
        }
        com.bannerbound.core.api.faith.SkyStateSync.broadcast(server);
        ctx.getSource().sendSuccess(() ->
            Component.literal("Sky rerolled (seed " + seed + ") — wait for night.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int executeCrisisStart(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = player.getServer();
        Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        if (settlement == null) {
            player.sendSystemMessage(Component.literal("You are not in a settlement.")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        String crisisId = StringArgumentType.getString(ctx, "crisis").trim();
        if (!com.bannerbound.core.crisis.CrisisManager.debugStart(server, settlement, crisisId)) {
            player.sendSystemMessage(Component.literal("Unknown crisis '" + crisisId + "'.")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        player.sendSystemMessage(Component.literal("Started crisis '" + crisisId + "' for " + settlement.name() + ".")
            .withStyle(ChatFormatting.GOLD));
        return 1;
    }

    private static int executeCrisisClear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = player.getServer();
        Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        if (settlement == null) {
            player.sendSystemMessage(Component.literal("You are not in a settlement.")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!com.bannerbound.core.crisis.CrisisManager.debugClear(server, settlement)) {
            player.sendSystemMessage(Component.literal("No active crisis to clear.")
                .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        player.sendSystemMessage(Component.literal("Cleared active crisis for " + settlement.name() + ".")
            .withStyle(ChatFormatting.GOLD));
        return 1;
    }

    private static int executeCodexOpen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        com.bannerbound.core.codex.CodexManager.open(player, "");
        return 1;
    }

    private static SuggestionProvider<CommandSourceStack> popupSuggestions() {
        return (ctx, builder) -> {
            for (String id : com.bannerbound.core.codex.TutorialPopupLoader.getAll().keySet()) {
                builder.suggest(id);
            }
            return builder.buildFuture();
        };
    }

    private static int executePopupShow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String popupId = StringArgumentType.getString(ctx, "popup").trim();
        boolean sent = com.bannerbound.core.codex.CodexManager.showPopup(player, popupId);
        ctx.getSource().sendSuccess(() -> Component.literal(sent
                ? "Queued tutorial popup " + popupId + "."
                : "Unknown tutorial popup " + popupId + ".")
            .withStyle(sent ? ChatFormatting.GOLD : ChatFormatting.RED), false);
        return sent ? 1 : 0;
    }

    private static int executePopupReset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        boolean changed = com.bannerbound.core.codex.CodexManager.resetPopups(target);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Reset fired tutorial popups for " + target.getGameProfile().getName() + ".")
            .withStyle(changed ? ChatFormatting.GOLD : ChatFormatting.GRAY), true);
        return changed ? 1 : 0;
    }

    private static int executeCodexUnlock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String entry = StringArgumentType.getString(ctx, "entry").trim();
        int count = 0;
        if ("all".equalsIgnoreCase(entry)) {
            for (String id : com.bannerbound.core.codex.CodexEntryLoader.getAll().keySet()) {
                if (com.bannerbound.core.codex.CodexManager.unlock(target, id, false)) count++;
            }
            com.bannerbound.core.codex.CodexManager.sendTo(target);
        } else if (com.bannerbound.core.codex.CodexManager.unlock(target, entry, false)) {
            count = 1;
        }
        final int n = count;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Unlocked " + n + " Chronicle entr" + (n == 1 ? "y" : "ies") + " for "
                    + target.getGameProfile().getName() + ".")
            .withStyle(ChatFormatting.GOLD), true);
        return count;
    }

    private static int executeCodexLock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String entry = StringArgumentType.getString(ctx, "entry").trim();
        int count = 0;
        if ("all".equalsIgnoreCase(entry)) {
            for (String id : com.bannerbound.core.codex.CodexEntryLoader.getAll().keySet()) {
                if (com.bannerbound.core.codex.CodexManager.lock(target, id)) count++;
            }
            com.bannerbound.core.codex.CodexManager.sendTo(target);
        } else if (com.bannerbound.core.codex.CodexManager.lock(target, entry)) {
            count = 1;
        }
        final int n = count;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Locked " + n + " Chronicle entr" + (n == 1 ? "y" : "ies") + " for "
                    + target.getGameProfile().getName() + ".")
            .withStyle(ChatFormatting.GOLD), true);
        return count;
    }

    private static int executeCodexReset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        boolean changed = com.bannerbound.core.codex.CodexManager.reset(target);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Reset Chronicle state for " + target.getGameProfile().getName() + ".")
            .withStyle(changed ? ChatFormatting.GOLD : ChatFormatting.GRAY), true);
        return changed ? 1 : 0;
    }

    private static int executeChunkClaim(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        SettlementData data = SettlementData.get(player.getServer().overworld());

        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null) {
            player.sendSystemMessage(Component.translatable("bannerbound.chunkclaim.error.not_in_settlement")
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        ChunkPos target = new ChunkPos(player.blockPosition());
        long targetPacked = target.toLong();

        if (settlement.claimedChunks().contains(targetPacked)) {
            player.sendSystemMessage(Component.translatable("bannerbound.chunkclaim.error.already_yours")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        Settlement existingOwner = data.getByChunk(targetPacked);
        if (existingOwner != null) {
            player.sendSystemMessage(Component.translatable("bannerbound.chunkclaim.error.owned_by_other", existingOwner.name())
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        if (!isAdjacentToClaim(settlement, target)) {
            player.sendSystemMessage(Component.translatable("bannerbound.chunkclaim.error.too_far")
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        if (data.claimChunk(settlement, target)) {
            com.bannerbound.core.faction.ChunkForceLoader.force(
                player.getServer().overworld(), targetPacked);
        }
        SettlementManager.broadcastClaims(player.getServer());

        player.sendSystemMessage(Component.translatable("bannerbound.chunkclaim.success",
                target.x, target.z, settlement.name())
            .withStyle(settlement.identityFormatting()));
        return 1;
    }

    private static int executeLeave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        SettlementManager.LeaveResult result = SettlementManager.tryLeave(player);
        if (result == SettlementManager.LeaveResult.NOT_IN_SETTLEMENT) {
            player.sendSystemMessage(Component.translatable("bannerbound.leave.error.not_in_settlement")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (result == SettlementManager.LeaveResult.COOLDOWN) {
            return 0;
        }
        return 1;
    }

    private static int executeChatVote(CommandContext<CommandSourceStack> ctx, boolean yes)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        com.bannerbound.core.api.settlement.ChatVoteManager.castVote(
            ctx.getSource().getServer(), player, IntegerArgumentType.getInteger(ctx, "id"), yes);
        return 1;
    }

    private static int executeJoin(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String targetName = StringArgumentType.getString(ctx, "name").trim();
        SettlementManager.JoinResult result = SettlementManager.tryJoin(player, targetName);
        switch (result) {
            case ALREADY_IN_SETTLEMENT -> player.sendSystemMessage(
                Component.translatable("bannerbound.join.error.already_in_settlement")
                    .withStyle(ChatFormatting.RED));
            case NOT_FOUND -> player.sendSystemMessage(
                Component.translatable("bannerbound.join.error.not_found", targetName)
                    .withStyle(ChatFormatting.RED));
            case OK -> { }
        }
        return result == SettlementManager.JoinResult.OK ? 1 : 0;
    }

    private static int executeSetAge(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String eraStr = StringArgumentType.getString(ctx, "era");
        Era era = Era.fromName(eraStr);
        if (era == null) {
            player.sendSystemMessage(Component.translatable("bannerbound.settlement.set_age.error.invalid_era", eraStr)
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!SettlementManager.setSettlementAge(player, era)) {
            player.sendSystemMessage(Component.translatable("bannerbound.settlement.set_age.error.not_in_settlement")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        return 1;
    }

    private static int executeWorldSetAge(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String eraStr = StringArgumentType.getString(ctx, "era");
        Era era = Era.fromName(eraStr);
        if (era == null) {
            player.sendSystemMessage(Component.translatable("bannerbound.settlement.set_age.error.invalid_era", eraStr)
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        SettlementManager.setWorldAge(player.getServer(), era);
        ctx.getSource().sendSuccess(() -> Component.translatable("bannerbound.settlement.world.set_age.success", era.displayName())
            .withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private static int executeWorldGetAge(CommandContext<CommandSourceStack> ctx) {
        SettlementData data = SettlementData.get(ctx.getSource().getServer().overworld());
        Era era = data.getWorldAge();
        ctx.getSource().sendSuccess(
            () -> Component.translatable("bannerbound.settlement.world.get_age", era.displayName())
                .withStyle(ChatFormatting.GOLD),
            false);
        return 1;
    }

    private static int executeForceMaxAge(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String arg = StringArgumentType.getString(ctx, "era");
        Era[] eras = Era.values();
        Era target = arg.equalsIgnoreCase("none") ? eras[eras.length - 1] : Era.fromName(arg);
        if (target == null) {
            ctx.getSource().sendFailure(
                Component.translatable("bannerbound.settlement.set_age.error.invalid_era", arg)
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        server.getGameRules().getRule(com.bannerbound.core.chat.BannerboundGameRules.FORCE_MAX_AGE)
            .set(target, server);
        boolean uncapped = target.ordinal() >= eras.length - 1;
        Era shown = target;
        ctx.getSource().sendSuccess(() -> uncapped
            ? Component.translatable("bannerbound.force_max_age.cleared").withStyle(ChatFormatting.GOLD)
            : Component.translatable("bannerbound.force_max_age.set", shown.displayName())
                .withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private static int executeForceMaxAgeQuery(CommandContext<CommandSourceStack> ctx) {
        Era cap = ResearchManager.forceMaxAge(ctx.getSource().getServer());
        boolean uncapped = cap.ordinal() >= Era.values().length - 1;
        ctx.getSource().sendSuccess(() -> uncapped
            ? Component.translatable("bannerbound.force_max_age.none").withStyle(ChatFormatting.GOLD)
            : Component.translatable("bannerbound.force_max_age.current", cap.displayName())
                .withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int executeUnlock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String researchId = StringArgumentType.getString(ctx, "research").trim();
        SettlementData data = SettlementData.get(player.getServer().overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null) {
            player.sendSystemMessage(Component.translatable("bannerbound.research.error.not_in_settlement")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        ResearchDefinition def = ResearchTreeLoader.get(researchId);
        if (def == null) {
            player.sendSystemMessage(Component.translatable("bannerbound.research.error.unknown")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (s.hasCompletedResearch(researchId)) {
            player.sendSystemMessage(Component.translatable("bannerbound.research.error.already_complete")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        ResearchManager.forceCompleteWithPrereqs(player.getServer(), s, def);
        data.setDirty();
        ctx.getSource().sendSuccess(
            () -> Component.translatable("bannerbound.settlement.unlock.success", def.name(), s.name())
                .withStyle(ChatFormatting.GOLD),
            true);
        return 1;
    }

    private static int executeUnresearch(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String researchId = StringArgumentType.getString(ctx, "research").trim();
        SettlementData data = SettlementData.get(player.getServer().overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null) {
            player.sendSystemMessage(Component.translatable("bannerbound.research.error.not_in_settlement")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        ResearchDefinition def = ResearchTreeLoader.get(researchId);
        ResearchDefinition cultureDef =
            com.bannerbound.core.api.research.data.CultureTreeLoader.get(researchId);
        if (def == null && cultureDef == null) {
            player.sendSystemMessage(Component.translatable("bannerbound.research.error.unknown")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        boolean changed = false;
        ResearchDefinition affected = null;
        if (def != null && s.hasCompletedResearch(researchId)) {
            changed = ResearchManager.forceUnresearch(player.getServer(), s, def);
            affected = def;
        } else if (cultureDef != null && s.completedCultureResearches().contains(researchId)) {
            changed = com.bannerbound.core.api.research.CultureManager
                .forceUnresearch(player.getServer(), s, cultureDef);
            affected = cultureDef;
        }
        if (affected == null) {
            player.sendSystemMessage(Component.translatable("bannerbound.research.error.not_completed")
                .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        if (changed) data.setDirty();
        ResearchDefinition finalAffected = affected;
        ctx.getSource().sendSuccess(
            () -> Component.translatable("bannerbound.settlement.unresearch.success",
                finalAffected.name(), s.name())
                .withStyle(ChatFormatting.GOLD),
            true);
        return 1;
    }

    private static int executeClearRodSelection(CommandContext<CommandSourceStack> ctx) {
        String target = StringArgumentType.getString(ctx, "target").trim();
        CommandSourceStack source = ctx.getSource();
        SettlementData data = SettlementData.get(source.getServer().overworld());
        BlockSelectionRegistry registry =
            BlockSelectionRegistry.get(source.getServer().overworld());

        int removed = 0;
        if ("all".equalsIgnoreCase(target)) {
            // Snapshot rod ids first: unregister() mutates the underlying map.
            List<UUID> ids = new ArrayList<>();
            for (BlockSelection s : registry.getAll()) {
                ids.add(s.rodId());
            }
            for (UUID id : ids) {
                registry.unregister(id);
                removed++;
            }
        } else {
            Settlement match = findSettlementByName(data, target);
            if (match == null) {
                source.sendFailure(Component.literal("No settlement named '" + target + "'."));
                return 0;
            }
            List<BlockSelection> hits = new ArrayList<>(registry.getForSettlement(match.id()));
            for (BlockSelection s : hits) {
                registry.unregister(s.rodId());
                removed++;
            }
        }

        if (removed > 0) {
            com.bannerbound.core.world.SelectionBroadcaster.broadcast(source.getServer());
        }
        final int removedFinal = removed;
        source.sendSuccess(() -> Component.literal("Cleared " + removedFinal + " rod selection(s).")
            .withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private static int executeDiplomacyList(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = player.getServer();
        SettlementData data = SettlementData.get(server.overworld());
        Settlement mine = data.getByPlayer(player.getUUID());
        if (mine == null) {
            ctx.getSource().sendFailure(Component.literal("You are not in a settlement."));
            return 0;
        }
        int count = 0;
        for (SettlementData.DiplomacyRelation relation : data.diplomacyRelations()) {
            if (!relation.involves(mine.id()) || !relation.discovered) continue;
            UUID otherId = relation.other(mine.id());
            Settlement other = otherId == null ? null : data.getById(otherId);
            if (other == null) continue;
            String stance = relation.capturedFinal()
                ? "captured"
                : relation.warActive ? "war" : relation.pending() ? "pending" : "peace";
            player.sendSystemMessage(Component.literal("- " + other.factionName() + ": " + stance));
            count++;
        }
        if (count == 0) {
            player.sendSystemMessage(Component.literal("No discovered settlements."));
        }
        return count;
    }

    private static int executeDiplomacyDiscover(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return executeDiplomacyTarget(ctx, "discover");
    }

    private static int executeDiplomacyWar(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return executeDiplomacyTarget(ctx, "war");
    }

    private static int executeDiplomacyPeace(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return executeDiplomacyTarget(ctx, "peace");
    }

    private static int executeDiplomacyTarget(CommandContext<CommandSourceStack> ctx, String action)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = player.getServer();
        SettlementData data = SettlementData.get(server.overworld());
        Settlement mine = data.getByPlayer(player.getUUID());
        if (mine == null) {
            ctx.getSource().sendFailure(Component.literal("You are not in a settlement."));
            return 0;
        }
        String targetName = StringArgumentType.getString(ctx, "target").trim();
        Settlement target = findSettlementByName(data, targetName);
        if (target == null || target.id().equals(mine.id())) {
            ctx.getSource().sendFailure(Component.literal("No other settlement named '" + targetName + "'."));
            return 0;
        }
        boolean ok = switch (action) {
            case "discover" -> DiplomacyManager.discover(server, mine, target, "command");
            case "war" -> DiplomacyManager.declareWar(server, mine, target.id(), true);
            case "peace" -> DiplomacyManager.offerPeace(server, mine, target.id(), true);
            default -> false;
        };
        ctx.getSource().sendSuccess(() -> Component.literal((ok
            ? "Diplomacy " + action + " applied to "
            : "No diplomacy change for ") + target.factionName()), true);
        return ok ? 1 : 0;
    }

    private static int executeSetRelationship(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        net.minecraft.world.entity.Entity rawA = EntityArgument.getEntity(ctx, "citizenA");
        net.minecraft.world.entity.Entity rawB = EntityArgument.getEntity(ctx, "citizenB");
        if (!(rawA instanceof CitizenEntity a) || !(rawB instanceof CitizenEntity b)) {
            ctx.getSource().sendFailure(Component.literal(
                "Both arguments must be citizen entities."));
            return 0;
        }
        if (a.getUUID().equals(b.getUUID())) {
            ctx.getSource().sendFailure(Component.literal(
                "Can't set a citizen's relationship with themselves."));
            return 0;
        }
        int value = IntegerArgumentType.getInteger(ctx, "value");
        SocialEvents.setMutualScore(a, b, value);
        final String aName = a.getDisplayName().getString();
        final String bName = b.getDisplayName().getString();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Set relationship between " + aName + " and " + bName + " to " + value + ".")
            .withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private static final int SIMULATE_REAL_BUDGET = 16;

    private static int executeSimulate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        int population = IntegerArgumentType.getInteger(ctx, "population");
        String settlementName = StringArgumentType.getString(ctx, "settlement").trim();
        int duration = IntegerArgumentType.getInteger(ctx, "duration");

        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = findSettlementByName(data, settlementName);
        if (s == null) {
            source.sendFailure(Component.literal("No settlement named '" + settlementName + "'."));
            return 0;
        }
        if (s.townHallPos() == null) {
            source.sendFailure(Component.literal("Settlement '" + s.name() + "' has no town hall to anchor the crowd."));
            return 0;
        }
        int real = com.bannerbound.core.sim.SimulationManager.start(server, s, population, SIMULATE_REAL_BUDGET, duration);
        source.sendSuccess(() -> Component.literal(String.format(
                "Simulating %,d believed pop in %s for %ds (%d real near-band citizens).",
                population, s.name(), duration, real))
            .withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private static int executeSimulateStop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!com.bannerbound.core.sim.SimulationManager.isActive()) {
            source.sendFailure(Component.literal("No simulation is running."));
            return 0;
        }
        com.bannerbound.core.sim.SimulationManager.stop(source.getServer(), true);
        source.sendSuccess(() -> Component.literal("Simulation stopped.").withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private static int executeTraderSimulate(CommandContext<CommandSourceStack> ctx, boolean sailing) {
        CommandSourceStack source = ctx.getSource();
        BlockPos start = BlockPos.containing(ctx.getArgument("start", Coordinates.class).getPosition(source));
        BlockPos end = BlockPos.containing(ctx.getArgument("end", Coordinates.class).getPosition(source));
        String err = com.bannerbound.core.sim.TraderSimManager.start(
            source.getServer(), source.getPlayer(), start, end, sailing);
        if (err != null) {
            source.sendFailure(Component.literal(err));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(
                "Planning a route (%d, %d, %d) → (%d, %d, %d), sailing %s — the trader sets off once the "
                    + "route is ready. /bannerbound trader_simulate_stop to cancel.",
                start.getX(), start.getY(), start.getZ(), end.getX(), end.getY(), end.getZ(),
                sailing ? "ON" : "OFF"))
            .withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private static int executeTraderSimulateStop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!com.bannerbound.core.sim.TraderSimManager.isActive()) {
            source.sendFailure(Component.literal("No trader simulation is running."));
            return 0;
        }
        com.bannerbound.core.sim.TraderSimManager.stop(source.getServer());
        source.sendSuccess(() -> Component.literal("Trader simulation stopped.").withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private enum GenerationStat { FOOD, CULTURE, SCIENCE }

    private static int executeSetGeneration(CommandContext<CommandSourceStack> ctx, GenerationStat stat) {
        return applyGeneration(ctx, stat, /*additive=*/ false);
    }

    private static int executeAddGeneration(CommandContext<CommandSourceStack> ctx, GenerationStat stat) {
        return applyGeneration(ctx, stat, /*additive=*/ true);
    }

    private static int applyGeneration(CommandContext<CommandSourceStack> ctx, GenerationStat stat, boolean additive) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        String target = StringArgumentType.getString(ctx, "target").trim();
        double value = DoubleArgumentType.getDouble(ctx, "value");

        SettlementData data = SettlementData.get(server.overworld());
        List<Settlement> targets = new ArrayList<>();
        if ("all".equalsIgnoreCase(target)) {
            targets.addAll(data.all());
        } else {
            Settlement match = findSettlementByName(data, target);
            if (match == null) {
                source.sendFailure(Component.literal("No settlement named '" + target + "'."));
                return 0;
            }
            targets.add(match);
        }

        if (targets.isEmpty()) {
            source.sendFailure(Component.literal("No settlements to update."));
            return 0;
        }

        for (Settlement s : targets) {
            applyToSettlement(server, s, stat, value, additive);
        }
        data.setDirty();

        final String statName = stat.name().toLowerCase();
        final String scope = "all".equalsIgnoreCase(target) ? "all settlements" : target;
        final String message = additive
            ? String.format("Adjusted %s generation for %s by %+.2f.", statName, scope, value)
            : String.format("Set %s generation for %s to %.2f.", statName, scope, value);
        source.sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GOLD), true);
        return targets.size();
    }

    private static void applyToSettlement(MinecraftServer server, Settlement s,
                                          GenerationStat stat, double value, boolean additive) {
        switch (stat) {
            case FOOD -> {
                double newRate = additive ? s.foodPerSecond() + value : value;
                s.setFoodPerSecond(Math.max(0.0, newRate));
                ImmigrationManager.broadcastState(server, s);
            }
            case CULTURE -> {
                double newRate = additive ? s.culturePerSecond() + value : value;
                s.setCulturePerSecond(Math.max(0.0, newRate));
                ImmigrationManager.broadcastState(server, s);
            }
            case SCIENCE -> {
                double newRate = additive ? s.sciencePerSecond() + value : value;
                s.setSciencePerSecond(Math.max(0.0, newRate));
                ResearchManager.broadcastStateToSettlement(server, s);
            }
        }
    }

    private static int executePopulation(CommandContext<CommandSourceStack> ctx, boolean additive) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        String target = StringArgumentType.getString(ctx, "target").trim();
        int value = IntegerArgumentType.getInteger(ctx, "value");

        SettlementData data = SettlementData.get(server.overworld());
        List<Settlement> targets = new ArrayList<>();
        if ("all".equalsIgnoreCase(target)) {
            targets.addAll(data.all());
        } else {
            Settlement match = findSettlementByName(data, target);
            if (match == null) {
                source.sendFailure(Component.literal("No settlement named '" + target + "'."));
                return 0;
            }
            targets.add(match);
        }

        int delta = 0;
        for (Settlement s : targets) {
            delta += applyPopulation(server, s, value, additive);
        }
        data.setDirty();

        final int deltaFinal = delta;
        final String scope = "all".equalsIgnoreCase(target) ? "all settlements" : target;
        source.sendSuccess(() -> Component.literal(String.format(
                "Population %s for %s (%+d citizens).", additive ? "adjusted" : "set", scope, deltaFinal))
            .withStyle(ChatFormatting.GOLD), true);
        return targets.size();
    }

    private static int applyPopulation(MinecraftServer server, Settlement s, int value, boolean additive) {
        net.minecraft.server.level.ServerLevel level = server.overworld();
        int current = s.population();
        int desired = Math.max(0, additive ? current + value : value);
        if (desired > current) {
            int added = 0;
            for (int i = 0; i < desired - current; i++) {
                if (ImmigrationManager.spawnImmigrant(level, s, false)) added++;
            }
            ImmigrationManager.broadcastState(server, s);
            return added;
        }
        if (desired < current) {
            int removed = removeCitizens(level, s, current - desired);
            ImmigrationManager.broadcastState(server, s);
            return -removed;
        }
        return 0;
    }

    private static int executeToggleProfiler(CommandContext<CommandSourceStack> ctx) {
        com.bannerbound.core.sim.CitizenAiProfiler.toggle();
        boolean on = com.bannerbound.core.sim.CitizenAiProfiler.enabled();
        ctx.getSource().sendSuccess(
            () -> Component.literal("Citizen AI profiler " + (on ? "ON" : "OFF")).withStyle(ChatFormatting.GOLD),
            false);
        return 1;
    }

    private static int executeBrainReport(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        String target = StringArgumentType.getString(ctx, "target").trim();
        SettlementData data = SettlementData.get(server.overworld());
        net.minecraft.server.level.ServerLevel level = server.overworld();

        List<Settlement> targets = new ArrayList<>();
        if ("all".equalsIgnoreCase(target)) {
            targets.addAll(data.all());
        } else {
            Settlement match = findSettlementByName(data, target);
            if (match == null) {
                source.sendFailure(Component.literal("No settlement named '" + target + "'."));
                return 0;
            }
            targets.add(match);
        }

        for (Settlement s : targets) {
            int loaded = 0, ambient = 0, navving = 0;
            for (com.bannerbound.core.api.settlement.Citizen c : s.citizens()) {
                net.minecraft.world.entity.Entity e = level.getEntity(c.entityId());
                if (e instanceof CitizenEntity ce) {
                    loaded++;
                    if (ce.usesAmbientBrain()) ambient++;
                    if (ce.getNavigation().isInProgress()) navving++;
                }
            }
            final int loadedF = loaded, ambientF = ambient, navvingF = navving;
            final String line = String.format(
                "%s [%s, pop %d]: loaded %d, ambient-brain %d, active A* paths %d",
                s.name(), s.stage(), s.population(), loadedF, ambientF, navvingF);
            source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.AQUA), false);
        }
        return targets.size();
    }

    private static int removeCitizens(net.minecraft.server.level.ServerLevel level, Settlement s, int count) {
        List<com.bannerbound.core.api.settlement.Citizen> roster = new ArrayList<>(s.citizens());
        int removed = 0;
        for (int i = roster.size() - 1; i >= 0 && removed < count; i--) {
            com.bannerbound.core.api.settlement.Citizen c = roster.get(i);
            net.minecraft.world.entity.Entity e = level.getEntity(c.entityId());
            if (e != null) e.discard();
            s.removeCitizen(c.entityId());
            removed++;
        }
        return removed;
    }

    private static int executeChunkType(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        net.minecraft.server.level.ServerLevel level = player.serverLevel();
        ChunkPos cp = new ChunkPos(player.blockPosition());
        com.bannerbound.core.territory.ChunkResource r =
            com.bannerbound.core.territory.ChunkResources.typeAt(level, cp);
        net.minecraft.resources.ResourceLocation biome =
            level.getBiome(player.blockPosition()).unwrapKey().map(k -> k.location()).orElse(null);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Chunk (" + cp.x + ", " + cp.z + ") [" + (biome == null ? "?" : biome.getPath()) + "]: " + r)
            .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int executeChunkTypeRadius(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        net.minecraft.server.level.ServerLevel level = player.serverLevel();
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        ChunkPos center = new ChunkPos(player.blockPosition());
        int side = 2 * radius + 1;
        byte[] ordinals = new byte[side * side];
        java.util.Map<com.bannerbound.core.territory.ChunkResource, Integer> counts =
            new java.util.EnumMap<>(com.bannerbound.core.territory.ChunkResource.class);
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                com.bannerbound.core.territory.ChunkResource r =
                    com.bannerbound.core.territory.ChunkResources.typeAt(level, new ChunkPos(center.x + dx, center.z + dz));
                ordinals[(dz + radius) * side + (dx + radius)] = (byte) r.ordinal();
                counts.merge(r, 1, Integer::sum);
            }
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            new com.bannerbound.core.network.ShowChunkTypesPayload(center.x, center.z, radius, ordinals, 300));
        StringBuilder legend = new StringBuilder("Floating chunk markers shown for ~15s. counts: ");
        for (var e : counts.entrySet()) {
            legend.append(e.getKey()).append('(').append(e.getValue()).append(") ");
        }
        final String legendLine = legend.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(legendLine).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int executeWallsLayout(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Players only."));
            return 0;
        }
        SettlementData data = SettlementData.get(source.getServer().overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null) {
            source.sendFailure(Component.literal("You are not in a settlement."));
            return 0;
        }
        net.minecraft.server.level.ServerLevel level = player.serverLevel();
        DefaultWallDesigns.WallDesignSet designs = com.bannerbound.core.api.walls.WallService.designs(level, settlement);
        WallLayoutEngine.LayoutResult result =
            com.bannerbound.core.api.walls.WallService.computeLayout(level, settlement);

        WallLayoutEngine.Stats stats = result.stats();
        source.sendSuccess(() -> Component.literal(String.format(
            "Wall layout: %d loop(s), %d corners (%d convex / %d concave), %d segments (%d truncated), %d gates, %d water gaps.",
            stats.loops(), stats.convexCorners() + stats.concaveCorners(), stats.convexCorners(),
            stats.concaveCorners(), stats.segments(), stats.truncatedSegments(), stats.gates(),
            stats.waterGaps()))
            .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(String.format(
            "Terrain: %d draped / %d stepped pieces, %d foundation blocks, %d blocks to clear, "
            + "%d obstacle blocks (structures in the footprint), %d perimeter columns.",
            stats.draped(), stats.stepped(), stats.foundationBlocks(), stats.clearBlocks(),
            stats.obstacleBlocks(), stats.perimeterColumns()))
            .withStyle(ChatFormatting.GOLD), false);

        StringBuilder requiredLine = new StringBuilder("Required blocks: ");
        java.util.Map<net.minecraft.world.item.Item, Integer> required =
            WallLayoutEngine.sortedRequired(result.plan().requiredItems(com.bannerbound.core.api.walls.WallService.resolver(level, settlement)));
        if (required.isEmpty()) {
            requiredLine.append("none");
        }
        for (java.util.Map.Entry<net.minecraft.world.item.Item, Integer> entry : required.entrySet()) {
            requiredLine.append(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(entry.getKey()).getPath())
                .append(" ×").append(entry.getValue()).append("  ");
        }
        final String requiredFinal = requiredLine.toString().trim();
        source.sendSuccess(() -> Component.literal(requiredFinal).withStyle(ChatFormatting.AQUA), false);
        for (String warning : result.warnings()) {
            final String w = warning;
            source.sendSuccess(() -> Component.literal(w).withStyle(ChatFormatting.YELLOW), false);
        }

        int shown = emitWallParticles(level, result.plan(), settlement);
        source.sendSuccess(() -> Component.literal(
            "Sketched " + shown + " blueprint positions (white wall / orange corner / green gate / "
            + "blue foundation; red columns mark water gaps). Re-run to refresh.")
            .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int executeWallsConstruct(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Players only."));
            return 0;
        }
        SettlementData data = SettlementData.get(source.getServer().overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null) {
            source.sendFailure(Component.literal("You are not in a settlement."));
            return 0;
        }
        net.minecraft.server.level.ServerLevel level = player.serverLevel();
        DefaultWallDesigns.WallDesignSet designs = com.bannerbound.core.api.walls.WallService.designs(level, settlement);
        com.bannerbound.core.api.walls.WallService.ConstructResult outcome =
            com.bannerbound.core.api.walls.WallService.construct(level, settlement);
        if (!outcome.ok()) {
            source.sendFailure(Component.literal(outcome.error()));
            return 0;
        }
        WallLayoutEngine.LayoutResult result = outcome.layout();

        StringBuilder requiredLine = new StringBuilder("Required: ");
        java.util.Map<net.minecraft.world.item.Item, Integer> required =
            WallLayoutEngine.sortedRequired(result.plan().requiredItems(com.bannerbound.core.api.walls.WallService.resolver(level, settlement)));
        for (java.util.Map.Entry<net.minecraft.world.item.Item, Integer> entry : required.entrySet()) {
            requiredLine.append(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(entry.getKey()).getPath())
                .append(" ×").append(entry.getValue()).append("  ");
        }
        final String requiredFinal = requiredLine.toString().trim();
        source.sendSuccess(() -> Component.literal(
            "Wall plan committed (" + result.plan().pieces().size()
            + " pieces). Ghost blocks now mark the wall line — build by hand, red marks wrong blocks.")
            .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(requiredFinal).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int executeWallsCancel(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Players only."));
            return 0;
        }
        SettlementData data = SettlementData.get(source.getServer().overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null) {
            source.sendFailure(Component.literal("You are not in a settlement."));
            return 0;
        }
        net.minecraft.server.level.ServerLevel level = player.serverLevel();
        final int leftoverCount = com.bannerbound.core.api.walls.WallService.cancel(level, settlement);
        if (leftoverCount < 0) {
            source.sendFailure(Component.literal("No wall plan to cancel."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(leftoverCount == 0
            ? "Wall plan cancelled — ghosts cleared."
            : "Wall plan cancelled — ghosts cleared. " + leftoverCount
                + " standing wall blocks remembered (awaiting demolition or a new plan that reuses them).")
            .withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int executeWallsStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Players only."));
            return 0;
        }
        SettlementData data = SettlementData.get(source.getServer().overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null) {
            source.sendFailure(Component.literal("You are not in a settlement."));
            return 0;
        }
        net.minecraft.server.level.ServerLevel level = player.serverLevel();
        WallData walls = WallData.get(level);
        com.bannerbound.core.api.walls.WallPlan plan = walls.plan(settlement.id());
        if (plan == null) {
            source.sendFailure(Component.literal(
                "No wall plan committed. Use /bannerbound walls construct."));
            return 0;
        }
        DefaultWallDesigns.WallDesignSet designs = com.bannerbound.core.api.walls.WallService.designs(level, settlement);
        walls.reconcile(level, settlement.id(), com.bannerbound.core.api.walls.WallService.resolver(level, settlement));
        if (!plan.obsolete().isEmpty()) {
            final int leftover = plan.obsolete().size();
            source.sendSuccess(() -> Component.literal(
                leftover + " old wall blocks awaiting demolition (not part of the current plan).")
                .withStyle(ChatFormatting.YELLOW), false);
        }
        com.bannerbound.core.api.walls.WallProgress.Progress progress =
            com.bannerbound.core.api.walls.WallProgress.scan(level, plan, com.bannerbound.core.api.walls.WallService.resolver(level, settlement));
        source.sendSuccess(() -> Component.literal(String.format(
            "Walls %d%% complete — %d/%d placed, %d missing, %d wrong-block.",
            progress.percent(), progress.matching(), progress.total(),
            progress.missing(), progress.mismatched()))
            .withStyle(ChatFormatting.GOLD), false);

        int[] taskCounts = com.bannerbound.core.world.WallTasks.counts(settlement.id());
        if (taskCounts[0] + taskCounts[1] + taskCounts[2] > 0) {
            source.sendSuccess(() -> Component.literal(String.format(
                "Builder tasks: %d open, %d waiting on materials, %d unreachable.",
                taskCounts[0], taskCounts[1], taskCounts[2]))
                .withStyle(ChatFormatting.GOLD), false);
        }

        java.util.Map<net.minecraft.world.item.Item, Integer> remaining =
            com.bannerbound.core.api.walls.WallProgress.remainingItems(level, plan, com.bannerbound.core.api.walls.WallService.resolver(level, settlement));
        if (!remaining.isEmpty()) {
            StringBuilder line = new StringBuilder("Still needed: ");
            for (java.util.Map.Entry<net.minecraft.world.item.Item, Integer> entry : remaining.entrySet()) {
                line.append(net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(entry.getKey()).getPath())
                    .append(" ×").append(entry.getValue()).append("  ");
            }
            final String lineFinal = line.toString().trim();
            source.sendSuccess(() -> Component.literal(lineFinal).withStyle(ChatFormatting.AQUA), false);
        }
        return 1;
    }

    private static int executeWallsPreview(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Players only."));
            return 0;
        }
        SettlementData data = SettlementData.get(source.getServer().overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null || !settlement.hasTownHall()) {
            source.sendFailure(Component.literal("You need a settlement with a town hall."));
            return 0;
        }
        com.bannerbound.core.network.WallNetworkHandlers.sendPreview(player, settlement);
        return 1;
    }

    private static int executeWallsRefine(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Players only."));
            return 0;
        }
        SettlementData data = SettlementData.get(source.getServer().overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null || !settlement.hasTownHall()) {
            source.sendFailure(Component.literal("You need a settlement with a town hall."));
            return 0;
        }
        com.bannerbound.core.network.WallNetworkHandlers.sendPreview(player, settlement, true);
        return 1;
    }

    private static int emitWallParticles(net.minecraft.server.level.ServerLevel level, WallPlan plan,
                                         Settlement settlement) {
        final int cap = 12000;
        final int[] count = {0};
        final org.joml.Vector3f foundationColor = new org.joml.Vector3f(0.3f, 0.5f, 1.0f);
        final org.joml.Vector3f gapColor = new org.joml.Vector3f(1.0f, 0.1f, 0.1f);
        plan.forEachInPrecedenceOrder(com.bannerbound.core.api.walls.WallService.resolver(level, settlement), (piece, design) -> {
            if (piece.waterGap()) {
                for (int dy = 0; dy < 4; dy++) {
                    level.sendParticles(
                        new net.minecraft.core.particles.DustParticleOptions(gapColor, 1.5f),
                        piece.startX() + 0.5, piece.baseY() + dy + 0.5, piece.startZ() + 0.5,
                        1, 0, 0, 0, 0);
                }
                return;
            }
            final org.joml.Vector3f kindColor = switch (piece.kind()) {
                case CORNER -> new org.joml.Vector3f(1.0f, 0.6f, 0.1f);
                case GATE -> new org.joml.Vector3f(0.2f, 1.0f, 0.3f);
                case SEGMENT -> new org.joml.Vector3f(1.0f, 1.0f, 1.0f);
            };
            piece.forEachBlock(design, (pos, state, foundation) -> {
                if (count[0]++ >= cap) return;
                level.sendParticles(
                    new net.minecraft.core.particles.DustParticleOptions(
                        foundation ? foundationColor : kindColor, 1.0f),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1, 0, 0, 0, 0);
            });
        });
        return Math.min(count[0], cap);
    }

    private static Settlement findSettlementByName(SettlementData data, String name) {
        for (Settlement s : data.all()) {
            if (s.matchesName(name)) return s;
        }
        return null;
    }

    private static boolean isAdjacentToClaim(Settlement settlement, ChunkPos target) {
        int cx = target.x;
        int cz = target.z;
        return settlement.claimedChunks().contains(new ChunkPos(cx - 1, cz).toLong())
            || settlement.claimedChunks().contains(new ChunkPos(cx + 1, cz).toLong())
            || settlement.claimedChunks().contains(new ChunkPos(cx, cz - 1).toLong())
            || settlement.claimedChunks().contains(new ChunkPos(cx, cz + 1).toLong());
    }
}
