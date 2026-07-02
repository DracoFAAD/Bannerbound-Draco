package com.bannerbound.core.codex;

import java.util.List;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.server.level.ServerPlayer;

/** Any/all/default unlock rule for a Chronicle entry. */
public record CodexUnlockRule(Mode mode, List<CodexCondition> conditions) {
    public enum Mode {
        NONE,
        ANY,
        ALL
    }

    public CodexUnlockRule {
        mode = mode == null ? Mode.NONE : mode;
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    public static CodexUnlockRule unlockedByDefault() {
        return new CodexUnlockRule(Mode.NONE, List.of());
    }

    public boolean startsUnlocked() {
        return mode == Mode.NONE || conditions.isEmpty();
    }

    public boolean canReconcileFromState() {
        if (startsUnlocked()) return true;
        for (CodexCondition condition : conditions) {
            if (!condition.isStateBased()) return false;
        }
        return true;
    }

    public boolean mayMatchEvent(CodexTriggerContext event) {
        if (startsUnlocked()) return false;
        for (CodexCondition condition : conditions) {
            if (condition.matchesEvent(event)) return true;
        }
        return false;
    }

    public boolean isSatisfied(ServerPlayer player, Settlement settlement, CodexTriggerContext event) {
        if (startsUnlocked()) return true;
        if (mode == Mode.ANY) {
            for (CodexCondition condition : conditions) {
                if (condition.isSatisfied(player, settlement, event)) return true;
            }
            return false;
        }
        for (CodexCondition condition : conditions) {
            if (!condition.isSatisfied(player, settlement, event)) return false;
        }
        return true;
    }
}
