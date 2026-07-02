package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.StatusEffectIcon;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client mirror of the local player's settlement status-effect list. Source of truth for the
 * Town Hall Statuses tab.
 * <p>
 * Authoritative replacements arrive via {@link com.bannerbound.core.network.StatusEffectListPayload}
 * on add/remove. Between syncs the client decrements remaining ticks locally (driven by
 * {@link com.bannerbound.core.client.ClientResearchEvents#onClientTick}) so the progress bars
 * animate smoothly without per-tick network traffic. When an effect's remaining ticks reaches
 * zero locally we drop it immediately — the server's removal payload will arrive shortly after
 * to confirm.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientStatusState {
    /** Immutable snapshot of one effect on the client. Mutable {@code remainingTicks} is held
     *  on the outer entry list to keep this record cheap-to-equal-check during render. */
    public record Entry(
        UUID instanceId,
        String translationKey,
        List<String> args,
        StatusEffectIcon icon,
        double iconValue,
        int totalDurationTicks,
        int remainingTicks
    ) {}

    private static final AtomicReference<List<Entry>> EFFECTS =
        new AtomicReference<>(java.util.Collections.emptyList());

    private ClientStatusState() {
    }

    /** Server replaced our list — replace the mirror wholesale. */
    public static void setAll(List<Entry> entries) {
        EFFECTS.set(List.copyOf(entries));
    }

    /** Snapshot used by the Statuses tab renderer. */
    public static List<Entry> getAll() {
        return EFFECTS.get();
    }

    /** Decrement remaining ticks on every entry; drop any that hit zero. Called once per client
     *  tick. Safe to call on the main thread — atomic-reference swap keeps reads consistent. */
    public static void tickClient() {
        List<Entry> current = EFFECTS.get();
        if (current.isEmpty()) return;
        List<Entry> next = new ArrayList<>(current.size());
        for (Entry e : current) {
            int rem = e.remainingTicks() - 1;
            if (rem > 0) {
                next.add(new Entry(e.instanceId(), e.translationKey(), e.args(), e.icon(),
                    e.iconValue(), e.totalDurationTicks(), rem));
            }
        }
        EFFECTS.set(java.util.Collections.unmodifiableList(next));
    }
}
