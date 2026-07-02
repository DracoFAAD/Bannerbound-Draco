package com.bannerbound.core.api.workshop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.bannerbound.core.api.settlement.Workshop;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Public registry of <b>work blocks</b> — the blocks that make an enclosed building a Workshop
 * (see {@code CRAFTER_PLAN.md}). Expansions register their stations during common setup
 * ({@code FMLCommonSetupEvent.enqueueWork}, like {@code CitizenJobRegistry}):
 *
 * <pre>
 * WorkBlockRegistry.register(new WorkBlockDef(
 *     BannerboundAntiquity.FLETCHING_STATION.get(), "fletchery"));
 * </pre>
 *
 * A workshop's TYPE is derived from the work blocks it contains: one distinct
 * {@code workshopTypeId} → that type; several → the generic "workshop" type. Its CAPACITY (max
 * assigned crafters) is the number of work blocks. Display names come from the lang keys
 * {@code bannerbound.workshop.type.<typeId>} (+ {@code bannerbound.workshop.type.mixed} and
 * {@code .none} for the derived fallbacks).
 *
 * <p>Phase 2 attaches a {@code WorkExecutor} per type (the thing that actually drives an NPC
 * craft); Phase 1 only needs the block → type mapping for validation/derivation.
 */
public final class WorkBlockRegistry {
    /** Derived type id for a workshop containing work blocks of SEVERAL types. */
    public static final String TYPE_MIXED = "mixed";
    /** Derived type id for a workshop that currently contains NO work blocks (invalid). */
    public static final String TYPE_NONE = "none";

    /** The fallback station type a generic Crafter's icon shows when no station family can be
     *  resolved (no held position, and a mixed/none workshop). An expansion declares this once
     *  (Antiquity → {@code "general_crafts"}, the crafting stone) so a Crafter is never iconless.
     *  Its registered type icon takes precedence over {@link #defaultCrafterIconBaseline}. */
    private static volatile String defaultCrafterType = null;
    /** Core baseline for the generic-Crafter fallback icon (a vanilla crafting table) — used when no
     *  expansion has declared a {@link #defaultCrafterType} with an icon. Era-themed expansions
     *  override it via {@link #setDefaultCrafterType} (Antiquity's crafting stone). */
    private static volatile net.minecraft.world.item.Item defaultCrafterIconBaseline = null;

    /** One registered work block: the block, the workshop type it produces, the
     *  {@link WorkExecutor} that drives an NPC craft at it (null = players-only station), and the
     *  type's display icon item (null = no type icon) shown on the rod's floating workshop
     *  overview labels. */
    public record WorkBlockDef(Block block, String workshopTypeId, @Nullable WorkExecutor executor,
                               @Nullable net.minecraft.world.item.Item icon,
                               @Nullable java.util.function.Predicate<BlockState> anchorTest) {
        public WorkBlockDef(Block block, String workshopTypeId) {
            this(block, workshopTypeId, null, null, null);
        }

        public WorkBlockDef(Block block, String workshopTypeId, @Nullable WorkExecutor executor) {
            this(block, workshopTypeId, executor, null, null);
        }

        public WorkBlockDef(Block block, String workshopTypeId, @Nullable WorkExecutor executor,
                            @Nullable net.minecraft.world.item.Item icon) {
            this(block, workshopTypeId, executor, icon, null);
        }

        /** True when {@code state} is a capacity-bearing instance of this station. Single-block
         *  stations always count; a multiblock station supplies an {@code anchorTest} so only its
         *  anchor/master cell counts — one work-slot (and one NPC station) per multiblock, not one
         *  per sub-block. The shell cells are still recognized as part of the station (so they're not
         *  mistaken for storage) but contribute no capacity. */
        public boolean countsAt(BlockState state) {
            return anchorTest == null || anchorTest.test(state);
        }
    }

    private static final Map<Block, WorkBlockDef> BY_BLOCK = new LinkedHashMap<>();
    /** First registered icon per workshop type id (for the floating overview labels). */
    private static final Map<String, net.minecraft.world.item.Item> ICON_BY_TYPE = new LinkedHashMap<>();
    /** Optional per-workshop-type structure rules layered on top of the core workshop checks. */
    private static final Map<String, WorkshopRequirement> REQUIREMENT_BY_TYPE = new LinkedHashMap<>();
    /** Research-unlock unit name per workshop type id (e.g. {@code "carpentry" -> "carpenter"}).
     *  These are the crafter PROFESSIONS — a single generic Crafter job staffs every workshop, and
     *  its specialty (executor, icon, unlock) is derived from the workshop's type, so the per-type
     *  unit lives here rather than on a separate job id. Absent = that type is ungated. */
    private static final Map<String, String> UNIT_BY_TYPE = new LinkedHashMap<>();

    /**
     * Extra validation supplied by a module for one workshop type. Return {@code null} when the
     * workshop satisfies the rule, or the status that should make the workshop invalid.
     */
    @FunctionalInterface
    public interface WorkshopRequirement {
        @Nullable
        Workshop.Status validate(ServerLevel sl, Workshop workshop, Set<BlockPos> marked,
                                 List<BlockPos> reachableWork,
                                 List<BlockPos> reachableStorage);
    }

    private WorkBlockRegistry() {
    }

    /** Registers a work block. Idempotent per block (first registration wins). */
    public static synchronized void register(WorkBlockDef def) {
        BY_BLOCK.putIfAbsent(def.block(), def);
        if (def.icon() != null) {
            ICON_BY_TYPE.putIfAbsent(def.workshopTypeId(), def.icon());
        }
    }

    /** Declares the research-unlock unit gating a workshop type (the crafter profession), e.g.
     *  {@code registerTypeUnit("carpentry", "carpenter")}. First registration wins. */
    public static synchronized void registerTypeUnit(String typeId, String unitName) {
        if (typeId != null && unitName != null) UNIT_BY_TYPE.putIfAbsent(typeId, unitName);
    }

    /** The research-unlock unit name gating a workshop type, or {@code null} when ungated. */
    @Nullable
    public static String unitForType(String typeId) {
        return UNIT_BY_TYPE.get(typeId);
    }

    /** Every distinct crafter-profession unit declared via {@link #registerTypeUnit} — drives the
     *  "is the generic Crafter job available at all" check (any one researched is enough). */
    public static synchronized Set<String> crafterUnits() {
        return Set.copyOf(UNIT_BY_TYPE.values());
    }

    /** Replaces the extra validation rule for a workshop type id. */
    public static synchronized void registerRequirement(String typeId, WorkshopRequirement requirement) {
        REQUIREMENT_BY_TYPE.put(typeId, requirement);
    }

    /** Registers a fallback validation rule for a workshop type id, preserving an expansion rule. */
    public static synchronized void registerRequirementIfAbsent(String typeId,
                                                                WorkshopRequirement requirement) {
        REQUIREMENT_BY_TYPE.putIfAbsent(typeId, requirement);
    }

    /** Runs every registered extra rule for the type ids present in this workshop. */
    @Nullable
    public static Workshop.Status validateRequirements(Set<String> typeIds, ServerLevel sl,
                                                       Workshop workshop, Set<BlockPos> marked,
                                                       List<BlockPos> reachableWork,
                                                       List<BlockPos> reachableStorage) {
        for (String typeId : typeIds) {
            WorkshopRequirement requirement = REQUIREMENT_BY_TYPE.get(typeId);
            if (requirement == null) continue;
            Workshop.Status status = requirement.validate(
                sl, workshop, marked, reachableWork, reachableStorage);
            if (status != null) return status;
        }
        return null;
    }

    /** Declares the fallback station type a generic Crafter's icon falls back to (see
     *  {@link #defaultCrafterType}). First declaration wins. An era-themed expansion calls this to
     *  override Core's plain {@link #setDefaultCrafterIconBaseline crafting-table baseline} with its
     *  own general-crafts station (Antiquity → the crafting stone). */
    public static synchronized void setDefaultCrafterType(String typeId) {
        if (typeId != null && !typeId.isBlank() && defaultCrafterType == null) {
            defaultCrafterType = typeId;
        }
    }

    /** Core baseline for the generic-Crafter fallback icon (a vanilla crafting table). First call
     *  wins; an expansion's {@link #setDefaultCrafterType} still takes precedence over it. */
    public static synchronized void setDefaultCrafterIconBaseline(net.minecraft.world.item.Item item) {
        if (item != null && defaultCrafterIconBaseline == null) {
            defaultCrafterIconBaseline = item;
        }
    }

    /** The fallback station type for a generic Crafter's icon, or {@code null} if none declared. */
    @Nullable
    public static String defaultCrafterType() {
        return defaultCrafterType;
    }

    /** Resolved fallback icon for a generic Crafter that can't resolve a station family: the
     *  declared {@link #defaultCrafterType}'s registered icon (Antiquity's crafting stone) when
     *  present, else the Core {@link #setDefaultCrafterIconBaseline baseline} (a crafting table),
     *  else {@code null}. Resolvable on both sides (registrations run in common setup). */
    @Nullable
    public static net.minecraft.world.item.Item defaultCrafterIcon() {
        if (defaultCrafterType != null) {
            net.minecraft.world.item.Item icon = ICON_BY_TYPE.get(defaultCrafterType);
            if (icon != null) return icon;
        }
        return defaultCrafterIconBaseline;
    }

    /** The display icon item for a workshop type id, or {@code null} when that type is intentionally
     *  iconless (notably mixed/none). Resolvable on BOTH sides (registrations run in common setup). */
    @Nullable
    public static net.minecraft.world.item.Item iconForType(String typeId) {
        return ICON_BY_TYPE.get(typeId);
    }

    /** The registration for this state's block, or null if it isn't a work block. */
    @Nullable
    public static WorkBlockDef of(BlockState state) {
        return BY_BLOCK.get(state.getBlock());
    }

    public static boolean isWorkBlock(BlockState state) {
        return BY_BLOCK.containsKey(state.getBlock());
    }

    /** Lang key for a workshop type id (registered, mixed, or none). */
    public static String displayKey(String typeId) {
        return "bannerbound.workshop.type." + typeId;
    }
}
