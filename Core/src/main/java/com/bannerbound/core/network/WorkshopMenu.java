package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.job.CitizenJobRegistry;
import com.bannerbound.core.api.settlement.Citizen;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.settlement.WorkstationUnlocks;
import com.bannerbound.core.api.workshop.WorkBlockRegistry;
import com.bannerbound.core.api.workshop.Workshops;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.CrafterWorkGoal;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side helper for the Workshop menu: snapshots a workshop into an
 * {@link OpenWorkshopMenuPayload} (re-validating + reconciling the worker roster first) and
 * handles the menu's C→S edits — rename and worker assign/unassign. The citizen's own job state
 * is the source of truth for employment; the workshop's workers list is reconciled against it
 * here (jobs live on the citizen, not the building — the workstation-retirement rule).
 */
@ApiStatus.Internal
public final class WorkshopMenu {
    /** Hard cap on custom workshop names (mirrors settlement-name limits). */
    public static final int MAX_NAME_LENGTH = 24;

    private WorkshopMenu() {
    }

    /** Re-validates, reconciles the roster, and opens (or refreshes) the menu for {@code sp}. */
    public static void open(ServerPlayer sp, ServerLevel sl, Workshops.Hit hit) {
        Workshop w = hit.workshop();
        Workshops.validate(sl, w);
        reconcileWorkers(sl, w);

        List<String> workerIds = new ArrayList<>();
        List<String> workerNames = new ArrayList<>();
        List<Integer> workerJobIcons = new ArrayList<>();
        List<String> workerPositions = new ArrayList<>();
        List<String> candidateIds = new ArrayList<>();
        List<String> candidateNames = new ArrayList<>();
        List<Boolean> candidateEmployed = new ArrayList<>();
        List<Integer> candidateJobIcons = new ArrayList<>();
        for (Citizen c : hit.settlement().citizens()) {
            CitizenEntity ce = sl.getEntity(c.entityId()) instanceof CitizenEntity live ? live : null;
            int icon = ce != null && ce.getJobType() != null
                ? com.bannerbound.core.social.JobIcons.iconItemId(hit.settlement(), ce.getJobType())
                : 0;
            if (w.workers().contains(c.entityId())) {
                workerIds.add(c.entityId().toString());
                workerNames.add(c.name());
                // A positioned worker shows its STATION family's icon (fletchery string, crafting
                // stone) — the roster reads as professions. A worker who hasn't self-assigned yet
                // shows the workshop's own type icon: the crafter JOB has no icon role (one would
                // drag the gatherer tool-slot UI into its Job tab), so the old fallback was blank.
                String position = w.positionOf(c.entityId());
                workerPositions.add(position == null ? "" : position);
                // Default the roster icon to the crafting stone (defaultCrafterType) when a worker
                // hasn't self-assigned yet and the workshop's own type is mixed/none — matching the
                // Job-tab/bubble fallback so a crafter is never iconless.
                String iconType = position != null ? position : w.derivedTypeId();
                net.minecraft.world.item.Item typeIcon =
                    com.bannerbound.core.api.workshop.WorkBlockRegistry.iconForType(iconType);
                if (typeIcon == null) {
                    typeIcon = com.bannerbound.core.api.workshop.WorkBlockRegistry.defaultCrafterIcon();
                }
                workerJobIcons.add(typeIcon == null ? 0
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(typeIcon));
                continue;
            }
            candidateIds.add(c.entityId().toString());
            candidateNames.add(c.name());
            candidateEmployed.add(ce != null && ce.isEmployed());
            candidateJobIcons.add(icon);
        }
        // Min-stock rows: every output the workshop's work blocks can currently produce (deduped),
        // with the configured minimum (0 = off) and the live settlement-wide census count.
        List<Integer> minItems = new ArrayList<>();
        List<Integer> minValues = new ArrayList<>();
        List<Integer> minCounts = new ArrayList<>();
        List<Integer> orderCounts = new ArrayList<>();
        List<Integer> autoOrderCounts = new ArrayList<>();
        java.util.Set<net.minecraft.world.item.Item> seen = new java.util.LinkedHashSet<>();
        for (net.minecraft.core.BlockPos p : w.workBlocks()) {
            com.bannerbound.core.api.workshop.WorkBlockRegistry.WorkBlockDef def =
                com.bannerbound.core.api.workshop.WorkBlockRegistry.of(sl.getBlockState(p));
            if (def == null || def.executor() == null) continue;
            for (net.minecraft.world.item.ItemStack out : def.executor().possibleOutputs(sl, p)) {
                if (!seen.add(out.getItem())) continue;
                String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(out.getItem()).toString();
                minItems.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(out.getItem()));
                minValues.add(w.minStock().getOrDefault(itemId, 0));
                minCounts.add(com.bannerbound.core.api.workshop.SettlementItemCensus
                    .count(sl, hit.settlement(), out.getItem()));
                orderCounts.add(w.orders().getOrDefault(itemId, 0));
                autoOrderCounts.add(w.autoOrders().getOrDefault(itemId, 0));
            }
        }
        // Distinct station families present (the options the per-worker station chooser cycles
        // through). Capacity-bearing instances only — a multiblock's shell cells don't add a family.
        List<String> stationTypeIds = new ArrayList<>();
        java.util.Set<String> seenTypes = new java.util.LinkedHashSet<>();
        for (net.minecraft.core.BlockPos p : w.workBlocks()) {
            com.bannerbound.core.api.workshop.WorkBlockRegistry.WorkBlockDef def =
                com.bannerbound.core.api.workshop.WorkBlockRegistry.of(sl.getBlockState(p));
            if (def != null && seenTypes.add(def.workshopTypeId())) {
                stationTypeIds.add(def.workshopTypeId());
            }
        }
        PacketDistributor.sendToPlayer(sp, new OpenWorkshopMenuPayload(
            w.id().toString(), w.customName(), w.derivedTypeId(),
            w.status().ordinal(), w.capacity(),
            workerIds, workerNames, workerJobIcons,
            candidateIds, candidateNames, candidateEmployed, candidateJobIcons,
            minItems, minValues, minCounts,
            w.cachedAppealBeauty() == null ? -1 : w.cachedAppealBeauty().ordinal(),
            orderCounts, autoOrderCounts,
            workerPositions, stationTypeIds));
    }

    /** Applies a min-stock edit ({@code value ≤ 0} clears the row) and refreshes the menu. */
    public static void handleSetMinStock(ServerPlayer sp, SetWorkshopMinStockPayload payload) {
        ServerLevel sl = sp.serverLevel();
        Workshops.Hit hit = resolve(sp, sl, payload.workshopId());
        if (hit == null) return;
        net.minecraft.world.item.Item item =
            net.minecraft.core.registries.BuiltInRegistries.ITEM.byId(payload.itemId());
        if (item == net.minecraft.world.item.Items.AIR) return;
        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
        if (payload.value() <= 0) {
            hit.workshop().minStock().remove(itemId);
        } else {
            hit.workshop().minStock().put(itemId, Math.min(payload.value(), 999));
        }
        SettlementData.get(sl.getServer().overworld()).setDirty();
        open(sp, sl, hit);
    }

    /** Applies an order-queue edit ({@code value ≤ 0} cancels the order) and refreshes the menu.
     *  A first-time set joins the END of the queue (LinkedHashMap insertion order = FIFO);
     *  re-setting an existing order keeps its queue slot. */
    public static void handleSetOrder(ServerPlayer sp, SetWorkshopOrderPayload payload) {
        ServerLevel sl = sp.serverLevel();
        Workshops.Hit hit = resolve(sp, sl, payload.workshopId());
        if (hit == null) return;
        net.minecraft.world.item.Item item =
            net.minecraft.core.registries.BuiltInRegistries.ITEM.byId(payload.itemId());
        if (item == net.minecraft.world.item.Items.AIR) return;
        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
        if (payload.value() <= 0) {
            hit.workshop().orders().remove(itemId);
        } else {
            hit.workshop().orders().put(itemId, Math.min(payload.value(), 999));
        }
        SettlementData.get(sl.getServer().overworld()).setDirty();
        open(sp, sl, hit);
    }

    /** Drops roster entries whose citizen no longer exists or no longer points back at this
     *  workshop (job changed, died, exiled). The citizen field is the source of truth. Station
     *  positions of dropped workers are pruned with them. */
    private static void reconcileWorkers(ServerLevel sl, Workshop w) {
        boolean dirty = false;
        Iterator<UUID> it = w.workers().iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            if (!(sl.getEntity(id) instanceof CitizenEntity ce)
                    || !w.id().equals(ce.getAssignedWorkshopId())) {
                it.remove();
                dirty = true;
                continue;
            }
            if (CrafterWorkGoal.JOB_TYPE_ID.equals(ce.getJobType())) {
                String specialized = assignmentJobType(w, ce.getJobType());
                if (!CrafterWorkGoal.JOB_TYPE_ID.equals(specialized) && canJobWorkIn(sl, w, specialized)) {
                    ce.setJobType(specialized);
                    String requiredType = CrafterWorkGoal.workshopTypeForJob(specialized);
                    if (requiredType != null) w.setPosition(id, requiredType);
                    dirty = true;
                }
            }
        }
        w.prunePositions();
        if (dirty) SettlementData.get(sl.getServer().overworld()).setDirty();
    }

    /** The Job tab assigned <i>Crafter</i>: list the settlement's workshops so the player picks
     *  where the citizen works (the subtype is derived from that choice — never picked directly).
     *  No workshops marked yet → actionable chat hint instead of an empty picker. */
    public static void openPicker(ServerPlayer sp, ServerLevel sl,
                                  com.bannerbound.core.api.settlement.Settlement settlement,
                                  CitizenEntity citizen) {
        openPicker(sp, sl, settlement, citizen, CrafterWorkGoal.JOB_TYPE_ID);
    }

    /** Opens the workshop picker for a specific workshop-bound job (e.g. Carpenter â†’ Carpentry). */
    public static void openPicker(ServerPlayer sp, ServerLevel sl,
                                  com.bannerbound.core.api.settlement.Settlement settlement,
                                  CitizenEntity citizen, String jobTypeId) {
        String requiredType = CrafterWorkGoal.workshopTypeForJob(jobTypeId);
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> typeIds = new ArrayList<>();
        List<Integer> statuses = new ArrayList<>();
        List<Integer> assigned = new ArrayList<>();
        List<Integer> capacities = new ArrayList<>();
        for (Workshop w : settlement.workshops().values()) {
            Workshops.validate(sl, w);
            reconcileWorkers(sl, w);
            if (requiredType != null && !hasStationOfType(sl, w, requiredType)) continue;
            ids.add(w.id().toString());
            names.add(w.customName());
            typeIds.add(w.derivedTypeId());
            statuses.add(w.status().ordinal());
            assigned.add(w.workers().size());
            capacities.add(w.capacity());
        }
        if (ids.isEmpty()) {
            sp.displayClientMessage(
                net.minecraft.network.chat.Component
                    .translatable("bannerbound.workshop.picker_none")
                    .withStyle(net.minecraft.ChatFormatting.YELLOW), true);
            return;
        }
        PacketDistributor.sendToPlayer(sp, new OpenWorkshopPickerPayload(
            citizen.getUUID().toString(), jobTypeId, ids, names, typeIds, statuses, assigned, capacities));
    }

    /** The Job tab's "Open" button: open a workshop's menu by id (no rod needed). */
    public static void handleOpenRequest(ServerPlayer sp, OpenWorkshopMenuRequestPayload payload) {
        ServerLevel sl = sp.serverLevel();
        Workshops.Hit hit = resolve(sp, sl, payload.workshopId());
        if (hit != null) {
            open(sp, sl, hit);
        }
    }

    /** Applies a rename (clamped; "" resets to the derived-type display). */
    public static void handleRename(ServerPlayer sp, RenameWorkshopPayload payload) {
        ServerLevel sl = sp.serverLevel();
        Workshops.Hit hit = resolve(sp, sl, payload.workshopId());
        if (hit == null) return;
        String name = payload.name().strip();
        if (name.length() > MAX_NAME_LENGTH) name = name.substring(0, MAX_NAME_LENGTH);
        hit.workshop().setCustomName(name);
        SettlementData.get(sl.getServer().overworld()).setDirty();
        // Refresh the floating overview labels (they carry the name).
        com.bannerbound.core.world.SelectionBroadcaster.broadcast(sl.getServer());
    }

    /** Assigns/unassigns a citizen to the workshop: capacity + crafter research unlock enforced,
     *  both sides written, menu re-sent so the screen refreshes in place. */
    public static void handleAssignWorker(ServerPlayer sp, AssignWorkshopWorkerPayload payload) {
        ServerLevel sl = sp.serverLevel();
        Workshops.Hit hit = resolve(sp, sl, payload.workshopId());
        if (hit == null) return;
        UUID citizenId;
        try {
            citizenId = UUID.fromString(payload.citizenId());
        } catch (IllegalArgumentException e) {
            return;
        }
        Workshop w = hit.workshop();
        Workshops.validate(sl, w);
        reconcileWorkers(sl, w);
        if (!(sl.getEntity(citizenId) instanceof CitizenEntity citizen)) {
            open(sp, sl, hit);
            return;
        }
        // The citizen id is client-supplied: only citizens of the workshop's own settlement may
        // be assigned (guards against a tampered client poaching another faction's citizens).
        if (!hit.settlement().id().equals(citizen.getSettlementId())) {
            open(sp, sl, hit);
            return;
        }
        if (payload.assign()) {
            String jobTypeId = assignmentJobType(w, payload.jobTypeId());
            if (!canJobWorkIn(sl, w, jobTypeId)) {
                open(sp, sl, hit);
                return;
            }
            // Gate on the WORKSHOP's specialty, not the job: a generic Crafter staffs every workshop,
            // so the carpentry/pottery/fletchery research gate comes from the workshop's derived type.
            // (A specific workshop-bound job, if any, still self-gates via its own unit below.)
            String flag = WorkstationUnlocks.flagForWorkshopType(w.derivedTypeId());
            if (flag == null) flag = WorkstationUnlocks.flagForWorkstation(jobTypeId);
            if (flag != null && !com.bannerbound.core.api.research.ResearchManager
                    .hasFlag(hit.settlement(), flag)) {
                // Don't fail silently — a workshop can be built (blocks placed) before its craft is
                // researched, leaving a VALID "needs workers" workshop that quietly refuses every
                // assign. Tell the player WHY so it isn't a mystery (the common pottery confusion).
                sp.displayClientMessage(net.minecraft.network.chat.Component
                    .translatable("bannerbound.workshop.assign_locked",
                        net.minecraft.network.chat.Component.translatable(
                            WorkBlockRegistry.displayKey(w.derivedTypeId())))
                    .withStyle(net.minecraft.ChatFormatting.YELLOW), true);
                open(sp, sl, hit);
                return;
            }
            if (w.workers().size() < w.capacity() && !w.workers().contains(citizenId)) {
                citizen.setJobType(jobTypeId);
                citizen.setJobPinned(true);
                citizen.setAssignedWorkshopId(w.id());
                String requiredType = CrafterWorkGoal.workshopTypeForJob(jobTypeId);
                if (requiredType != null) w.setPosition(citizenId, requiredType);
                w.workers().add(citizenId);
            }
        } else if (w.workers().remove(citizenId)) {
            w.clearPosition(citizenId);
            if (w.id().equals(citizen.getAssignedWorkshopId())
                    && CrafterWorkGoal.isWorkshopJob(citizen.getJobType())) {
                citizen.returnJobToolAndClear();
                citizen.setJobType(null);
            }
        }
        SettlementData.get(sl.getServer().overworld()).setDirty();
        // Refresh the floating overview labels (they carry worker occupancy).
        com.bannerbound.core.world.SelectionBroadcaster.broadcast(sl.getServer());
        open(sp, sl, hit);
    }

    /** Pins an assigned worker to a station family within the workshop (or clears the pin when
     *  {@code stationTypeId} is blank → "Any"). The worker must be on the roster and the station
     *  family must actually exist in the workshop; otherwise the edit is dropped and the menu just
     *  refreshes. The crafter respects the pinned position (it only considers stations of that
     *  family), so the player chooses who works which station instead of letting them self-assign. */
    public static void handleSetWorkerStation(ServerPlayer sp, SetWorkshopWorkerStationPayload payload) {
        ServerLevel sl = sp.serverLevel();
        Workshops.Hit hit = resolve(sp, sl, payload.workshopId());
        if (hit == null) return;
        UUID citizenId;
        try {
            citizenId = UUID.fromString(payload.citizenId());
        } catch (IllegalArgumentException e) {
            return;
        }
        Workshop w = hit.workshop();
        Workshops.validate(sl, w);
        reconcileWorkers(sl, w);
        if (w.workers().contains(citizenId)) {
            String typeId = payload.stationTypeId();
            if (typeId == null || typeId.isBlank()) {
                w.clearPosition(citizenId);
            } else if (hasStationOfType(sl, w, typeId)) {
                w.setPosition(citizenId, typeId);
            }
            SettlementData.get(sl.getServer().overworld()).setDirty();
        }
        open(sp, sl, hit);
    }

    /** Resolves the workshop by id and verifies the sender is a member of the owning settlement.
     *  Malformed ids, unknown workshops, and foreign workshops all resolve to null (the edit is
     *  silently dropped) — the id is client-supplied and must never grant cross-settlement access. */
    @Nullable
    private static Workshops.Hit resolve(ServerPlayer sp, ServerLevel sl, String workshopId) {
        Workshops.Hit hit;
        try {
            hit = Workshops.findById(sl, UUID.fromString(workshopId));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (hit == null || !hit.settlement().members().contains(sp.getUUID())) return null;
        return hit;
    }

    private static String assignmentJobType(Workshop w, String requestedJobType) {
        if (requestedJobType != null && !requestedJobType.isBlank()
                && !CrafterWorkGoal.JOB_TYPE_ID.equals(requestedJobType)
                && CrafterWorkGoal.isWorkshopJob(requestedJobType)) {
            return requestedJobType;
        }
        String specific = CitizenJobRegistry.workshopJobForType(w.derivedTypeId());
        return specific != null ? specific : CrafterWorkGoal.JOB_TYPE_ID;
    }

    private static boolean canJobWorkIn(ServerLevel sl, Workshop w, String jobTypeId) {
        if (!CrafterWorkGoal.isWorkshopJob(jobTypeId)) return false;
        String requiredType = CrafterWorkGoal.workshopTypeForJob(jobTypeId);
        return requiredType == null || hasStationOfType(sl, w, requiredType);
    }

    private static boolean hasStationOfType(ServerLevel sl, Workshop w, @Nullable String typeId) {
        if (typeId == null) return true;
        for (net.minecraft.core.BlockPos p : w.workBlocks()) {
            WorkBlockRegistry.WorkBlockDef def = WorkBlockRegistry.of(sl.getBlockState(p));
            if (def != null && typeId.equals(def.workshopTypeId())) return true;
        }
        return false;
    }
}
