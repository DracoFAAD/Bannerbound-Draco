package com.bannerbound.core.codex;

import java.util.Locale;

import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.WorkstationUnlocks;

import net.minecraft.server.level.ServerPlayer;

/** One Chronicle unlock condition. */
public record CodexCondition(
    String type,
    String id,
    String item,
    String block,
    String era,
    String flag,
    String advancement,
    String job
) {
    public CodexCondition {
        type = norm(type);
        id = norm(id);
        item = norm(item);
        block = norm(block);
        era = norm(era);
        flag = norm(flag);
        advancement = norm(advancement);
        job = norm(job);
    }

    public boolean isStateBased() {
        CodexTriggerRegistry.ConditionHandler custom = CodexTriggerRegistry.get(canonicalType());
        if (custom != null) return custom.isStateBased(this);
        return switch (canonicalType()) {
            case "research_completed", "culture_completed", "research", "culture",
                "era_reached", "flag", "job_unlocked" -> true;
            default -> false;
        };
    }

    public boolean matchesEvent(CodexTriggerContext event) {
        if (event == null) return false;
        String t = canonicalType();
        CodexTriggerRegistry.ConditionHandler custom = CodexTriggerRegistry.get(t);
        if (custom != null) return custom.matchesEvent(this, event);
        return switch (t) {
            case "research_completed", "research" ->
                event.type().equals("research_completed") && matches(valueId(), event.id());
            case "culture_completed", "culture" ->
                event.type().equals("culture_completed") && matches(valueId(), event.id());
            case "item_obtained" -> event.type().equals("item_obtained") && matches(valueItem(), event.item());
            case "block_used" -> event.type().equals("block_used") && matches(valueBlock(), event.block());
            case "block_placed" -> event.type().equals("block_placed") && matches(valueBlock(), event.block());
            case "block_formed" -> event.type().equals("block_formed") && matches(valueBlock(), event.block());
            case "era_reached" -> event.type().equals("era_reached") && eraAtLeast(event.era(), valueEra());
            case "flag" -> event.type().equals("flag") && matches(valueFlag(), event.flag());
            case "advancement" -> event.type().equals("advancement") && matches(valueAdvancement(), event.advancement());
            case "job_unlocked" -> event.type().equals("flag") && matches(jobFlag(), event.flag());
            default -> event.type().equals(t) && (valueId().isEmpty() || matches(valueId(), event.id()));
        };
    }

    public boolean isSatisfied(ServerPlayer player, Settlement settlement, CodexTriggerContext event) {
        CodexTriggerRegistry.ConditionHandler custom = CodexTriggerRegistry.get(canonicalType());
        if (custom != null) return custom.isSatisfied(this, player, settlement, event);
        if (event != null && matchesEvent(event)) return true;
        if (settlement == null) return false;
        String t = canonicalType();
        return switch (t) {
            case "research_completed", "research" -> settlement.hasCompletedResearch(valueId());
            case "culture_completed", "culture" -> settlement.hasCompletedCultureResearch(valueId());
            case "era_reached" -> eraAtLeast(settlement.age().key(), valueEra());
            case "flag" -> ResearchManager.hasFlagEitherTree(settlement, valueFlag());
            case "job_unlocked" -> {
                String flagValue = jobFlag();
                yield !flagValue.isEmpty() && ResearchManager.hasFlagEitherTree(settlement, flagValue);
            }
            default -> false;
        };
    }

    private String canonicalType() {
        return type.toLowerCase(Locale.ROOT);
    }

    private String valueId() {
        return !id.isEmpty() ? id : firstNonBlank(item, block, flag, advancement, job);
    }

    private String valueItem() {
        return !item.isEmpty() ? item : id;
    }

    private String valueBlock() {
        return !block.isEmpty() ? block : id;
    }

    private String valueEra() {
        return !era.isEmpty() ? era : id;
    }

    private String valueFlag() {
        return !flag.isEmpty() ? flag : id;
    }

    private String valueAdvancement() {
        return !advancement.isEmpty() ? advancement : id;
    }

    private String jobFlag() {
        String unit = !job.isEmpty() ? job : id;
        if (unit.isEmpty()) return "";
        String direct = unit.startsWith("bannerbound.unlock.") ? unit : WorkstationUnlocks.flagForUnit(unit);
        return direct == null ? "" : direct;
    }

    private static boolean matches(String wanted, String actual) {
        return !wanted.isEmpty() && wanted.equals(actual);
    }

    private static boolean eraAtLeast(String actual, String wanted) {
        Era actualEra = Era.fromName(actual);
        Era wantedEra = Era.fromName(wanted);
        return actualEra != null && wantedEra != null && actualEra.ordinal() >= wantedEra.ordinal();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim();
    }
}
