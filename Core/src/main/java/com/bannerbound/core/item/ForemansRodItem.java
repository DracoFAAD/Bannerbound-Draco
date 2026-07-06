package com.bannerbound.core.item;

import com.bannerbound.core.api.farmer.AwaitingSeedRegistry;

import java.util.List;
import java.util.UUID;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.network.OpenForemansRodPickerPayload;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.world.SelectionBroadcaster;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.GrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Foreman's Rod. Player-held tool that marks rectangular block regions as jobs for a chosen
 * workstation type.
 * <p>
 * Interaction:
 * <ul>
 *   <li>Shift-right-click in air → open the workstation-type picker.</li>
 *   <li>Right-click a block → cycles between point A (first click) and point B (second click).
 *       Once B is set, the box is committed to the {@link BlockSelectionRegistry}: if it overlaps
 *       one or more existing same-type fields it JOINS them into one (the new patch grows the
 *       existing field rather than being rejected), otherwise it becomes a fresh independent entry.
 *       A and B are cleared so the next right-click starts a new selection.</li>
 *   <li>Shift-left-click a block inside any of your settlement's existing selections →
 *       removes that selection. (Handled in {@code ForemansRodLeftClick}.)</li>
 * </ul>
 * A rod can author many selections; each has its own UUID in the registry. The rod stores only
 * the workstation type and the in-progress A/B between clicks.
 */
public class ForemansRodItem extends Item {
    /** Hard volume cap on a single selection. */
    public static final int MAX_SELECTION_VOLUME = 32_768;
    /** Unit name the rod / selections use for the digger (the citizen job id is "diggers_slab"). */
    public static final String DIGGER_TYPE = "digger";
    /** Unit name the rod / selections use for the farmer (the citizen job id is "farmers_granary"). */
    public static final String FARMER_TYPE = "farmer";
    /** Unit name the rod / selections use for the herder (the citizen job id is "herders_pen"). The
     *  herder is marked with a SINGLE click inside a fenced pen (point selection), not an A→B box. */
    public static final String HERDER_TYPE = "herder";
    /** Unit name the rod / selections use for the miner (the citizen job id is "miners_claim").
     *  Marked with a SINGLE click in an ore resource chunk (point selection), like the herder —
     *  the "workplace" is the chunk's boulder, detected from the world, not drawn as a box. */
    public static final String MINER_TYPE = "miner";
    /** Unit name the rod / selections use for the forester plantation (citizen job "foresters_log").
     *  An A→B box, like the digger/farmer; bound to one forester. Research-gated (Silviculture) at
     *  bind time in {@code ServerPayloadHandler.handleBindForemanToCitizen}. */
    public static final String FORESTER_FARM_TYPE = "forester_farm";
    /** Unit name the rod / selections use for a guard post (citizen job "guards_post"). A SINGLE
     *  click on any block — a gate, the banner, a wall walk, a tower — marks it as a post
     *  (point selection); guards hold within a few blocks of it instead of walking the perimeter
     *  beat. Must equal {@code GuardWorkGoal.SELECTION_TYPE}. */
    public static final String GUARD_TYPE = com.bannerbound.core.entity.GuardWorkGoal.SELECTION_TYPE;
    /** Animals a pen can raise anywhere (the basic four). Horse is added only on a horse chunk. */
    public static final java.util.List<String> BASIC_PEN_ANIMALS = java.util.List.of(
        "minecraft:cow", "minecraft:pig", "minecraft:sheep", "minecraft:chicken");
    /** Research flag (Extensive Quarries) that lets DIGGER selections reach onto unclaimed land
     *  near the settlement — never another settlement's claims (MINER_PLAN.md phase 3). */
    public static final String FLAG_EXTENSIVE_QUARRIES = "bannerbound.unlock.extensive_quarries";
    /** How far (chunks, Chebyshev) an extensive-quarry selection may stray from the nearest own
     *  claimed chunk. Keeps commutes sane until Outposts are the real long-range answer. */
    public static final int QUARRY_REACH_CHUNKS = 4;

    public ForemansRodItem(Properties properties) {
        super(properties);
    }

    /** Ordered worker types that bind to a specific citizen (or "all of that type") via the rod. */
    private static boolean isOrderedType(String t) {
        return DIGGER_TYPE.equals(t) || FARMER_TYPE.equals(t) || HERDER_TYPE.equals(t)
            || MINER_TYPE.equals(t) || FORESTER_FARM_TYPE.equals(t) || GUARD_TYPE.equals(t);
    }

    /** A→B box-drawing worker types (the ones that select a rectangular region rather than a single
     *  point marker). Only these support the "expand existing field" union flow. Note a digger box in
     *  a material-deposit chunk is a point marker, but a plain dig order is a box — the expand path
     *  guards on finding an actual box field to grow, so a deposit digger simply finds no match. */
    private static boolean isBoxType(String t) {
        return DIGGER_TYPE.equals(t) || FARMER_TYPE.equals(t) || FORESTER_FARM_TYPE.equals(t);
    }

    // NOTE: shift-right-click on a digger citizen (→ bind this rod to that one digger) is handled in
    // CitizenEntity.mobInteract, not here — entity.interact() consumes the click before the item's
    // interactLivingEntity would ever run, so the rod logic has to live on the entity side.

    // ─── Shift-right-click in air → open picker ──────────────────────────────────────────────

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.success(stack);
        }
        if (player.isShiftKeyDown()) {
            // TODO:
            /*String wsTypeNow = stack.get(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get());
            if (isOrderedType(wsTypeNow)) {
                stack.remove(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
                stack.remove(BannerboundCore.FOREMAN_TARGET_NAME.get());
                Component wname = com.bannerbound.core.social.WorkstationNames.dynamic(
                    com.bannerbound.core.api.settlement.SettlementData.get(serverPlayer.serverLevel().getServer().overworld())
                        .getByPlayer(serverPlayer.getUUID()), wsTypeNow);
                serverPlayer.displayClientMessage(
                    Component.translatable("bannerbound.foremans_rod.all", wname)
                        .withStyle(ChatFormatting.AQUA), true);
                return InteractionResultHolder.consume(stack);
            }*/
            PacketDistributor.sendToPlayer(serverPlayer, new OpenForemansRodPickerPayload());
            return InteractionResultHolder.consume(stack);
        }

        String wsType = stack.get(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get());
        if (wsType == null || wsType.isEmpty()) {
            serverPlayer.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.no_workstation")
                    .withStyle(ChatFormatting.YELLOW), true);
        }
        return InteractionResultHolder.pass(stack);
    }

    // ─── Right-click on block → cycle A then B; commit on B ──────────────────────────────────

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = context.getItemInHand();
        BlockPos clicked = context.getClickedPos();

        if (player.isShiftKeyDown()) {
            // EXPAND an existing field: when a box-type selection is mid-draw (point A already set),
            // a SNEAK-right-click sets B and UNIONS the new box into the nearest adjacent/overlapping
            // existing field of the same type + target, instead of committing a brand-new field. Lets
            // the player grow one big field rather than tiling many small ones. Plain (non-sneak)
            // B-clicks still make a fresh field. Checked before the adopt-field gesture so a mid-draw
            // sneak click always means "expand", never "adopt".
            String wsTypeExpand = stack.get(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get());
            BlockPos pendingA = stack.get(BannerboundCore.FOREMAN_POINT_A.get());
            if (pendingA != null && isBoxType(wsTypeExpand)) {
                tryExpandSelection(serverPlayer, stack, pendingA, clicked, wsTypeExpand);
                stack.remove(BannerboundCore.FOREMAN_POINT_A.get());
                stack.remove(BannerboundCore.FOREMAN_POINT_B.get());
                return InteractionResult.CONSUME;
            }
            // SHIFT-right-click INSIDE an existing digger/farmer field → adopt that field's type + owner
            // onto the rod (a quick way to re-target the rod to match a field you can see). Shift on any
            // other block does nothing here (the air-targeted shift cases are handled by use()).
            net.minecraft.server.level.ServerLevel overworld = serverPlayer.serverLevel().getServer().overworld();
            com.bannerbound.core.api.settlement.Settlement settlement =
                com.bannerbound.core.api.settlement.SettlementData.get(overworld).getByPlayer(serverPlayer.getUUID());
            if (settlement != null) {
                for (BlockSelection sel : BlockSelectionRegistry.get(overworld)
                        .findContaining(clicked, settlement.id())) {
                    if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
                    String t = sel.workstationType();
                    if (!isOrderedType(t)) continue;
                    // Farmer field → open the edit GUI (set crop + which farmer works it). Other
                    // ordered types keep the "adopt this field's target onto the rod" shortcut.
                    if (FARMER_TYPE.equals(t)) {
                        openFieldEditScreen(serverPlayer, overworld, sel, settlement);
                        return InteractionResult.CONSUME;
                    }
                    adoptSelectionOntoRod(serverPlayer, stack, sel, settlement);
                    stack.remove(BannerboundCore.FOREMAN_POINT_A.get());
                    stack.remove(BannerboundCore.FOREMAN_POINT_B.get());
                    return InteractionResult.CONSUME;
                }
            }
            return InteractionResult.PASS;
        }

        String wsType = stack.get(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get());
        if (wsType == null || wsType.isEmpty()) {
            serverPlayer.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.no_workstation")
                    .withStyle(ChatFormatting.YELLOW), true);
            return InteractionResult.FAIL;
        }

        // Herder: a single click inside a fenced pen marks it (point selection); no A→B box.
        if (HERDER_TYPE.equals(wsType)) {
            tryCommitHerderPen(serverPlayer, stack, clicked);
            return InteractionResult.CONSUME;
        }

        // Miner: a single click in an ore resource chunk marks its boulder (point selection).
        if (MINER_TYPE.equals(wsType)) {
            tryCommitMinerMarker(serverPlayer, stack, clicked);
            return InteractionResult.CONSUME;
        }

        // Guard: a single click on any block in own claims marks a guard post (point selection).
        if (GUARD_TYPE.equals(wsType)) {
            tryCommitGuardPost(serverPlayer, stack, clicked);
            return InteractionResult.CONSUME;
        }

        // Material deposits: in digger mode, a single click on a stone/clay/sand resource chunk
        // marks the whole deposit. Ordinary A->B dig orders still work in non-deposit chunks.
        if (DIGGER_TYPE.equals(wsType)) {
            com.bannerbound.core.territory.ChunkResource material =
                com.bannerbound.core.territory.ChunkResources.typeAt(
                    serverPlayer.serverLevel(), new net.minecraft.world.level.ChunkPos(clicked));
            if (com.bannerbound.core.territory.MaterialDepositLayout.isMaterialChunk(material)) {
                tryCommitMaterialDepositMarker(serverPlayer, stack, clicked, material);
                return InteractionResult.CONSUME;
            }
        }

        BlockPos a = stack.get(BannerboundCore.FOREMAN_POINT_A.get());

        if (a == null) {
            // First click of the pair → set A.
            stack.set(BannerboundCore.FOREMAN_POINT_A.get(), clicked);
            serverPlayer.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.point_a_set",
                    clicked.getX(), clicked.getY(), clicked.getZ())
                    .withStyle(ChatFormatting.GREEN), true);
            return InteractionResult.CONSUME;
        }

        // Second click → B + commit. Either way (success/fail) we clear A/B so the next click
        // restarts the cycle at A. Other selections in the registry are NEVER touched here.
        tryCommitSelection(serverPlayer, stack, a, clicked, wsType);
        stack.remove(BannerboundCore.FOREMAN_POINT_A.get());
        stack.remove(BannerboundCore.FOREMAN_POINT_B.get());
        return InteractionResult.CONSUME;
    }

    /** Copies an existing field's worker type + owner (a specific citizen, or "all") onto the rod, so
     *  right-clicking a field re-targets the rod to match it. */
    private static void adoptSelectionOntoRod(ServerPlayer player, ItemStack stack, BlockSelection sel,
                                              com.bannerbound.core.api.settlement.Settlement settlement) {
        String t = sel.workstationType();
        stack.set(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get(), t);
        Component wname = com.bannerbound.core.social.WorkstationNames.dynamic(settlement, t);
        Component label;
        if (sel.targetsAllWorkers()) {
            stack.remove(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
            stack.remove(BannerboundCore.FOREMAN_TARGET_NAME.get());
            label = Component.translatable("bannerbound.foremans_rod.all", wname);
        } else {
            UUID owner = sel.assignedCitizenId();
            stack.set(BannerboundCore.FOREMAN_TARGET_CITIZEN.get(), owner.toString());
            String name = "Worker";
            net.minecraft.world.entity.Entity e =
                player.serverLevel().getServer().overworld().getEntity(owner);
            if (e instanceof com.bannerbound.core.entity.CitizenEntity c && c.getCustomName() != null) {
                name = c.getCustomName().getString();
            }
            stack.set(BannerboundCore.FOREMAN_TARGET_NAME.get(), name);
            label = Component.translatable("bannerbound.foremans_rod.one", wname, name);
        }
        player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.adopted", label)
            .withStyle(ChatFormatting.AQUA), true);
    }

    /** Shift-right-click on a farmer field → open the field-edit GUI on the client. Enumerates the
     *  settlement's farmers (uuid + display name) so the screen can offer a worker dropdown ("all"
     *  plus each farmer), and carries the field's current crop + assigned worker for the highlight. */
    private static void openFieldEditScreen(ServerPlayer player, ServerLevel overworld,
                                            BlockSelection sel, Settlement settlement) {
        java.util.List<UUID> farmerIds = new java.util.ArrayList<>();
        java.util.List<String> farmerNames = new java.util.ArrayList<>();
        for (com.bannerbound.core.api.settlement.Citizen c : settlement.citizens()) {
            if (overworld.getEntity(c.entityId()) instanceof com.bannerbound.core.entity.CitizenEntity ce
                    && com.bannerbound.core.entity.FarmerWorkGoal.JOB_TYPE_ID.equals(ce.getJobType())) {
                farmerIds.add(c.entityId());
                farmerNames.add(ce.getCustomName() != null
                    ? ce.getCustomName().getString() : ce.getName().getString());
            }
        }
        PacketDistributor.sendToPlayer(player, new com.bannerbound.core.network.OpenFieldEditPayload(
            sel.rodId(), com.bannerbound.core.farmer.SeedCandidates.itemIds(), sel.seedItemId(),
            farmerIds, farmerNames, sel.assignedCitizenId(),
            com.bannerbound.core.territory.CropChunks.bonusSeedIds(
                overworld, sel.minX(), sel.minZ(), sel.maxX(), sel.maxZ())));
    }

    /**
     * Commits a freshly drawn {@code a→b} box. Validates settlement + volume + territory, then:
     * <ul>
     *   <li>If the box OVERLAPS one or more existing same-type box fields, it JOINS them — the new
     *       box and every overlapped field are unioned into a single field that keeps the oldest
     *       overlapped field's identity (its id, crop and assigned worker). The others are removed.
     *       This is what players expect when they draw a new patch over an existing field: it grows,
     *       it doesn't get rejected or replaced. (Adjacency-only growth, no overlap, still goes
     *       through the explicit SNEAK "expand" gesture — see {@link #tryExpandSelection}.)</li>
     *   <li>If the box overlaps anything that ISN'T a mergeable same-type field (a different worker
     *       type, a point marker, a home, etc.), that's a genuine conflict → red "overlap" message,
     *       registry untouched.</li>
     *   <li>Otherwise it registers a fresh {@link BlockSelection} with a new UUID.</li>
     * </ul>
     * On any rejection, sends a red error message and never mutates the registry.
     */
    private static void tryCommitSelection(ServerPlayer player, ItemStack stack,
                                            BlockPos a, BlockPos b, String wsType) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        Settlement settlement = SettlementData.get(overworld).getByPlayer(player.getUUID());
        if (settlement == null) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.not_in_settlement")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }

        if (!checkVolume(player, a, b)) return;
        if (!checkTerritory(player, overworld, settlement, wsType, a, b)) return;

        UUID selectionId = UUID.randomUUID();
        // Stamp the creator (player who set point B) so the farmer pipeline can route the
        // seed-picker popup to the right person; empty seed assignment by default.
        BlockSelection candidate = BlockSelection.workstation(
            selectionId, settlement.id(), settlement.color().ordinal(),
            a, b, wsType, player.getUUID(), "");
        // For an ordered-worker rod (digger/farmer), bind the selection to the rod's current target (a
        // specific citizen, or "all" of that type when the target is cleared).
        if (DIGGER_TYPE.equals(wsType) || FARMER_TYPE.equals(wsType)
                || FORESTER_FARM_TYPE.equals(wsType)) {
            String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
            if (targetStr != null && !targetStr.isEmpty()) {
                try {
                    candidate = candidate.withAssignedCitizen(UUID.fromString(targetStr));
                } catch (IllegalArgumentException ignored) { /* malformed → leave open to all */ }
            }
        }

        if (FARMER_TYPE.equals(wsType)) {
            int amountOfFarmland = 0;

            for (BlockPos pos : BlockPos.betweenClosed(a, b)) {
                BlockState state = overworld.getBlockState(pos);
                Block block = state.getBlock();

                if (block instanceof FarmBlock || block instanceof GrassBlock) {
                    amountOfFarmland++;
                }
            }

            if (amountOfFarmland == 0) {
                player.displayClientMessage(
                    Component.translatable("bannerbound.foremans_rod.no_farmland")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
        }

        BlockSelectionRegistry registry = BlockSelectionRegistry.get(overworld);

        // Split the things this box overlaps into mergeable same-type fields vs hard conflicts.
        java.util.List<BlockSelection> mergeable = new java.util.ArrayList<>();
        boolean hardConflict = false;
        for (BlockSelection s : registry.getForSettlement(settlement.id())) {
            if (s.completed()) continue;            // finished fields are invisible / non-blocking
            if (!s.intersects(candidate)) continue;
            if (isMergeableField(s, wsType)) mergeable.add(s);
            else hardConflict = true;               // different type / point marker / home → real clash
        }
        if (hardConflict) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.overlap")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!mergeable.isEmpty()) {
            mergeIntoOverlapping(player, server, overworld, settlement, registry, candidate, mergeable, wsType);
            return;
        }

        registry.register(candidate);
        player.displayClientMessage(
            Component.translatable("bannerbound.foremans_rod.selection_committed", candidate.volume())
                .withStyle(ChatFormatting.GREEN), true);
        SelectionBroadcaster.broadcast(server);

        // Farmer selections immediately prompt the player for a seed — they have to pick (or
        // dismiss, which deletes the selection) before any work happens. Other workstation
        // types are no-op here.
        if ("farmer".equals(wsType)) {
            com.bannerbound.core.api.farmer.AwaitingSeedRegistry.queueAndMaybePush(
                server, player.getUUID(), selectionId,
                com.bannerbound.core.farmer.SeedCandidates.itemIds(),
                com.bannerbound.core.territory.CropChunks.bonusSeedIds(
                    overworld, candidate.minX(), candidate.minZ(), candidate.maxX(), candidate.maxZ()));
        }
    }

    /** A field the freshly drawn {@code wsType} box may be JOINED into on overlap: a same-type,
     *  not-yet-completed WORKSTATION box (point markers — deposit/miner 1×1×1 — are never merged;
     *  they're a different kind of job that happens to share the digger/miner type). */
    private static boolean isMergeableField(BlockSelection s, String wsType) {
        if (s.kind() != BlockSelection.Kind.WORKSTATION) return false;
        if (!wsType.equals(s.workstationType())) return false;
        if (s.sizeX() == 1 && s.sizeY() == 1 && s.sizeZ() == 1) return false; // point marker
        return true;
    }

    /**
     * Joins the freshly drawn {@code candidate} box and every field it overlaps into ONE field. The
     * oldest overlapped field (registry insertion order) is the survivor — it keeps its id, crop and
     * assigned worker, and merely grows to the union AABB; the rest are removed. The union must still
     * fit the volume cap, stay in territory, and not collide with any OTHER (non-merged) field. On any
     * rejection a red message; the registry is never mutated on failure.
     */
    private static void mergeIntoOverlapping(ServerPlayer player, MinecraftServer server,
            ServerLevel overworld, Settlement settlement, BlockSelectionRegistry registry,
            BlockSelection candidate, java.util.List<BlockSelection> mergeable, String wsType) {
        int unionMinX = candidate.minX(), unionMinY = candidate.minY(), unionMinZ = candidate.minZ();
        int unionMaxX = candidate.maxX(), unionMaxY = candidate.maxY(), unionMaxZ = candidate.maxZ();
        for (BlockSelection s : mergeable) {
            unionMinX = Math.min(unionMinX, s.minX()); unionMaxX = Math.max(unionMaxX, s.maxX());
            unionMinY = Math.min(unionMinY, s.minY()); unionMaxY = Math.max(unionMaxY, s.maxY());
            unionMinZ = Math.min(unionMinZ, s.minZ()); unionMaxZ = Math.max(unionMaxZ, s.maxZ());
        }
        BlockPos unionA = new BlockPos(unionMinX, unionMinY, unionMinZ);
        BlockPos unionB = new BlockPos(unionMaxX, unionMaxY, unionMaxZ);

        if (!checkVolume(player, unionA, unionB)) return;
        if (!checkTerritory(player, overworld, settlement, wsType, unionA, unionB)) return;

        // Oldest overlapped field survives and grows; it owns the crop + worker assignment.
        BlockSelection primary = mergeable.get(0);
        BlockSelection grown = primary.withBounds(unionA, unionB);

        // The grown union may not collide with any field OTHER than the ones we're folding in.
        java.util.Set<UUID> merged = new java.util.HashSet<>();
        for (BlockSelection s : mergeable) merged.add(s.rodId());
        if (registry.anyOverlapExcludingAll(grown, merged)) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.overlap")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }

        for (BlockSelection s : mergeable) {
            if (!s.rodId().equals(primary.rodId())) registry.unregister(s.rodId());
        }
        registry.register(grown); // same rodId key → grows the surviving field in place
        player.displayClientMessage(
            Component.translatable("bannerbound.foremans_rod.field_expanded", grown.volume())
                .withStyle(ChatFormatting.GREEN), true);
        SelectionBroadcaster.broadcast(server);
    }

    /** Volume-cap guard shared by fresh commits and unions. Sends the red "too large" message and
     *  returns false when the box exceeds {@link #MAX_SELECTION_VOLUME}. */
    private static boolean checkVolume(ServerPlayer player, BlockPos a, BlockPos b) {
        long volume = BlockSelection.volumeOf(a, b);
        if (volume > MAX_SELECTION_VOLUME) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.too_large",
                    volume, MAX_SELECTION_VOLUME).withStyle(ChatFormatting.RED), true);
            return false;
        }
        return true;
    }

    /**
     * Territory guard shared by fresh commits and unions. Every chunk the box touches must be claimed
     * by this player's settlement. Exception (Extensive Quarries research, DIGGER only): unclaimed
     * chunks within {@link #QUARRY_REACH_CHUNKS} of own territory are also valid — another
     * settlement's claims never are. Sends the matching red message and returns false on failure.
     */
    private static boolean checkTerritory(ServerPlayer player, ServerLevel overworld,
            Settlement settlement, String wsType, BlockPos a, BlockPos b) {
        boolean extensive = DIGGER_TYPE.equals(wsType)
            && com.bannerbound.core.api.research.ResearchManager.hasFlag(settlement, FLAG_EXTENSIVE_QUARRIES);
        if (extensive) {
            String failKey = quarryReachFailKey(overworld, settlement, a, b);
            if (failKey != null) {
                player.displayClientMessage(Component.translatable(failKey, QUARRY_REACH_CHUNKS)
                    .withStyle(ChatFormatting.RED), true);
                return false;
            }
        } else if (!isFullyWithinTerritory(settlement, a, b)) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.outside_territory")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        return true;
    }

    /**
     * Expand-field commit: instead of registering a fresh selection, grow an EXISTING same-type field
     * to the union of its box and the newly drawn {@code a→b} box. The target field is the nearest
     * (by min-corner distance) non-completed WORKSTATION box field of the same {@code wsType} and same
     * assigned-citizen target that either overlaps or sits adjacent to the new box. The union must
     * still fit the volume cap, stay fully in territory, and not collide with any OTHER field. On any
     * rejection a red message; the registry is never mutated on failure. If no field to expand is
     * found, falls back to a normal fresh commit so a sneak-click is never wasted.
     */
    private void tryExpandSelection(ServerPlayer player, ItemStack stack,
                                    BlockPos a, BlockPos b, String wsType) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        Settlement settlement = SettlementData.get(overworld).getByPlayer(player.getUUID());
        if (settlement == null) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.not_in_settlement")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        // The rod's current target (a bound citizen, or "all"), to match the field we grow.
        UUID rodTarget = BlockSelection.NO_CITIZEN;
        String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
        if (targetStr != null && !targetStr.isEmpty()) {
            try { rodTarget = UUID.fromString(targetStr); } catch (IllegalArgumentException ignored) { }
        }

        BlockSelectionRegistry registry = BlockSelectionRegistry.get(overworld);
        // Build the new box as a probe selection (1-block inflated for the adjacency test).
        BlockSelection probe = BlockSelection.workstation(
            UUID.randomUUID(), settlement.id(), settlement.color().ordinal(), a, b, wsType,
            player.getUUID(), "");
        int newMinX = Math.min(a.getX(), b.getX()) - 1, newMaxX = Math.max(a.getX(), b.getX()) + 1;
        int newMinY = Math.min(a.getY(), b.getY()) - 1, newMaxY = Math.max(a.getY(), b.getY()) + 1;
        int newMinZ = Math.min(a.getZ(), b.getZ()) - 1, newMaxZ = Math.max(a.getZ(), b.getZ()) + 1;

        BlockSelection best = null;
        long bestDist = Long.MAX_VALUE;
        for (BlockSelection s : registry.getForSettlement(settlement.id())) {
            if (s.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (s.completed()) continue;
            if (!wsType.equals(s.workstationType())) continue;
            if (!s.assignedCitizenId().equals(rodTarget)) continue;
            // Skip point markers (deposit/miner) — only true boxes are expandable.
            if (s.sizeX() == 1 && s.sizeY() == 1 && s.sizeZ() == 1) continue;
            // Adjacency/overlap: the existing box must touch the 1-inflated new box.
            boolean touches = s.minX() <= newMaxX && s.maxX() >= newMinX
                && s.minY() <= newMaxY && s.maxY() >= newMinY
                && s.minZ() <= newMaxZ && s.maxZ() >= newMinZ;
            if (!touches) continue;
            BlockPos sMin = new BlockPos(s.minX(), s.minY(), s.minZ());
            long d = (long) sMin.distSqr(new BlockPos(probe.minX(), probe.minY(), probe.minZ()));
            if (d < bestDist) { bestDist = d; best = s; }
        }

        if (best == null) {
            // Nothing adjacent to grow → behave like a normal commit so the click isn't wasted.
            tryCommitSelection(player, stack, a, b, wsType);
            return;
        }

        // Union AABB of the existing field and the new box.
        BlockPos unionA = new BlockPos(
            Math.min(best.minX(), Math.min(a.getX(), b.getX())),
            Math.min(best.minY(), Math.min(a.getY(), b.getY())),
            Math.min(best.minZ(), Math.min(a.getZ(), b.getZ())));
        BlockPos unionB = new BlockPos(
            Math.max(best.maxX(), Math.max(a.getX(), b.getX())),
            Math.max(best.maxY(), Math.max(a.getY(), b.getY())),
            Math.max(best.maxZ(), Math.max(a.getZ(), b.getZ())));

        long volume = BlockSelection.volumeOf(unionA, unionB);
        if (volume > MAX_SELECTION_VOLUME) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.too_large",
                    volume, MAX_SELECTION_VOLUME).withStyle(ChatFormatting.RED), true);
            return;
        }
        // Territory rule — same as a fresh commit (extensive quarries honoured for diggers).
        boolean extensive = DIGGER_TYPE.equals(wsType)
            && com.bannerbound.core.api.research.ResearchManager.hasFlag(settlement, FLAG_EXTENSIVE_QUARRIES);
        if (extensive) {
            String failKey = quarryReachFailKey(overworld, settlement, unionA, unionB);
            if (failKey != null) {
                player.displayClientMessage(Component.translatable(failKey, QUARRY_REACH_CHUNKS)
                    .withStyle(ChatFormatting.RED), true);
                return;
            }
        } else if (!isFullyWithinTerritory(settlement, unionA, unionB)) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.outside_territory")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        // The grown field may not collide with any OTHER field (its own id is excluded).
        BlockSelection grown = best.withBounds(unionA, unionB);
        if (registry.anyOverlapExcluding(grown, best.rodId())) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.overlap")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        registry.register(grown); // same rodId key → updates the existing field in place
        player.displayClientMessage(
            Component.translatable("bannerbound.foremans_rod.field_expanded", volume)
                .withStyle(ChatFormatting.GREEN), true);
        SelectionBroadcaster.broadcast(server);
    }

    /**
     * Herder pen marking: detect the fenced enclosure around {@code clicked} ({@link PenEnclosure}),
     * verify it's on a livestock chunk and in this settlement's territory, then commit a point
     * selection (a==b==clicked) of type {@code herder} bound to the rod's current target citizen. On
     * any rejection, a red message; the registry is never mutated on failure.
     */
    private static void tryCommitHerderPen(ServerPlayer player, ItemStack stack, BlockPos clicked) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel level = player.serverLevel();
        ServerLevel overworld = server.overworld();
        Settlement settlement = SettlementData.get(overworld).getByPlayer(player.getUUID());
        if (settlement == null) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.not_in_settlement")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        // Clicking INSIDE an existing pen → show its info (animal / capacity / kills), don't re-mark.
        for (BlockSelection sel : BlockSelectionRegistry.get(overworld).getForSettlement(settlement.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION || !HERDER_TYPE.equals(sel.workstationType())) {
                continue;
            }
            BlockPos marker = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            if (marker.distSqr(clicked) > 64 * 64) continue;
            com.bannerbound.core.building.PenEnclosure.Result r =
                com.bannerbound.core.building.PenEnclosure.scan(level, marker);
            if (r.valid() && (r.interior().contains(clicked) || r.interior().contains(clicked.below()))) {
                // Shift-right-click → (re)assign the pen's worker from the rod's selection (a citizen, or
                // "all" if cleared). Plain right-click → open the "keep how many adults" harvest config.
                if (player.isShiftKeyDown()) reassignPen(player, stack, overworld, sel);
                else openPenKeepScreen(player, level, sel, r);
                return;
            }
        }
        if (!isWorkableChunk(settlement, clicked)) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.outside_territory")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        // Pens can be built anywhere now (basic livestock isn't chunk-gated) — just need a valid enclosure.
        com.bannerbound.core.building.PenEnclosure.Result pen =
            com.bannerbound.core.building.PenEnclosure.scan(level, clicked);
        if (!pen.valid()) {
            player.displayClientMessage(Component.translatable(penFailKey(pen.reason()))
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        // Which animals may this pen raise? The basic four anywhere; horse only on a horse chunk.
        java.util.List<String> animals = new java.util.ArrayList<>(BASIC_PEN_ANIMALS);
        if (com.bannerbound.core.territory.ChunkResources.typeAt(level,
                new net.minecraft.world.level.ChunkPos(clicked)) == com.bannerbound.core.territory.ChunkResource.HORSES) {
            animals.add("minecraft:horse");
        }
        // Open the animal picker; the pen is committed in ServerPayloadHandler#handlePickPenAnimal.
        PacketDistributor.sendToPlayer(player,
            new com.bannerbound.core.network.OpenPenAnimalPickerPayload(clicked, animals));
    }

    /** Plain right-click on an existing pen → open the "keep how many adults" harvest config screen, carrying
     *  the pen's animal / mature count / capacity / kills / current keep threshold for display. */
    private static void openPenKeepScreen(ServerPlayer player, ServerLevel level,
            BlockSelection sel, com.bannerbound.core.building.PenEnclosure.Result r) {
        String packed = sel.seedItemId();
        String animalId = com.bannerbound.core.entity.HerderWorkGoal.penAnimalId(packed);
        int kills = com.bannerbound.core.entity.HerderWorkGoal.penKills(packed);
        int keep = com.bannerbound.core.entity.HerderWorkGoal.penKeep(packed);
        net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(animalId);
        net.minecraft.world.entity.EntityType<?> type = rl == null ? null
            : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null);
        int cap = com.bannerbound.core.building.PenEnclosure.stats(level, r)
            .capacity(com.bannerbound.core.entity.HerderWorkGoal.animalSize(type));
        int mature = level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
            r.bounds().inflate(1.0, 2.0, 1.0),
            a -> a.isAlive() && !a.isBaby() && type != null && a.getType() == type && inPenColumn(r, a)).size();
        BlockPos marker = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
        PacketDistributor.sendToPlayer(player,
            new com.bannerbound.core.network.OpenPenKeepPayload(marker, animalId, mature, cap, kills, keep));
    }

    /** Same interior-column membership the herder uses for counting its herd (1-block margin), so the keep
     *  screen's mature count matches what the herder actually sees — never a raw bounding box, which on an
     *  L-shaped pen covers ground outside the rope and over-counts wild stock as inside. */
    private static boolean inPenColumn(com.bannerbound.core.building.PenEnclosure.Result r,
            net.minecraft.world.entity.Entity a) {
        int feetY = a.blockPosition().getY();
        int cx0 = (int) Math.floor(a.getX() - 1.0), cx1 = (int) Math.floor(a.getX() + 1.0);
        int cz0 = (int) Math.floor(a.getZ() - 1.0), cz1 = (int) Math.floor(a.getZ() + 1.0);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (r.interior().contains(new BlockPos(cx, feetY, cz))
                    || r.interior().contains(new BlockPos(cx, feetY - 1, cz))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Shift-right-click on an existing pen → set its worker to the rod's current selection: a specific
     *  citizen ({@code FOREMAN_TARGET_CITIZEN}), or all herders if the rod's target is cleared. */
    private static void reassignPen(ServerPlayer player, ItemStack stack, ServerLevel overworld, BlockSelection sel) {
        String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
        java.util.UUID who = BlockSelection.NO_CITIZEN;
        net.minecraft.network.chat.MutableComponent msg =
            Component.translatable("bannerbound.foremans_rod.pen_assigned_all");
        if (targetStr != null && !targetStr.isEmpty()) {
            try {
                who = java.util.UUID.fromString(targetStr);
                String name = stack.get(BannerboundCore.FOREMAN_TARGET_NAME.get());
                msg = Component.translatable("bannerbound.foremans_rod.pen_assigned",
                    name != null && !name.isEmpty() ? name : "?");
            } catch (IllegalArgumentException ignored) { who = BlockSelection.NO_CITIZEN; }
        }
        BlockSelectionRegistry.get(overworld).register(sel.withAssignedCitizen(who));
        com.bannerbound.core.world.SelectionBroadcaster.broadcast(player.getServer());
        player.displayClientMessage(msg.withStyle(ChatFormatting.GREEN), true);
    }

    /**
     * Generic material deposit marking: a single click in a STONE/CLAY/SAND resource chunk commits
     * a point marker of type {@code digger}. The packed seed carries resource + deposit base height;
     * {@link com.bannerbound.core.entity.DiggerWorkGoal} sees that seed and switches from finite
     * A->B excavation to the infinite non-destructive deposit cycle.
     */
    private static void tryCommitMaterialDepositMarker(ServerPlayer player, ItemStack stack,
                                                       BlockPos clicked,
                                                       com.bannerbound.core.territory.ChunkResource type) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel level = player.serverLevel();
        ServerLevel overworld = server.overworld();
        Settlement settlement = SettlementData.get(overworld).getByPlayer(player.getUUID());
        if (settlement == null) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.not_in_settlement")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(clicked);
        if (settlement.workingClaims().contains(cp.toLong())) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.deposit_outpost_managed")
                .withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        for (BlockSelection sel : BlockSelectionRegistry.get(overworld).getForSettlement(settlement.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION || !DIGGER_TYPE.equals(sel.workstationType())) {
                continue;
            }
            if (!com.bannerbound.core.territory.MaterialDepositLayout.isMaterialPacked(sel.seedItemId())) {
                continue;
            }
            if (!cp.equals(new net.minecraft.world.level.ChunkPos(
                    new BlockPos(sel.minX(), sel.minY(), sel.minZ())))) {
                continue;
            }
            if (player.isShiftKeyDown()) {
                reassignMaterialDeposit(player, stack, overworld, sel);
            } else {
                player.displayClientMessage(
                    Component.translatable("bannerbound.foremans_rod.deposit_already_marked",
                        resourceLabel(com.bannerbound.core.territory.MaterialDepositLayout
                            .materialResource(sel.seedItemId())))
                        .withStyle(ChatFormatting.AQUA), true);
            }
            return;
        }
        if (!isWorkableChunk(settlement, clicked)) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.outside_territory")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        if (com.bannerbound.core.territory.MaterialDepositLayout.isStoneBoulder(type)
                && !com.bannerbound.core.api.research.ResearchManager.hasFlag(
                    settlement, com.bannerbound.core.social.WorkstationNames.FLAG_QUARRY)) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.not_researched")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        int baseY = com.bannerbound.core.territory.MaterialDepositLayout.locateBaseY(level, cp, type)
            .orElseGet(() -> com.bannerbound.core.territory.MaterialDepositLayout.dress(level, cp));
        if (baseY == Integer.MIN_VALUE) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.deposit_unworkable")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        BlockPos anchor = new BlockPos(cp.getMinBlockX() + 8, baseY, cp.getMinBlockZ() + 8);
        UUID selectionId = UUID.randomUUID();
        BlockSelection candidate = BlockSelection.workstation(
            selectionId, settlement.id(), settlement.color().ordinal(),
            anchor, anchor, DIGGER_TYPE, player.getUUID(),
            com.bannerbound.core.territory.MaterialDepositLayout.packDeposit(type, baseY));
        String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
        if (targetStr != null && !targetStr.isEmpty()) {
            try {
                candidate = candidate.withAssignedCitizen(UUID.fromString(targetStr));
            } catch (IllegalArgumentException ignored) { /* malformed -> leave open to all diggers */ }
        }
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(overworld);
        if (registry.anyOverlapExcluding(candidate, selectionId)) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.overlap")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        registry.register(candidate);
        SelectionBroadcaster.broadcast(server);
        player.displayClientMessage(
            Component.translatable("bannerbound.foremans_rod.deposit_marked", resourceLabel(type))
                .withStyle(ChatFormatting.GREEN), true);
    }

    private static void reassignMaterialDeposit(ServerPlayer player, ItemStack stack,
                                                ServerLevel overworld, BlockSelection sel) {
        String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
        java.util.UUID who = BlockSelection.NO_CITIZEN;
        net.minecraft.network.chat.MutableComponent msg =
            Component.translatable("bannerbound.foremans_rod.deposit_assigned_all");
        if (targetStr != null && !targetStr.isEmpty()) {
            try {
                who = java.util.UUID.fromString(targetStr);
                String name = stack.get(BannerboundCore.FOREMAN_TARGET_NAME.get());
                msg = Component.translatable("bannerbound.foremans_rod.deposit_assigned",
                    name != null && !name.isEmpty() ? name : "?");
            } catch (IllegalArgumentException ignored) { who = BlockSelection.NO_CITIZEN; }
        }
        BlockSelectionRegistry.get(overworld).register(sel.withAssignedCitizen(who));
        com.bannerbound.core.world.SelectionBroadcaster.broadcast(player.getServer());
        player.displayClientMessage(msg.withStyle(ChatFormatting.GREEN), true);
    }

    /**
     * Miner marking: a single click in an ore resource chunk ({@link ChunkResources}) commits a
     * point selection of type {@code miner} whose packed {@code seedItemId} carries the chunk's
     * resource + the boulder's base height. The boulder is located by scoring the deterministic
     * {@link com.bannerbound.core.territory.BoulderLayout} against the world; a chunk typed as ore
     * but generated before the populator feature gets its boulder DRESSED here (the one terrain
     * edit, once, at the player's request, on the player's own claim). On any rejection a red
     * message; the registry is never mutated on failure.
     */
    private static void tryCommitMinerMarker(ServerPlayer player, ItemStack stack, BlockPos clicked) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel level = player.serverLevel();
        ServerLevel overworld = server.overworld();
        Settlement settlement = SettlementData.get(overworld).getByPlayer(player.getUUID());
        if (settlement == null) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.not_in_settlement")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(clicked);
        // OUTPOST chunks are banner-managed: the Outpost Banner owns the deposit marker and the
        // player appoints the miner in ITS screen — the rod stays out entirely.
        if (settlement.workingClaims().contains(cp.toLong())) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.mine_outpost_managed")
                .withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        // Clicking in an already-marked chunk: shift → re-assign its worker from the rod's target,
        // plain → status message. Mirrors the pen behaviour; never re-marks.
        for (BlockSelection sel : BlockSelectionRegistry.get(overworld).getForSettlement(settlement.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION || !MINER_TYPE.equals(sel.workstationType())) {
                continue;
            }
            if (!cp.equals(new net.minecraft.world.level.ChunkPos(
                    new BlockPos(sel.minX(), sel.minY(), sel.minZ())))) {
                continue;
            }
            if (player.isShiftKeyDown()) {
                reassignMine(player, stack, overworld, sel);   // same bind-or-all semantics as pens
            } else {
                player.displayClientMessage(
                    Component.translatable("bannerbound.foremans_rod.mine_already_marked",
                        resourceLabel(com.bannerbound.core.entity.MinerWorkGoal.mineResource(sel.seedItemId())))
                        .withStyle(ChatFormatting.AQUA), true);
            }
            return;
        }
        if (!isWorkableChunk(settlement, clicked)) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.outside_territory")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        com.bannerbound.core.territory.ChunkResource type =
            com.bannerbound.core.territory.ChunkResources.typeAt(level, cp);
        if (!com.bannerbound.core.territory.BoulderLayout.isOreChunk(type)) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.mine_no_deposit")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        if (com.bannerbound.core.territory.BoulderLayout.dropFor(type).isEmpty()) {
            // The resource's yield item isn't in this install (raw tin lives in Antiquity).
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.mine_unworkable")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        // Locate the chunk's boulder; pre-feature chunks get dressed (built fresh) right here.
        int baseY = com.bannerbound.core.territory.BoulderLayout.locateBaseY(level, cp, type)
            .orElseGet(() -> com.bannerbound.core.territory.BoulderLayout.dress(level, cp));

        UUID selectionId = UUID.randomUUID();
        BlockSelection candidate = BlockSelection.workstation(
            selectionId, settlement.id(), settlement.color().ordinal(),
            clicked, clicked, MINER_TYPE, player.getUUID(),
            com.bannerbound.core.entity.MinerWorkGoal.packMine(type, baseY));
        String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
        if (targetStr != null && !targetStr.isEmpty()) {
            try {
                candidate = candidate.withAssignedCitizen(UUID.fromString(targetStr));
            } catch (IllegalArgumentException ignored) { /* malformed → leave open to all */ }
        }
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(overworld);
        if (registry.anyOverlapExcluding(candidate, selectionId)) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.overlap")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        registry.register(candidate);
        SelectionBroadcaster.broadcast(server);
        player.displayClientMessage(
            Component.translatable("bannerbound.foremans_rod.mine_marked", resourceLabel(type))
                .withStyle(ChatFormatting.GREEN), true);
    }

    /**
     * Guard-post marking: a single click on any block inside the settlement's own claims commits a
     * point selection of type {@code guard}. Guards ({@code GuardWorkGoal}) man marked posts —
     * bound-to-one-guard via the rod's target, or open (one guard each via {@code GuardPostClaims})
     * — and hold nearby instead of walking the perimeter beat. Clicking near an existing post:
     * shift → re-assign its guard from the rod's target, plain → status message (remove with the
     * usual shift-LEFT-click). On any rejection a red message; the registry is never mutated.
     */
    private static void tryCommitGuardPost(ServerPlayer player, ItemStack stack, BlockPos clicked) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        Settlement settlement = SettlementData.get(overworld).getByPlayer(player.getUUID());
        if (settlement == null) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.not_in_settlement")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        // Clicking on/near an existing post: shift → re-assign its guard, plain → status. Mirrors
        // the mine-marker behaviour; never re-marks. "Near" = within 2 blocks, so a fat-fingered
        // second click on the gate doesn't stack a twin post.
        for (BlockSelection sel : BlockSelectionRegistry.get(overworld).getForSettlement(settlement.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION || !GUARD_TYPE.equals(sel.workstationType())) {
                continue;
            }
            BlockPos anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            if (anchor.distManhattan(clicked) > 2) continue;
            if (player.isShiftKeyDown()) {
                reassignGuardPost(player, stack, overworld, sel);
            } else {
                player.displayClientMessage(
                    Component.translatable("bannerbound.foremans_rod.guard_post_already_marked")
                        .withStyle(ChatFormatting.AQUA), true);
            }
            return;
        }
        // Posts live on the settlement's own claimed ground — the leash and patrol both anchor
        // there, and a post outside the defense band would just strand its guard.
        if (!settlement.claimedChunks().contains(
                new net.minecraft.world.level.ChunkPos(clicked).toLong())) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.outside_territory")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        UUID selectionId = UUID.randomUUID();
        BlockSelection candidate = BlockSelection.workstation(
            selectionId, settlement.id(), settlement.color().ordinal(),
            clicked, clicked, GUARD_TYPE, player.getUUID(), "");
        String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
        if (targetStr != null && !targetStr.isEmpty()) {
            try {
                candidate = candidate.withAssignedCitizen(UUID.fromString(targetStr));
            } catch (IllegalArgumentException ignored) { /* malformed → leave open to all */ }
        }
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(overworld);
        if (registry.anyOverlapExcluding(candidate, selectionId)) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.overlap")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        registry.register(candidate);
        SelectionBroadcaster.broadcast(server);
        player.displayClientMessage(
            Component.translatable("bannerbound.foremans_rod.guard_post_marked")
                .withStyle(ChatFormatting.GREEN), true);
    }

    /** Shift-right-click on an existing guard post → set its guard to the rod's current selection:
     *  a specific citizen, or all guards if the rod's target is cleared. (The mine twin:
     *  {@link #reassignMine}.) */
    private static void reassignGuardPost(ServerPlayer player, ItemStack stack, ServerLevel overworld,
                                          BlockSelection sel) {
        String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
        java.util.UUID who = BlockSelection.NO_CITIZEN;
        net.minecraft.network.chat.MutableComponent msg =
            Component.translatable("bannerbound.foremans_rod.guard_post_assigned_all");
        if (targetStr != null && !targetStr.isEmpty()) {
            try {
                who = java.util.UUID.fromString(targetStr);
                String name = stack.get(BannerboundCore.FOREMAN_TARGET_NAME.get());
                msg = Component.translatable("bannerbound.foremans_rod.guard_post_assigned",
                    name != null && !name.isEmpty() ? name : "?");
            } catch (IllegalArgumentException ignored) { who = BlockSelection.NO_CITIZEN; }
        }
        BlockSelectionRegistry.get(overworld).register(sel.withAssignedCitizen(who));
        com.bannerbound.core.world.SelectionBroadcaster.broadcast(player.getServer());
        player.displayClientMessage(msg.withStyle(ChatFormatting.GREEN), true);
    }

    /** Player-facing name of an ore chunk's resource, via lang key {@code bannerbound.resource.<name>}. */
    private static Component resourceLabel(com.bannerbound.core.territory.ChunkResource type) {
        return Component.translatable("bannerbound.resource." + type.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** Shift-right-click on an existing mine marker → set its worker to the rod's current selection: a
     *  specific citizen, or all miners if the rod's target is cleared. (The pen twin: {@link #reassignPen}.) */
    private static void reassignMine(ServerPlayer player, ItemStack stack, ServerLevel overworld, BlockSelection sel) {
        String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
        java.util.UUID who = BlockSelection.NO_CITIZEN;
        net.minecraft.network.chat.MutableComponent msg =
            Component.translatable("bannerbound.foremans_rod.mine_assigned_all");
        if (targetStr != null && !targetStr.isEmpty()) {
            try {
                who = java.util.UUID.fromString(targetStr);
                String name = stack.get(BannerboundCore.FOREMAN_TARGET_NAME.get());
                msg = Component.translatable("bannerbound.foremans_rod.mine_assigned",
                    name != null && !name.isEmpty() ? name : "?");
            } catch (IllegalArgumentException ignored) { who = BlockSelection.NO_CITIZEN; }
        }
        BlockSelectionRegistry.get(overworld).register(sel.withAssignedCitizen(who));
        com.bannerbound.core.world.SelectionBroadcaster.broadcast(player.getServer());
        player.displayClientMessage(msg.withStyle(ChatFormatting.GREEN), true);
    }

    public static String penFailKey(com.bannerbound.core.building.PenEnclosure.FailReason reason) {
        return switch (reason) {
            case NO_GATE -> "bannerbound.foremans_rod.pen_no_gate";
            case NO_WATER -> "bannerbound.foremans_rod.pen_no_water";
            case NO_GRASS -> "bannerbound.foremans_rod.pen_no_grass";
            case TOO_LARGE -> "bannerbound.foremans_rod.pen_not_enclosed";
        };
    }

    /**
     * Extensive-Quarries territory rule for a digger selection: every chunk the box touches must
     * be (1) claimed by this settlement, or (2) claimed by NOBODY and within
     * {@link #QUARRY_REACH_CHUNKS} (Chebyshev) of one of this settlement's claimed chunks.
     * Returns {@code null} when valid, else the lang key of the failure reason — another
     * settlement's land ({@code foreign_territory}) or too far out ({@code quarry_too_far}).
     */
    private static String quarryReachFailKey(ServerLevel overworld, Settlement settlement,
                                             BlockPos a, BlockPos b) {
        SettlementData data = SettlementData.get(overworld);
        java.util.Set<Long> claimed = settlement.claimedChunks();
        int minCX = Math.min(a.getX(), b.getX()) >> 4;
        int maxCX = Math.max(a.getX(), b.getX()) >> 4;
        int minCZ = Math.min(a.getZ(), b.getZ()) >> 4;
        int maxCZ = Math.max(a.getZ(), b.getZ()) >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                long packed = new net.minecraft.world.level.ChunkPos(cx, cz).toLong();
                if (claimed.contains(packed)) continue;
                Settlement owner = data.getByChunk(packed);
                if (owner != null) return "bannerbound.foremans_rod.foreign_territory";
                if (!withinReachOfClaim(claimed, cx, cz)) return "bannerbound.foremans_rod.quarry_too_far";
            }
        }
        return null;
    }

    /** Whether (cx,cz) lies within {@link #QUARRY_REACH_CHUNKS} (Chebyshev) of any claimed chunk. */
    private static boolean withinReachOfClaim(java.util.Set<Long> claimed, int cx, int cz) {
        for (int dx = -QUARRY_REACH_CHUNKS; dx <= QUARRY_REACH_CHUNKS; dx++) {
            for (int dz = -QUARRY_REACH_CHUNKS; dz <= QUARRY_REACH_CHUNKS; dz++) {
                if (claimed.contains(new net.minecraft.world.level.ChunkPos(cx + dx, cz + dz).toLong())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Single-chunk territory test for the point markers (miner pens / herder pens): the chunk is
     *  workable when it's fully claimed OR held by one of this settlement's outpost working claims
     *  — that's the whole point of an outpost (MINER_PLAN.md phase 4). */
    private static boolean isWorkableChunk(Settlement settlement, BlockPos pos) {
        long packed = new net.minecraft.world.level.ChunkPos(pos).toLong();
        return settlement.claimedChunks().contains(packed)
            || settlement.workingClaims().contains(packed);
    }

    /** True if every chunk the bounding box from {@code a} to {@code b} overlaps is in
     *  {@code settlement.claimedChunks()}. Selections are AABBs so we only need to scan the
     *  chunk grid between the min and max corners (cheap — at most ~64 chunks for the largest
     *  allowed selection). */
    public static boolean isFullyWithinTerritory(Settlement settlement, BlockPos a, BlockPos b) {
        int minBlockX = Math.min(a.getX(), b.getX());
        int maxBlockX = Math.max(a.getX(), b.getX());
        int minBlockZ = Math.min(a.getZ(), b.getZ());
        int maxBlockZ = Math.max(a.getZ(), b.getZ());
        int minCX = minBlockX >> 4;
        int maxCX = maxBlockX >> 4;
        int minCZ = minBlockZ >> 4;
        int maxCZ = maxBlockZ >> 4;
        java.util.Set<Long> claimed = settlement.claimedChunks();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!claimed.contains(new net.minecraft.world.level.ChunkPos(cx, cz).toLong())) {
                    return false;
                }
            }
        }
        return true;
    }

    // ─── Hotbar name reflects chosen workstation type ────────────────────────────────────────

    @Override
    public Component getName(ItemStack stack) {
        String wsType = stack.get(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get());
        if (wsType == null || wsType.isEmpty()) {
            return super.getName(stack);
        }
        return Component.translatable("item.bannerbound.foremans_rod.named", targetLabel(stack, wsType));
    }

    /** "Digger — Cedric" when bound to one digger, else just the worker type ("Digger"/"Quarryworker"). */
    private static Component targetLabel(ItemStack stack, String wsType) {
        Component type = clientWorkerTypeName(wsType);
        String targetName = stack.get(BannerboundCore.FOREMAN_TARGET_NAME.get());
        if (isOrderedType(wsType) && targetName != null && !targetName.isEmpty()) {
            return Component.translatable("bannerbound.foremans_rod.one", type, targetName);
        }
        return type;
    }

    /** Client-side worker-type label: "digger" upgrades to "Quarryworker" once the local settlement has
     *  the Quarry research. Guarded so the client-only {@code ClientResearchState} is never touched on a
     *  dedicated server (where this falls back to the base name). */
    private static Component clientWorkerTypeName(String wsType) {
        if (DIGGER_TYPE.equals(wsType) && net.neoforged.fml.loading.FMLEnvironment.dist.isClient()
                && com.bannerbound.core.client.ClientResearchState.hasFlag(
                    com.bannerbound.core.social.WorkstationNames.FLAG_QUARRY)) {
            return Component.translatable("bannerbound.workstation_type.quarryworker");
        }
        return Component.translatable("bannerbound.workstation_type." + wsType);
    }

    // ─── Tooltip: workstation type + in-progress point ───────────────────────────────────────

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        String wsType = stack.get(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get());
        if (wsType == null || wsType.isEmpty()) {
            tooltip.add(Component.translatable("bannerbound.foremans_rod.tooltip.no_workstation")
                .withStyle(ChatFormatting.DARK_GRAY));
            super.appendHoverText(stack, context, tooltip, flag);
            return;
        }
        tooltip.add(Component.translatable("bannerbound.foremans_rod.tooltip.workstation",
            targetLabel(stack, wsType))
            .withStyle(ChatFormatting.GRAY));

        BlockPos a = stack.get(BannerboundCore.FOREMAN_POINT_A.get());
        if (a != null) {
            tooltip.add(Component.translatable("bannerbound.foremans_rod.tooltip.a_only",
                a.getX(), a.getY(), a.getZ()).withStyle(ChatFormatting.DARK_GRAY));
            // Hint the expand gesture for box-drawing types while mid-selection.
            if (isBoxType(wsType)) {
                tooltip.add(Component.translatable("bannerbound.foremans_rod.tooltip.expand_hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
            }
        } else {
            tooltip.add(Component.translatable("bannerbound.foremans_rod.tooltip.no_selection")
                .withStyle(ChatFormatting.DARK_GRAY));
        }

        super.appendHoverText(stack, context, tooltip, flag);
    }
}
