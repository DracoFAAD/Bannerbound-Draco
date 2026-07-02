package com.bannerbound.core.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.LaborStatePayload;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side cache of the settlement's gatherer-labor state for the Town Hall "Labor" tab. The
 * server keeps it fresh via {@link LaborStatePayload} (on town-hall open and after edits). Holds the
 * unlocked gatherer jobs in priority order with per-job enabled flag + current/target worker counts,
 * and the global auto-assign flag.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientLaborState {
    private static volatile List<String> jobIds = List.of();
    private static volatile List<Boolean> enabled = List.of();
    private static volatile List<Integer> current = List.of();
    private static volatile List<Integer> caps = List.of();
    private static volatile boolean autoAssign = true;
    private static volatile boolean workloadShareActive = false;
    private static volatile long preferredStorage = Long.MIN_VALUE;

    private ClientLaborState() {
    }

    public static void replace(LaborStatePayload p) {
        jobIds = List.copyOf(p.jobIds());
        enabled = List.copyOf(p.enabled());
        current = List.copyOf(p.current());
        caps = List.copyOf(p.caps());
        autoAssign = p.autoAssign();
        workloadShareActive = p.workloadShareActive();
        preferredStorage = p.preferredStorage();
    }

    public static List<String> getJobIds() { return jobIds; }
    public static List<Boolean> getEnabled() { return enabled; }
    public static List<Integer> getCurrent() { return current; }
    /** Player-set worker cap per job ({@code -1} = no limit), parallel to {@link #getJobIds()}. */
    public static List<Integer> getCaps() { return caps; }
    public static boolean isAutoAssign() { return autoAssign; }
    /** Whether the Workload Share policy is active (delegates Labor-tab editing to every member). */
    public static boolean isWorkloadShareActive() { return workloadShareActive; }
    /** Packed BlockPos of the settlement's preferred storage, or {@link Long#MIN_VALUE} if none. */
    public static long getPreferredStorage() { return preferredStorage; }
    public static boolean hasPreferredStorage() { return preferredStorage != Long.MIN_VALUE; }
}
