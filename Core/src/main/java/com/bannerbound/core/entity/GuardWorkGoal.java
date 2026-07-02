package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Guard {@link WorkGoal} — a settlement's standing watch (GUARD_PLAN.md). By default the guard
 * <b>patrols the claim perimeter</b> (the ring of claimed chunks that borders unclaimed land — the
 * edge an enemy crosses), weapon in hand. If the player has marked <b>guard posts</b> with the
 * Foreman's Rod ({@code "guard"} point selections in the {@link BlockSelectionRegistry}), the guard
 * instead mans a post: it walks there and holds within {@link #POST_HOLD_RADIUS} blocks, pacing a
 * little so the watch doesn't read as statues. Open posts distribute one-guard-per-post through
 * {@link GuardPostClaims}; a rod-bound post is private to its citizen. Guards beyond the post count
 * fall back to the perimeter beat.
 *
 * <p>It does NOT fight here: the actual combat lives in the priority-0 {@link GuardCombatGoal},
 * which preempts this goal whenever a hostile is in range and returns the guard to its beat after.
 *
 * <h2>Not a gatherer</h2>
 * Guards produce no haul, so this extends {@link WorkGoal} directly (not {@link GathererWorkGoal})
 * and is registered {@code gatherer(false)} — a government-assigned institution, never anarchy
 * self-employed. {@link #canStartWork()} runs its OWN readiness (job + a settlement to defend)
 * rather than {@link CitizenEntity#isGatherJobReady} — that helper requires a drop-off depot, which
 * a guard (depositing nothing) never has.
 *
 * <h2>Weapon — a real logistics cost</h2>
 * The guard's weapon is drawn from the settlement storage pool through the normal {@link JobTools}
 * provisioning (the {@code "guard"} tool-age role, with slings/bows appended — see
 * {@link JobTools#allowedToolsFor}). There is <b>no conjured fallback</b>: an empty armory means a
 * bare-handed watch that patrols the same but fights at fist damage
 * ({@link GuardCombatGoal}). Stock spears and swords — or slings and bows for a ranged watch.
 */
@ApiStatus.Internal
public class GuardWorkGoal extends WorkGoal {
    /** Per-citizen job id (registered with {@code CitizenJobRegistry}). */
    public static final String JOB_TYPE_ID = "guards_post";
    /** Foreman's Rod / {@link BlockSelectionRegistry} workstation type for a marked guard post. */
    public static final String SELECTION_TYPE = "guard";

    /** Slings a guard may fight ranged with pre-Archery — Antiquity contributes its slingshot via
     *  this tag (any expansion sling joins the same way). */
    public static final TagKey<Item> GUARD_SLINGS_TAG = TagKey.create(
        Registries.ITEM, ResourceLocation.fromNamespaceAndPath("bannerbound", "guard_slings"));

    private static final double ARRIVE_SQ = 2.2 * 2.2;
    private static final int ROAM_TIMEOUT_TICKS = 300;     // can't reach a beat point → pick another
    /** Prefer beat points within this many chunks of the guard, so it paces its own stretch of wall
     *  rather than marching across the whole town to a far corner each time. */
    private static final int BEAT_CHUNK_RADIUS = 8;
    /** A posted guard holds within this many blocks of its post. */
    private static final double POST_HOLD_RADIUS_SQ = 8.0 * 8.0;
    /** How far a posted guard's idle pacing strays from the post block. */
    private static final int POST_PACE_RANGE = 3;
    /** Ticks between a posted guard's post-revalidation / idle pace picks. */
    private static final int POST_TICK_INTERVAL = 60;

    private BlockPos beatPos;
    private int roamAge;
    @Nullable private BlockPos postPos;
    private int postCooldown;

    public GuardWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    @Override
    protected boolean canStartWork() {
        // Guard readiness is its OWN check — NOT isGatherJobReady (which gates on a drop-off depot a
        // guard never has). All a guard needs is its job and a settlement with claims to patrol.
        if (!JOB_TYPE_ID.equals(citizen.getJobType())) return false;
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        Settlement s = citizen.getSettlement();
        if (s == null || s.claimedChunks().isEmpty()) return false;
        // Arm up from the settlement pool (no-op when already armed / nothing stocked). A weaponless
        // guard still takes the watch — bare-fisted and weak, which is the point of the economy.
        JobTools.tryEquipToolFromStorage(citizen, s);
        postPos = findGuardPost(sl, s);
        if (postPos != null) return true;
        if (beatPos == null) {
            beatPos = pickBeatPos(sl, s);
            roamAge = 0;
        }
        return beatPos != null;
    }

    @Override
    protected boolean canKeepWorking() {
        return JOB_TYPE_ID.equals(citizen.getJobType());
    }

    @Override
    public void start() {
        citizen.setWorking(true);
        equipWeapon();
        postCooldown = 0;
        if (postPos != null) {
            moveTo(postPos);
        } else if (beatPos != null) {
            moveTo(beatPos);
        }
    }

    @Override
    public void stop() {
        citizen.setWorking(false);
        citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        GuardPostClaims.releaseAll(citizen.getId());
        beatPos = null;
        postPos = null;
        roamAge = 0;
    }

    @Override
    public void tick() {
        if (!(citizen.level() instanceof ServerLevel sl)) return;
        Settlement s = citizen.getSettlement();
        if (s == null) return;
        if (postPos != null) {
            tickPost(sl, s);
            return;
        }
        tickPatrol(sl, s);
    }

    // ─── Post duty ──────────────────────────────────────────────────────────────────────────

    private void tickPost(ServerLevel sl, Settlement s) {
        if (--postCooldown > 0) {
            // Between beats: if we drifted outside the hold radius (combat pull, knockback),
            // start walking back without waiting for the next revalidation.
            if (citizen.getNavigation().isDone() && distSqTo(postPos) > POST_HOLD_RADIUS_SQ) {
                moveTo(postPos);
            }
            return;
        }
        postCooldown = POST_TICK_INTERVAL;
        // Revalidate: the marker may have been removed, rebound, or grabbed by another guard.
        BlockPos current = findGuardPost(sl, s);
        if (current == null) {
            postPos = null;   // no post for us anymore → perimeter beat resumes next tick
            return;
        }
        postPos = current;
        if (distSqTo(postPos) > POST_HOLD_RADIUS_SQ) {
            moveTo(postPos);
            return;
        }
        // On station: pace a step or two so the watch reads as alive, not statuary.
        int x = postPos.getX() + citizen.getRandom().nextInt(POST_PACE_RANGE * 2 + 1) - POST_PACE_RANGE;
        int z = postPos.getZ() + citizen.getRandom().nextInt(POST_PACE_RANGE * 2 + 1) - POST_PACE_RANGE;
        int y = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos p = new BlockPos(x, y, z);
        if (WorkerPathing.isWalkable(sl, p) || WorkerPathing.isWalkable(sl, p.above())) {
            moveTo(p);
        }
    }

    private double distSqTo(BlockPos p) {
        return citizen.position().distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
    }

    /**
     * The post this guard should man, or null to fall back to the perimeter beat. Preference:
     * a post rod-BOUND to this citizen, then the open post it already mans, then the nearest
     * unmanned open post ({@link GuardPostClaims} keeps two guards off one post). Claims (or
     * refreshes) the reservation on success.
     */
    @Nullable
    private BlockPos findGuardPost(ServerLevel sl, Settlement s) {
        ServerLevel overworld = sl.getServer().overworld();
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        double bestSq = Double.MAX_VALUE;
        boolean bestOpen = false;
        for (BlockSelection sel : BlockSelectionRegistry.get(overworld).getForSettlement(s.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (!sel.targetsCitizen(citizen.getUUID())) continue;
            BlockPos anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            boolean open = sel.targetsAllWorkers();
            int score;
            if (!open) {
                score = 0;                                                   // bound to me
            } else if (GuardPostClaims.ownedBy(anchor, citizen.getId())) {
                score = 1;                                                   // already mine
            } else if (!GuardPostClaims.isClaimedByOther(sl, anchor, citizen.getId())) {
                score = 2;                                                   // free for the taking
            } else {
                continue;                                                    // manned by a peer
            }
            double dSq = citizen.distanceToSqr(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
            if (score < bestScore || (score == bestScore && dSq < bestSq)) {
                best = anchor;
                bestScore = score;
                bestSq = dSq;
                bestOpen = open;
            }
        }
        if (best != null && bestOpen) {
            GuardPostClaims.claim(best, citizen.getId());
        }
        return best;
    }

    // ─── Perimeter beat (no posts) ──────────────────────────────────────────────────────────

    private void tickPatrol(ServerLevel sl, Settlement s) {
        if (beatPos == null) {
            beatPos = pickBeatPos(sl, s);
            roamAge = 0;
            if (beatPos != null) moveTo(beatPos);
            return;
        }
        double d = distSqTo(beatPos);
        if (d <= ARRIVE_SQ || ++roamAge > ROAM_TIMEOUT_TICKS) {
            beatPos = pickBeatPos(sl, s);
            roamAge = 0;
            if (beatPos != null) moveTo(beatPos);
            return;
        }
        if (citizen.getNavigation().isDone()) moveTo(beatPos);
    }

    /** Show the guard's weapon in hand while on watch — the installed job tool, or bare hands. */
    private void equipWeapon() {
        ItemStack tool = citizen.getJobTool();
        citizen.setItemSlot(EquipmentSlot.MAINHAND, tool.isEmpty() ? ItemStack.EMPTY : tool.copy());
    }

    /** The guard's weapon stack: the installed job tool (a REAL, quality-bearing item drawn from
     *  storage), or EMPTY for a bare-handed watch. No conjured fallback — weapons are an economy.
     *  Shared with {@link GuardCombatGoal} so patrol and combat hold the same blade. */
    static ItemStack currentWeapon(CitizenEntity citizen) {
        return citizen.getJobTool();
    }

    // ─── Weapon stats (read off the actual held ItemStack) ─────────────────────────────────

    /** True for anything a guard shoots like a bow — {@code #bannerbound:hunter_bows} plus any
     *  {@link BowItem} as a safety net (same test as the hunter's). */
    static boolean isBowWeapon(ItemStack stack) {
        return stack.is(HunterWorkGoal.HUNTER_BOWS_TAG) || stack.getItem() instanceof BowItem;
    }

    /** True for a sling — {@code #bannerbound:guard_slings} (Antiquity's slingshot). */
    static boolean isSlingWeapon(ItemStack stack) {
        return stack.is(GUARD_SLINGS_TAG);
    }

    static boolean isRangedWeapon(ItemStack stack) {
        return !stack.isEmpty() && (isBowWeapon(stack) || isSlingWeapon(stack));
    }

    /** The full melee hit of {@code stack} — 1.0 (fist) + its mainhand ATTACK_DAMAGE additive
     *  modifiers, i.e. what the item itself says it hits for. This is what makes a stocked bronze
     *  sword genuinely better than a bone club in a guard's hand. */
    static double weaponAttackDamage(ItemStack stack) {
        return 1.0 + sumAttribute(stack, true);
    }

    /** The attack speed of {@code stack} — the player-baseline 4.0 + its mainhand ATTACK_SPEED
     *  additive modifiers (swords land at ~1.6, like the tool-age JSONs assume). */
    static double weaponAttackSpeed(ItemStack stack) {
        return 4.0 + sumAttribute(stack, false);
    }

    private static double sumAttribute(ItemStack stack, boolean damage) {
        if (stack.isEmpty()) return 0.0;
        ItemAttributeModifiers mods = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (mods == null) return 0.0;
        double sum = 0.0;
        for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
            if (!e.slot().test(EquipmentSlot.MAINHAND)) continue;
            var attr = damage ? Attributes.ATTACK_DAMAGE : Attributes.ATTACK_SPEED;
            if (e.attribute().value() != attr.value()) continue;
            if (e.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                sum += e.modifier().amount();
            }
        }
        return sum;
    }

    /** A walkable point on the claim perimeter near the guard. Falls back to any claimed chunk. */
    private BlockPos pickBeatPos(ServerLevel sl, Settlement s) {
        List<Long> perimeter = perimeterChunks(s);
        List<Long> pool = perimeter.isEmpty() ? new ArrayList<>(s.claimedChunks()) : perimeter;
        if (pool.isEmpty()) return null;
        // Prefer perimeter chunks near the guard so it paces a local stretch, not the whole border.
        ChunkPos here = new ChunkPos(citizen.blockPosition());
        List<Long> nearby = new ArrayList<>();
        for (long packed : pool) {
            ChunkPos cp = new ChunkPos(packed);
            if (Math.abs(cp.x - here.x) <= BEAT_CHUNK_RADIUS && Math.abs(cp.z - here.z) <= BEAT_CHUNK_RADIUS) {
                nearby.add(packed);
            }
        }
        List<Long> chosen = nearby.isEmpty() ? pool : nearby;
        for (int attempt = 0; attempt < 12; attempt++) {
            ChunkPos cp = new ChunkPos(chosen.get(citizen.getRandom().nextInt(chosen.size())));
            int x = (cp.x << 4) + citizen.getRandom().nextInt(16);
            int z = (cp.z << 4) + citizen.getRandom().nextInt(16);
            int y = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (WorkerPathing.isWalkable(sl, p) || WorkerPathing.isWalkable(sl, p.above())) {
                return p;
            }
        }
        return null;
    }

    /** Claimed chunks that touch an unclaimed chunk — the perimeter ring an attacker has to cross. */
    private static List<Long> perimeterChunks(Settlement s) {
        Set<Long> claimed = s.claimedChunks();
        List<Long> out = new ArrayList<>();
        for (long packed : claimed) {
            ChunkPos cp = new ChunkPos(packed);
            if (!claimed.contains(ChunkPos.asLong(cp.x + 1, cp.z))
                    || !claimed.contains(ChunkPos.asLong(cp.x - 1, cp.z))
                    || !claimed.contains(ChunkPos.asLong(cp.x, cp.z + 1))
                    || !claimed.contains(ChunkPos.asLong(cp.x, cp.z - 1))) {
                out.add(packed);
            }
        }
        return out;
    }

    private void moveTo(BlockPos p) {
        if (p == null) return;
        citizen.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, skilledSpeed());
    }
}
