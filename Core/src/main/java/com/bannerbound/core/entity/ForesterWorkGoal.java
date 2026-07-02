package com.bannerbound.core.entity;

import com.bannerbound.core.api.entity.ForesterTreeRegistry;
import com.bannerbound.core.api.research.ResearchManager;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Forester {@link GathererWorkGoal} — the original gatherer. A gatherer's loop is: SCAN for a
 * resource → walk to it → fake-chop with sound + swing + particles → claim the yield into the
 * workstation BE → spend stamina. Common scaffolding (citizen, speedModifier, assignment
 * resolution) lives on {@link WorkGoal}; future shared behaviour (rescan cooldowns, target-age
 * timeouts, the SCAN/WALK/WORK skeleton) is a candidate to lift into {@link GathererWorkGoal}.
 * <p>
 * Forester specifics: target = log block ({@link BlockTags#LOGS}), yield = whole-tree fell
 * (in-house flood + break, or PFT compat path). The 3-fake-chop pacing, the tool-age chop budget,
 * the per-log stamina cost, and the per-tree BE fit check are forester-only for now.
 * <p>
 * Yield gates that drop the goal back to {@link SettlementPatrolGoal}: missing assignment,
 * inactive workstation toggle, broken building, exhausted stamina, no tree-that-fits in range.
 * No "idle at station" middle state.
 */
@ApiStatus.Internal
public class ForesterWorkGoal extends GathererWorkGoal {
    private static final int SEARCH_RADIUS = 64;
    private static final int SEARCH_HEIGHT = 16;
    /** Max horizontal reach to chop a log. Wider than a player's reach so a path-finder landing
     * one tile off (foliage, hill, etc.) doesn't deadlock the chop loop. */
    private static final double HORIZONTAL_REACH_SQ = 4.5 * 4.5;
    /** Max vertical reach — covers most natural trees from ground without needing to climb. */
    private static final double VERTICAL_REACH = 12.0;
    /** Hard cap on flood-fill so a weird connected-log structure can't stall the AI. */
    private static final int MAX_TREE_SIZE = 200;
    /** Logs beyond this count are treated as "mega" trees (giant jungle, mega spruce, dark oak)
     * and skipped outright — the climb-from-ground chop model can't realistically handle them, so
     * we'd rather citizens walk past and find a normal tree. Tuned high enough to fit fancy oaks
     * (~20 logs) and the occasional pair of fancy oaks that diagonal-flood-fill fuses into one
     * group; mega trees still trip the 2×2 trunk check independently. */
    private static final int MEGA_TREE_THRESHOLD = 60;
    /** Fallback chop wind-up when no tool age is unlocked (bare-handed). Each unlocked tool age
     *  can override this via its JSON's {@code chop_ticks} field; faster tools = shorter wind-up. */
    private static final int DEFAULT_CHOP_TICKS = 80;
    /** Settlement flag (granted by the Lumberjacking research) that lets foresters target mega
     *  trees — dark oak, mega spruce, giant jungle — instead of skipping them outright. */
    private static final String FLAG_LARGE_TREE_CUTTING = "bannerbound.large_tree_cutting";
    /** For a tree larger than {@link #MEGA_TREE_THRESHOLD}, each step of this many logs above
     *  the threshold earns one extra swing interval of chop time — so bigger trunks visibly take
     *  more swings to bring down. */
    private static final int LARGE_TREE_INTERVAL_LOGS = 2;
    private static final int RESCAN_COOLDOWN_TICKS = 60;
    /** If a target log can't be chopped within this many ticks, abandon it and try another.
     * Prevents pathfinder failures from locking the citizen onto an unreachable target. */
    private static final int TARGET_TIMEOUT_TICKS = 200;
    /** How long the citizen's drop-capture window stays open after a chop. PFT's falling-tree
     *  animation drops items over a couple of seconds; this leaves headroom. While the window
     *  is open, any ItemEntity that spawns within {@link CitizenEntity#CAPTURE_RADIUS_SQ} of the
     *  chop position is siphoned straight into the workstation BE. */
    private static final int CAPTURE_WINDOW_TICKS = 200;

    private enum Phase { SCAN, CHOP }

    private Phase phase = Phase.SCAN;
    private BlockPos targetLog;
    private BlockPos standPos;
    private int chopTimer;
    private int rescanCooldown;
    /** Ticks since the current targetLog was picked. Reset on every successful chop. */
    private int targetAge;
    /** Logs of the tree the citizen committed to. Picked once when they target a new tree, then
     *  picked-from until empty so they don't bounce between trees. */
    private Set<BlockPos> currentTreeLogs;

    /** Stable job-type id for the forester (formerly the Forester's Log workstation's TYPE_ID).
     *  Lives here now that the workstation block is gone — used by {@link CitizenEntity#getJobType},
     *  the JOB icon table, and research-flag gating. */
    public static final String JOB_TYPE_ID = "foresters_log";
    /** Type string the Foreman's Rod stamps on forester <b>plantation</b> selections — the ordered
     *  "tend a tree grid" mode handled by {@link ForesterPlantationGoal}. The free-roaming gatherer
     *  (this class) uses no selection; a forester with a {@code forester_farm} order bound to it
     *  yields to the plantation goal (see {@link #canStartWork}). */
    public static final String SELECTION_TYPE = "forester_farm";

    public ForesterWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    /** Resolves the citizen's job depot: its marked drop-off container, or — in anarchy with no real
     *  storage — the citizen's carry pack (it hauls that to the town hall). Null if unset / unloaded /
     *  no longer valid. */
    private Container resolveDepot() {
        return DropOffContainers.resolveJobDepot(citizen);
    }

    @Override
    protected boolean canStartWork() {
        // Yield to the plantation goal whenever a forester_farm order is bound to this forester —
        // a managed grove means "tend only, never clear-cut wild trees" (see ForesterPlantationGoal).
        if (ForesterPlantationGoal.hasPlantationOrder(citizen)) return false;
        citizen.validateJobStorage();   // clear a broken drop-off so we don't chop for a dead container
        // No work without a job + tool + a marked drop-off that still resolves to a container.
        if (!citizen.isForesterReady()) return false;
        // The depot is resolved up-front so the tree picker can reject trees whose logs won't fit.
        // No global is-full early-out — the picker decides per-tree, so the worker keeps making
        // progress on any species/size that still fits. Null depot (chest broken / chunk unloaded)
        // yields to patrol.
        Container depot = resolveDepot();
        if (depot == null) return false;

        // Already committed to a log? Continue it.
        if (targetLog != null && isLog(citizen.level(), targetLog)) {
            phase = Phase.CHOP;
            return true;
        }

        // Still committed to a tree from a previous tick? Prune chopped/protected logs and pick
        // the closest remaining one before considering any other tree.
        if (currentTreeLogs != null) {
            currentTreeLogs.removeIf(p -> !isLog(citizen.level(), p));
            if (!currentTreeLogs.isEmpty() && isTreeProtected(citizen.level(), currentTreeLogs)) {
                currentTreeLogs = null;
            }
            if (currentTreeLogs != null && !currentTreeLogs.isEmpty()) {
                BlockPos next = lowestLog(currentTreeLogs);
                targetLog = next;
                standPos = findChopStandPos(citizen.level(), next);
                targetAge = 0;
                phase = Phase.CHOP;
                return true;
            }
            currentTreeLogs = null; // tree fully chopped — fall through to find a new one
        }

        if (rescanCooldown-- > 0) return false;
        // Rank candidate trees by distance from the citizen, not the drop-off. After felling a
        // tree the worker is often nowhere near home; picking the next tree closest to where they're
        // standing saves them a long walk back-and-forth. The depot is passed so the picker can
        // reject trees whose logs wouldn't all fit — partial spillage on logs is not OK.
        TreePick pick = findNearestTree(citizen.level(), citizen.blockPosition(),
            citizen.getSettlement(), citizen, depot);
        if (pick == null) {
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        // Claim the whole tree before committing. If another citizen grabbed it between our
        // candidate filter and now (rare but possible on the same server tick), bail and retry
        // shortly — findNearestTree will then route us to a different tree.
        if (!ForesterTreeRegistry.tryClaim(citizen.getUUID(), pick.tree())) {
            rescanCooldown = 5;
            return false;
        }
        targetLog = pick.log();
        currentTreeLogs = new HashSet<>(pick.tree());
        standPos = findChopStandPos(citizen.level(), targetLog);
        targetAge = 0;
        phase = Phase.CHOP;
        return true;
    }

    /**
     * Resets the current target so canUse() can pick a fresh log. Also evicts the bad log from
     * the committed tree set so the next pass picks a sibling log instead of looping back to the
     * same unreachable position. Releases the tree claim so other foresters can take it.
     */
    private void abandonTarget() {
        if (targetLog != null) {
            // Tell the settlement before we clear the reference, so the message can include the
            // coords of the tree we gave up on.
            citizen.broadcastCannotReach(targetLog);
        }
        if (currentTreeLogs != null && targetLog != null) {
            currentTreeLogs.remove(targetLog);
        }
        ForesterTreeRegistry.release(citizen.getUUID());
        currentTreeLogs = null;
        targetLog = null;
        standPos = null;
        targetAge = 0;
        chopTimer = 0;
        rescanCooldown = RESCAN_COOLDOWN_TICKS;
        phase = Phase.SCAN;
    }

    @Override
    protected boolean canKeepWorking() {
        if (!citizen.isForesterReady()) return false;
        if (resolveDepot() == null) return false;
        return targetLog != null && isLog(citizen.level(), targetLog);
    }

    @Override
    public void start() {
        chopTimer = 0;
        citizen.setWorking(true);
        // Render the player-provided axe in hand (a copy — the canonical tool lives on the citizen
        // and is never consumed). stop() clears the render copy again.
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            citizen.getJobTool().copy());
        if (phase == Phase.CHOP && standPos != null) {
            citizen.getNavigation().moveTo(
                standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, skilledSpeed());
        }
    }

    @Override
    public void tick() {
        if (!citizen.isForesterReady()) return;
        tickChop();
    }

    private void tickChop() {
        if (targetLog == null) return;
        targetAge++;
        if (targetAge > TARGET_TIMEOUT_TICKS) {
            abandonTarget();
            return;
        }
        double dx = (targetLog.getX() + 0.5) - citizen.getX();
        double dz = (targetLog.getZ() + 0.5) - citizen.getZ();
        double horizSq = dx * dx + dz * dz;
        double dy = Math.abs((targetLog.getY() + 0.5) - citizen.getY());

        citizen.getLookControl().setLookAt(
            targetLog.getX() + 0.5, targetLog.getY() + 0.5, targetLog.getZ() + 0.5);

        // Within horizontal reach AND vertical reach → chop. Otherwise navigate to stand pos.
        if (horizSq <= HORIZONTAL_REACH_SQ && dy <= VERTICAL_REACH) {
            citizen.getNavigation().stop();
            chopTimer++;
            // Base chop budget comes from the HELD axe's tool age — not the settlement's best
            // unlocked age. Hand a forester a bone axe and they chop at bone speed even after Wood
            // Refining; a stone axe cuts that down, iron further. Bare/unknown tool → default ticks.
            // A swing fires on every `interval`-tick boundary, so a normal tree gets ~3 swings.
            com.bannerbound.core.api.research.ToolAge toolAge =
                com.bannerbound.core.api.research.data.ToolAgeLoader.getByTool("axe", citizen.getJobTool().getItem());
            int baseBudget = toolAge != null ? toolAge.chopTicks().orElse(DEFAULT_CHOP_TICKS) : DEFAULT_CHOP_TICKS;
            // Anarchy without an axe → chop slower (bare-handed). Handing the forester an axe (in or
            // out of anarchy) returns the factor to 1.0 and full speed. interval derives from this,
            // so the swings space out proportionally too.
            baseBudget = (int) Math.round(baseBudget * citizen.anarchyWorkSpeedFactor());
            baseBudget = com.bannerbound.core.api.quality.QualityTier.scaleWorkTicks(
                citizen.getJobTool(), baseBudget);
            // Experience on top of tool/quality: a master forester swings faster than a novice with
            // the same axe (scales interval AND chopBudget, so the tree still gets ~3 swings).
            baseBudget = skilledWorkTicks(baseBudget);
            int interval = Math.max(1, baseBudget / 3);
            // Large-tree extension: every LARGE_TREE_INTERVAL_LOGS logs above MEGA_TREE_THRESHOLD
            // adds one more swing interval of chop time. A 30-log dark oak is under the threshold
            // and chops at base speed; a 120-log giant jungle earns ~30 extra swings.
            int treeSize = currentTreeLogs != null ? currentTreeLogs.size() : 0;
            int chopBudget = baseBudget;
            if (treeSize > MEGA_TREE_THRESHOLD) {
                int extraSwings = (treeSize - MEGA_TREE_THRESHOLD) / LARGE_TREE_INTERVAL_LOGS;
                chopBudget += extraSwings * interval;
            }
            if (chopTimer % interval == 0 && citizen.level() instanceof ServerLevel sl) {
                playSwing(sl, targetLog);
            }
            if (chopTimer >= chopBudget) {
                chopLog();
                chopTimer = 0;
            }
        } else {
            if (citizen.getNavigation().isDone() && standPos != null) {
                citizen.getNavigation().moveTo(
                    standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, skilledSpeed());
            }
            chopTimer = 0;
        }
    }

    /**
     * Fires one fake-punch: swing the citizen's arm (broadcasts to all nearby clients), play the
     * block's hit-sound, and spawn a handful of block-break particles at the log's position.
     */
    private void playSwing(ServerLevel level, BlockPos log) {
        // Broadcast the swing as an AnimatePacket directly. Each tracking client receives it and
        // calls LivingEntity.swing(MAIN_HAND) locally, which sets swingTime + swinging. From
        // there, CitizenEntity.aiStep's updateSwingTime() call (added because Mob.aiStep doesn't
        // drive it for non-Monster mobs) ramps attackAnim every tick and the model rotates the
        // arm. Server-side swing-field pokes were redundant — the client packet handler does
        // the assignment itself, and the server's swing state isn't read by any of our code.
        net.minecraft.network.protocol.game.ClientboundAnimatePacket packet =
            new net.minecraft.network.protocol.game.ClientboundAnimatePacket(citizen, 0);
        level.getChunkSource().broadcastAndSend(citizen, packet);
        BlockState state = level.getBlockState(log);
        SoundType st = state.getSoundType();
        level.playSound(null, log,
            st.getHitSound(), SoundSource.BLOCKS, st.getVolume() * 0.5f, st.getPitch());
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
            log.getX() + 0.5, log.getY() + 0.5, log.getZ() + 0.5,
            4, 0.3, 0.3, 0.3, 0.0);
    }

    /**
     * Fells the entire tree at {@link #targetLog} in one go. Tries Pandas Falling Trees first
     * (when installed); falls back to an in-house flood-fill + break-all sweep. All drops are
     * routed to the citizen's marked drop-off container — no citizen carry, no deposit walk.
     * Spends one stamina point after a successful fell.
     */
    private void chopLog() {
        Level level = citizen.level();
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!isLog(level, targetLog)) {
            abandonTarget();
            return;
        }
        // Re-vet protection right before breaking — if someone slid cobblestone under any log of
        // this tree after the initial pick, abandon the whole tree now instead of finishing it.
        Set<BlockPos> tree = collectConnectedTree(level, targetLog);
        if (isTreeProtected(level, tree)) {
            abandonTarget();
            return;
        }

        Container depot = resolveDepot();

        BlockPos felledAt = targetLog;
        // Sample the trunk-base species BEFORE destruction so the replant step can pick the
        // matching sapling after the block is gone.
        net.minecraft.world.level.block.Block seedBlock = level.getBlockState(felledAt).getBlock();
        // Re-vet depot room right before felling. canUse() approved this tree based on whatever
        // state the container had then, but PFT's delayed drops from a previous chop's capture
        // window could have arrived since and eaten the slot we were counting on. Without this
        // check, the chop fires anyway and the new drops spill.
        if (depot != null && seedBlock != net.minecraft.world.level.block.Blocks.AIR) {
            net.minecraft.world.item.Item logItem = seedBlock.asItem();
            if (logItem != net.minecraft.world.item.Items.AIR
                    && DropOffContainers.roomFor(depot, new ItemStack(logItem)) < tree.size()) {
                abandonTarget();
                return;
            }
        }
        // In carry-home mode (the depot IS the citizen's carry pack — anarchy with no pooled storage)
        // force the in-house fell so logs route into the pack; PFT's delayed ground drops can't be
        // funnelled into a pack. With any real container (pooled basket/stockpile) PFT is fine.
        boolean handledByPFT = depot != citizen.getAnarchyHaul()
            && com.bannerbound.core.compat.FallingTreesCompat.fellTree(
                serverLevel, felledAt, citizen.getX(), citizen.getY(), citizen.getZ());
        if (!handledByPFT) {
            // In-house path routes drops directly into the container. No ItemEntities, no ground
            // spawn unless the container is full.
            fellTreeAndRouteToDepot(citizen, serverLevel, tree, depot);
        } else if (depot != null) {
            // PFT's falling-tree entity drops ItemEntities over the next couple of seconds along
            // its trajectory. Open the capture window so those drops are routed to the drop-off
            // container by ForesterDropCaptureEvents instead of landing on the ground.
            citizen.beginCaptureWindow(felledAt, CAPTURE_WINDOW_TICKS);
        }

        // If the settlement has researched forester replant, drop in a matching sapling from the
        // drop-off container at the trunk position. Mismatched species / empty → silent no-op.
        Settlement settlement = citizen.getSettlement();
        if (settlement != null && depot != null
                && com.bannerbound.core.api.research.ResearchManager.hasFlag(
                    settlement, "bannerbound.foresters_replant")) {
            tryReplant(serverLevel, felledAt, seedBlock, depot);
        }

        // Whole tree is gone — spend stamina proportional to the work just done (one point per
        // log felled). A 5-log oak costs 5; a fancy oak ~15-20; big trees genuinely tire the
        // citizen instead of a flat 1-per-tree that lets them chop forever.
        citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "wood");
        citizen.consumeStamina(Math.max(1, tree.size()));
        ForesterTreeRegistry.release(citizen.getUUID());
        currentTreeLogs = null;
        targetLog = null;
        standPos = null;
        targetAge = 0;
        phase = Phase.SCAN;
    }

    /**
     * After a tree is felled, if the settlement has the {@code bannerbound.foresters_replant}
     * flag, find a matching sapling stack in the drop-off container and place it at the trunk
     * position. Species match is strict: oak logs replant only with oak saplings (the path-
     * substitution {@code _log → _sapling} mirrors vanilla naming and works for every vanilla
     * species). Skipped silently when the sapling block doesn't exist (modded oddballs), no
     * matching stack is in the container, or the ground below isn't dirt-tagged.
     */
    private void tryReplant(ServerLevel level, BlockPos felledAt,
                            net.minecraft.world.level.block.Block seedBlock,
                            Container depot) {
        net.minecraft.resources.ResourceLocation logId =
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(seedBlock);
        if (logId == null) return;
        String saplingPath = logId.getPath().replace("_log", "_sapling");
        if (saplingPath.equals(logId.getPath())) return; // not a *_log block
        net.minecraft.resources.ResourceLocation saplingId =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(logId.getNamespace(), saplingPath);
        if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(saplingId)) return;
        net.minecraft.world.level.block.Block sapling =
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(saplingId);
        net.minecraft.world.item.Item saplingItem = sapling.asItem();
        if (saplingItem == net.minecraft.world.item.Items.AIR) return;

        // Source one sapling: the drop-off container first, then the settlement's valid stockpiles
        // — the Stocker drains the drop-off, so saplings usually end up in storage.
        int matchSlot = -1;
        for (int i = 0; i < depot.getContainerSize(); i++) {
            ItemStack s = depot.getItem(i);
            if (!s.isEmpty() && s.is(saplingItem)) { matchSlot = i; break; }
        }
        com.bannerbound.core.api.settlement.Settlement settlement = citizen.getSettlement();
        boolean fromStockpile = matchSlot < 0;
        if (fromStockpile && (settlement == null
                || com.bannerbound.core.stockpile.StockpileService.count(level, settlement, saplingItem) <= 0)) {
            return;
        }

        // Soil check — sapling needs dirt-family ground below.
        if (!level.getBlockState(felledAt.below()).is(BlockTags.DIRT)) return;

        // Trunk spot must be replaceable (air after the fell, or PFT's leftover air).
        BlockState here = level.getBlockState(felledAt);
        if (!here.isAir() && !here.canBeReplaced()) return;

        level.setBlock(felledAt, sapling.defaultBlockState(), 3);
        if (fromStockpile) {
            com.bannerbound.core.stockpile.StockpileService.withdraw(level, settlement, saplingItem, 1);
        } else {
            depot.getItem(matchSlot).shrink(1);
            depot.setChanged();
        }
    }

    /** Horizontal half-extent of the canopy leaf-cut box (so the box is 5 wide in X and Z). */
    private static final int LEAF_CUT_RADIUS = 2;
    /** Vertical half-extent of the canopy leaf-cut box (so the box is 3 tall, centered on the
     *  highest log). */
    private static final int LEAF_CUT_HEIGHT = 1;

    /**
     * Fallback whole-tree fell when PFT isn't installed. Breaks every log in the connected set in
     * one tick and routes drops directly into the drop-off container. {@code destroyBlock(pos,
     * false, ...)} is critical here so vanilla doesn't double-drop — we feed {@link
     * net.minecraft.world.level.block.Block#getDrops} output ourselves and let the container
     * absorb. Logs that overflow drop at the citizen's feet; clutter that overflows is discarded.
     */
    static void fellTreeAndRouteToDepot(CitizenEntity citizen, ServerLevel level,
                                        Set<BlockPos> tree, Container depot) {
        for (BlockPos p : tree) {
            BlockState state = level.getBlockState(p);
            if (!state.is(BlockTags.LOGS)) continue;
            List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(
                state, level, p, null);
            com.bannerbound.core.api.research.SettlementDropFilter.filterStacks(citizen.getSettlement(),
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()), drops);
            level.destroyBlock(p, false, citizen);
            for (ItemStack drop : drops) {
                if (drop.isEmpty()) continue;
                if (depot == null) {
                    // No container reachable — drop on the ground as a last resort.
                    citizen.spawnAtLocation(drop);
                    continue;
                }
                ItemStack leftover = DropOffContainers.insert(depot, drop);
                // Logs that couldn't fit (per-tree fit check should have prevented this, but a
                // racing PFT drop can eat a slot) spill at the citizen's feet so they're not lost.
                // Clutter (sticks / apples / saplings) is discarded — it would otherwise pile up
                // around the chop site faster than the player can collect it.
                if (!leftover.isEmpty() && leftover.is(state.getBlock().asItem())) {
                    citizen.spawnAtLocation(leftover);
                }
            }
        }
        cutCanopyLeaves(citizen, level, tree, depot);
    }

    /**
     * Breaks a 5×5×3 box of leaves centered on the tree's highest log and routes the drops to the
     * drop-off container. The in-house fell only removes logs, leaving the canopy to slowly decay
     * — which means foresters miss the sapling / apple / stick yield that Pandas Falling Trees
     * produces when it fells the whole tree at once. Cutting the canopy explicitly closes that gap.
     * <p>
     * Any leaf block in the box is fair game — no "is this leaf attached to this tree" check. The
     * box is small enough that grabbing a sliver of a neighboring tree's canopy is a negligible
     * (and harmless) bonus, and it spares us the leaf-distance-blockstate bookkeeping.
     */
    static void cutCanopyLeaves(CitizenEntity citizen, ServerLevel level,
                                Set<BlockPos> tree, Container depot) {
        BlockPos top = highestLog(tree);
        if (top == null) return;
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int dx = -LEAF_CUT_RADIUS; dx <= LEAF_CUT_RADIUS; dx++) {
            for (int dz = -LEAF_CUT_RADIUS; dz <= LEAF_CUT_RADIUS; dz++) {
                for (int dy = -LEAF_CUT_HEIGHT; dy <= LEAF_CUT_HEIGHT; dy++) {
                    c.set(top.getX() + dx, top.getY() + dy, top.getZ() + dz);
                    BlockState state = level.getBlockState(c);
                    if (!state.is(BlockTags.LEAVES)) continue;
                    // "Logs only" forester: clear the canopy for cleanliness but don't collect the
                    // saplings/apples/sticks — keeps the drop-off a pure log chain (player choice).
                    if (!citizen.foresterKeepsExtras()) {
                        level.destroyBlock(c, false, citizen);
                        continue;
                    }
                    List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(
                        state, level, c, null);
                    com.bannerbound.core.api.research.SettlementDropFilter.filterStacks(citizen.getSettlement(),
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()), drops);
                    level.destroyBlock(c, false, citizen);
                    for (ItemStack drop : drops) {
                        if (drop.isEmpty()) continue;
                        if (depot == null) {
                            citizen.spawnAtLocation(drop);
                        } else {
                            // Leaf clutter overflow is discarded (same rationale as logs above).
                            DropOffContainers.insert(depot, drop);
                        }
                    }
                }
            }
        }
    }

    private static BlockPos highestLog(Set<BlockPos> tree) {
        BlockPos high = null;
        int highestY = Integer.MIN_VALUE;
        for (BlockPos p : tree) {
            if (p.getY() > highestY) {
                highestY = p.getY();
                high = p;
            }
        }
        return high;
    }

    @Override
    public void stop() {
        chopTimer = 0;
        citizen.setWorking(false);
        // Release any tree claim when the goal stops — covers exile, death, reassignment, and
        // settlement disband. Idempotent if we already released in chopLog/abandonTarget.
        ForesterTreeRegistry.release(citizen.getUUID());
        currentTreeLogs = null;
        // Drop the work tool when leaving the goal so a resting / patrolling citizen isn't seen
        // hauling around an axe.
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            net.minecraft.world.item.ItemStack.EMPTY);
    }

    /** Pure block check — used to verify the current target is still there. */
    static boolean isLog(Level level, BlockPos pos) {
        if (pos == null) return false;
        return level.getBlockState(pos).is(BlockTags.LOGS);
    }

    /**
     * Picks a standing position at the base of a log's column, adjacent to the trunk rather than
     * inside it: walks down to find the ground level, then searches the four cardinal neighbors
     * at that ground level for an open, walkable tile next to the trunk. Falls back to the column
     * itself only if no adjacent tile is walkable — the pathfinder still finds the closest
     * reachable point in that case.
     */
    static BlockPos findChopStandPos(Level level, BlockPos log) {
        int x = log.getX();
        int z = log.getZ();
        int groundY = log.getY();
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int y = log.getY() - 1; y >= log.getY() - 32; y--) {
            c.set(x, y, z);
            BlockState s = level.getBlockState(c);
            if (s.is(BlockTags.LOGS)) continue;
            if (s.isAir()) continue;
            if (s.isSolid()) {
                groundY = y + 1;
                break;
            }
        }
        // Try each cardinal neighbor: prefer the first passable tile with a solid floor.
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos adj = new BlockPos(x + dir.getStepX(), groundY, z + dir.getStepZ());
            BlockPos floor = adj.below();
            if (WorkerPathing.isPassable(level, adj)
                    && WorkerPathing.hasFloor(level, floor)
                    && !level.getBlockState(floor).is(BlockTags.LOGS)) {
                return adj;
            }
        }
        return new BlockPos(x, groundY, z);
    }

    /** Result of {@link #findNearestTree}: the log to aim for first, plus every other log in its tree. */
    private record TreePick(BlockPos log, Set<BlockPos> tree) {}

    /**
     * Scans the search box for all logs, groups them into connected trees, drops any tree that
     * is cobblestone-protected, mega-sized, or claimed by another forester, and returns the best
     * remaining tree along with the log to start chopping. Selection is distance-first: the
     * preferred-log type and the frontier-push are bounded nudges layered on top of straight-line
     * distance, not overrides.
     */
    private static TreePick findNearestTree(Level level, BlockPos origin, Settlement settlement,
                                            CitizenEntity citizen, Container depot) {
        // With the Lumberjacking research, mega trees stop being off-limits — the citizen can
        // target dark oak / mega spruce / giant jungle. Without it, the three mega filters below
        // skip them as before.
        boolean allowLarge = settlement != null
            && ResearchManager.hasFlag(settlement, FLAG_LARGE_TREE_CUTTING);
        // Phase 1: collect every log in the search box.
        Set<BlockPos> allLogs = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                for (int dy = -SEARCH_HEIGHT; dy <= SEARCH_HEIGHT; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (level.getBlockState(cursor).is(BlockTags.LOGS)) {
                        allLogs.add(cursor.immutable());
                    }
                }
            }
        }
        if (allLogs.isEmpty()) return null;

        // Phase 2: group into connected trees; vet each; rank whole trees (not individual logs)
        // by a composite score — lowest wins. Straight-line distance to the tree's nearest log is
        // the PRIMARY term; the other two are bounded nudges measured in blocks:
        //   preferred-log miss → +PREF_NUDGE     (worth ~this many blocks of detour for the
        //                                          workstation's chosen species)
        //   inside-claim only  → +FRONTIER_NUDGE (worth ~this many blocks of detour to chop a
        //                                          tree on unclaimed land — gentle frontier push)
        // Because both nudges are small relative to the search radius, the forester never treks
        // past a close tree to reach a far "perfect" one — it picks the nearest tree and only
        // diverts when a preferred / frontier tree is within the nudge's worth of extra steps.
        // (Earlier these were 1e8 / 1e5 absolute overrides, which is what made the forester run
        // to the furthest oak on the map.)
        final double PREF_NUDGE = 20.0;
        final double FRONTIER_NUDGE = 12.0;
        net.minecraft.world.level.block.Block preferredLog = citizen.getPreferredLog();

        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> chosen = null;
        double bestScore = Double.MAX_VALUE;

        for (BlockPos seed : allLogs) {
            if (visited.contains(seed)) continue;
            Set<BlockPos> tree = floodFillTree(level, seed, allLogs);
            visited.addAll(tree);
            if (isTreeProtected(level, tree)) continue;
            if (!allowLarge && tree.size() > MEGA_TREE_THRESHOLD) continue;
            if (!allowLarge && hasTwoByTwoTrunk(tree)) continue;
            if (!allowLarge && hasMegaTrunkInColumn(level, seed)) continue;
            if (ForesterTreeRegistry.isAnyLogClaimedByOther(tree, citizen.getUUID())) continue;
            // Per-tree fit check: sample the seed log's species, count the tree's logs, and skip
            // if the drop-off container can't absorb all of them. Clutter drops (sticks/saplings/
            // apples) are intentionally ignored — they're allowed to spill. Null depot (chunk
            // unloaded) skips the check, matching the prior "best effort" behavior.
            if (depot != null) {
                net.minecraft.world.item.Item logItem = level.getBlockState(seed).getBlock().asItem();
                if (logItem != net.minecraft.world.item.Items.AIR
                        && DropOffContainers.roomFor(depot, new net.minecraft.world.item.ItemStack(logItem)) < tree.size()) {
                    continue;
                }
            }

            boolean matchesPref = preferredLog != null && level.getBlockState(seed).is(preferredLog);
            double minDistSq = Double.MAX_VALUE;
            boolean anyOutsideClaim = settlement == null;
            for (BlockPos p : tree) {
                double d = origin.distSqr(p);
                if (d < minDistSq) minDistSq = d;
                if (settlement != null && !settlement.claimedChunks().contains(new ChunkPos(p).toLong())) {
                    anyOutsideClaim = true;
                }
            }
            // sqrt so the nudges below are in real block units, not squared distance.
            double score = Math.sqrt(minDistSq)
                + (matchesPref ? 0.0 : PREF_NUDGE)
                + (anyOutsideClaim ? 0.0 : FRONTIER_NUDGE);
            if (score < bestScore) {
                bestScore = score;
                chosen = tree;
            }
        }
        if (chosen == null) return null;
        // Target = the LOWEST log so {@link #findChopStandPos} snaps the worker to the base of the
        // tree. Without this they'd often start chopping mid-trunk or on a branch tip — visually
        // weird ("chopping from the top") even though chopLog still fells the whole tree at once.
        return new TreePick(lowestLog(chosen), chosen);
    }

    private static BlockPos lowestLog(Set<BlockPos> tree) {
        BlockPos low = null;
        int lowestY = Integer.MAX_VALUE;
        for (BlockPos p : tree) {
            if (p.getY() < lowestY) {
                lowestY = p.getY();
                low = p;
            }
        }
        return low;
    }

    /**
     * Flood-fills connected logs starting at {@code seed} without pre-collecting the candidate
     * set — used at chop time to re-vet a tree the citizen had already picked. Walks neighbors
     * that are themselves log blocks in the world (so newly-grown logs are picked up too).
     */
    static Set<BlockPos> collectConnectedTree(Level level, BlockPos seed) {
        Set<BlockPos> tree = new HashSet<>();
        if (seed == null) return tree;
        Deque<BlockPos> q = new ArrayDeque<>();
        q.add(seed);
        tree.add(seed);
        while (!q.isEmpty() && tree.size() < MAX_TREE_SIZE) {
            BlockPos p = q.poll();
            for (BlockPos n : neighbors26(p)) {
                if (tree.contains(n)) continue;
                if (!level.getBlockState(n).is(BlockTags.LOGS)) continue;
                tree.add(n);
                q.add(n);
            }
        }
        return tree;
    }

    private static Set<BlockPos> floodFillTree(Level level, BlockPos seed, Set<BlockPos> candidates) {
        Set<BlockPos> tree = new HashSet<>();
        Deque<BlockPos> q = new ArrayDeque<>();
        q.add(seed);
        tree.add(seed);
        while (!q.isEmpty() && tree.size() < MAX_TREE_SIZE) {
            BlockPos p = q.poll();
            for (BlockPos n : neighbors26(p)) {
                if (tree.contains(n)) continue;
                if (!candidates.contains(n)) continue;
                tree.add(n);
                q.add(n);
            }
        }
        return tree;
    }

    /**
     * 3×3×3 neighborhood around {@code p} (the 26 surrounding cells, center excluded). Required so
     * the flood fill follows fancy-oak branches, which step diagonally — face-only adjacency leaves
     * each branch as its own "tree". Also lets two trees touching at the canopy fuse into one
     * group, which is fine: the worker chops both, or skips if the merged group trips the 2×2
     * trunk check or the mega-tree threshold.
     */
    private static Iterable<BlockPos> neighbors26(BlockPos p) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    out.add(p.offset(dx, dy, dz));
                }
            }
        }
        return out;
    }

    /**
     * Returns true if any 2x2 cluster of logs exists at a single Y level inside {@code tree}.
     * Every vanilla mega tree (dark oak, mega spruce, mega jungle) is built on a 2x2 trunk and
     * triggers this; no single-trunk tree ever forms a 2x2 footprint.
     */
    private static boolean hasTwoByTwoTrunk(Set<BlockPos> tree) {
        for (BlockPos p : tree) {
            if (tree.contains(p.east())
                && tree.contains(p.south())
                && tree.contains(p.east().south())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Backstop mega-tree check that reads the world directly: walks ±32 blocks vertically along
     * the log's column and looks for any 2x2 cluster of logs in a 2-wide horizontal neighborhood
     * around the column.
     */
    private static boolean hasMegaTrunkInColumn(Level level, BlockPos log) {
        int range = 32;
        int x = log.getX();
        int z = log.getZ();
        BlockPos.MutableBlockPos a = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos b = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos d = new BlockPos.MutableBlockPos();
        for (int dy = -range; dy <= range; dy++) {
            int y = log.getY() + dy;
            for (int xo = -1; xo <= 0; xo++) {
                for (int zo = -1; zo <= 0; zo++) {
                    a.set(x + xo, y, z + zo);
                    if (!level.getBlockState(a).is(BlockTags.LOGS)) continue;
                    b.set(a.getX() + 1, y, a.getZ());
                    c.set(a.getX(), y, a.getZ() + 1);
                    d.set(a.getX() + 1, y, a.getZ() + 1);
                    if (level.getBlockState(b).is(BlockTags.LOGS)
                        && level.getBlockState(c).is(BlockTags.LOGS)
                        && level.getBlockState(d).is(BlockTags.LOGS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * A tree counts as protected if <em>any</em> of its logs sits directly above a cobblestone
     * block. One marker protects the whole connected trunk + branches.
     */
    static boolean isTreeProtected(Level level, Set<BlockPos> tree) {
        for (BlockPos p : tree) {
            if (level.getBlockState(p.below()).is(Blocks.COBBLESTONE)) {
                return true;
            }
        }
        return false;
    }
}
