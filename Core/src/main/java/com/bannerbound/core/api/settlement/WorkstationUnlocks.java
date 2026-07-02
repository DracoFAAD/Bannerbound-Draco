package com.bannerbound.core.api.settlement;

import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

/**
 * Research-gating for worker units and their workstations. A gated unit cannot have its
 * workstation block opened, and cannot be chosen on the Foreman's Rod, until the settlement
 * has researched the unlocking flag {@code bannerbound.unlock.<unit>}.
 *
 * <p>This flag is an ordinary research flag (it lives in {@code unlocks.flags} of a research
 * JSON and is queried via {@code ResearchManager.hasFlag} / {@code ClientResearchState.hasFlag}).
 * It is deliberately <b>separate</b> from item-unlocking: knowing a workstation block as an item
 * does not by itself mean the settlement can operate it — that takes the unlock flag.
 *
 * <p>To gate a future "Ordered" (or any) unit: add its workstation TYPE_ID → short unit-name
 * entry to {@link #WORKSTATION_UNIT} and put {@code bannerbound.unlock.<unit>} in that unit's
 * research JSON. A workstation type absent from the map is ungated.
 */
@ApiStatus.Internal
public final class WorkstationUnlocks {
    private WorkstationUnlocks() {
    }

    /** Gated workstation block TYPE_IDs → their short unit name. Ungated types are simply absent. */
    private static final Map<String, String> WORKSTATION_UNIT = Map.of(
        "foresters_log",   "forester",
        "diggers_slab",    "digger",
        "farmers_granary", "farmer",
        "fishers_creel",   "fisher",
        "stockpile_rack",  "stocker",
        "foragers_basket", "forager",
        "herders_pen",     "herder"
    );

    /** The research flag that unlocks a worker unit, keyed by its short name (farmer, digger, …). */
    public static String flagForUnit(String unitName) {
        return "bannerbound.unlock." + unitName;
    }

    /** The short unit name gating a workstation/job {@code TYPE_ID}, consulting the built-in map first
     *  then {@link com.bannerbound.core.api.job.CitizenJobRegistry registry} jobs. {@code null} = ungated. */
    public static String unitForWorkstation(String workstationTypeId) {
        String unit = WORKSTATION_UNIT.get(workstationTypeId);
        return unit != null ? unit : com.bannerbound.core.api.job.CitizenJobRegistry.unitFor(workstationTypeId);
    }

    /**
     * The research flag gating a workstation block, keyed by its block-entity {@code TYPE_ID},
     * or {@code null} if that workstation type is ungated. Registry-defined jobs are gated by the
     * unit they declare via {@link com.bannerbound.core.api.job.CitizenJobRegistry}.
     */
    public static String flagForWorkstation(String workstationTypeId) {
        String unit = unitForWorkstation(workstationTypeId);
        return unit == null ? null : flagForUnit(unit);
    }

    /** The research flag gating a WORKSHOP TYPE (e.g. {@code "carpentry"} → the carpenter unlock),
     *  resolved from the crafter-profession units declared via
     *  {@link com.bannerbound.core.api.workshop.WorkBlockRegistry#registerTypeUnit}. {@code null} when
     *  that workshop type is ungated (or mixed/none). This is what gates assigning a generic Crafter
     *  to a workshop: the specialty — and its research gate — comes from the workshop, not the job. */
    public static String flagForWorkshopType(String workshopTypeId) {
        String unit = com.bannerbound.core.api.workshop.WorkBlockRegistry.unitForType(workshopTypeId);
        return unit == null ? null : flagForUnit(unit);
    }

    /** Reverse lookup: the workstation/job {@code TYPE_ID} gated by a given unit name, or {@code null}.
     *  Used to resolve a migration successor (unit {@code "fisher"} → {@code "fishers_creel"}). Checks
     *  the built-in map first, then registry jobs. */
    public static String workstationForUnit(String unitName) {
        if (unitName == null) return null;
        for (Map.Entry<String, String> e : WORKSTATION_UNIT.entrySet()) {
            if (unitName.equals(e.getValue())) return e.getKey();
        }
        for (com.bannerbound.core.api.job.CitizenJobRegistry.JobDef d
                : com.bannerbound.core.api.job.CitizenJobRegistry.all()) {
            if (unitName.equals(d.unitName())) return d.jobTypeId();
        }
        return null;
    }
}
