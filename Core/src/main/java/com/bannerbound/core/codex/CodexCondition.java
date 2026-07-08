package com.bannerbound.core.codex;

import java.util.Locale;

import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.WorkstationUnlocks;

import net.minecraft.server.level.ServerPlayer;

/**
 * One Chronicle unlock condition (a row in a codex entry's requirement list). A single record holds
 * every possible operand (id/item/block/era/flag/advancement/job); the compact constructor trims
 * nulls to "" and the valueXxx accessors fall back to the generic id when the typed field is blank,
 * so authors can write either {"type":"flag","flag":"x"} or {"type":"flag","id":"x"}.
 *
 * type is matched case-insensitively (canonicalType). CodexTriggerRegistry lets other modules
 * register custom condition handlers that override the built-in switch in all three predicates:
 * isStateBased (satisfiable from settlement state alone vs event-only), matchesEvent (does a fired
 * trigger satisfy this row) and isSatisfied (state OR event). job_unlocked resolves a job unit to
 * its unlock flag via WorkstationUnlocks; era_reached compares Era ordinals.
 */
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
                "era_reached", "flag", "job_unlocked", "always", "settlement_founded",
                "government_enacted", "population_reached", "dimension_entered" -> true;
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
            case "population_reached" ->
                event.type().equals("population_reached") && intAtLeast(event.id(), valueId());
            case "always" -> false;
            default -> event.type().equals(t) && (valueId().isEmpty() || matches(valueId(), event.id()));
        };
    }

    public boolean isSatisfied(ServerPlayer player, Settlement settlement, CodexTriggerContext event) {
        CodexTriggerRegistry.ConditionHandler custom = CodexTriggerRegistry.get(canonicalType());
        if (custom != null) return custom.isSatisfied(this, player, settlement, event);
        if (event != null && matchesEvent(event)) return true;
        String t = canonicalType();
        if (t.equals("always")) return true;
        if (t.equals("dimension_entered")) {
            return player != null
                && player.level().dimension().location().toString().equals(normalizedDimension());
        }
        if (settlement == null) return false;
        return switch (t) {
            case "research_completed", "research" -> settlement.hasCompletedResearch(valueId());
            case "culture_completed", "culture" -> settlement.hasCompletedCultureResearch(valueId());
            case "era_reached" -> eraAtLeast(settlement.age().key(), valueEra());
            case "flag" -> ResearchManager.hasFlagEitherTree(settlement, valueFlag());
            case "job_unlocked" -> {
                String flagValue = jobFlag();
                yield !flagValue.isEmpty() && ResearchManager.hasFlagEitherTree(settlement, flagValue);
            }
            case "settlement_founded" -> true;
            case "government_enacted" -> settlement.governmentType() != Settlement.Government.NONE;
            case "population_reached" ->
                intAtLeast(String.valueOf(settlement.population()), valueId());
            default -> false;
        };
    }

    private String normalizedDimension() {
        String value = valueId();
        return value.contains(":") ? value : "minecraft:" + value;
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

    private static boolean intAtLeast(String actual, String wanted) {
        try {
            return Integer.parseInt(actual.trim()) >= Integer.parseInt(wanted.trim());
        } catch (NumberFormatException ex) {
            return false;
        }
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
