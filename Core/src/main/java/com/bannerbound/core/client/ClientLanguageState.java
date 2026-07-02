package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.ResearchDefinition;
import com.bannerbound.core.language.ConceptRole;
import com.bannerbound.core.language.CustomLanguageLabel;
import com.bannerbound.core.language.LanguageConcept;
import com.bannerbound.core.language.LanguageConceptOverrideLoader;
import com.bannerbound.core.language.LanguageConceptResolver;
import com.bannerbound.core.language.LanguageProfile;
import com.bannerbound.core.language.LanguageRegister;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientLanguageState {
    private static volatile boolean enabled;
    private static volatile long seed;

    // getHoverName() is injected at HEAD and runs the full regex-heavy word generation on every
    // call — every tooltip frame, and once per item per keystroke for name-search filters. Memoize
    // the generated label per (seed, era, id); cleared whenever the language sync changes.
    private static final java.util.Map<String, Component> NAME_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    public record DictionaryEntry(String category, String word, String gloss, String note) {
    }

    private ClientLanguageState() {
    }

    public static void replace(boolean nextEnabled, long nextSeed, List<String> conceptOverrides) {
        enabled = nextEnabled;
        seed = nextSeed;
        LanguageConceptOverrideLoader.replaceFromSync(conceptOverrides);
        NAME_CACHE.clear();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static long seed() {
        return seed;
    }

    public static Component jobName(String jobTypeId, boolean quarryUnlocked) {
        if (!enabled) return null;
        String era = ClientEraState.getPlayerEra().name();
        return NAME_CACHE.computeIfAbsent(
            "j|" + seed + "|" + era + "|" + quarryUnlocked + "|" + jobTypeId,
            k -> CustomLanguageLabel.clientJob(seed, ClientEraState.getPlayerEra(), jobTypeId, quarryUnlocked));
    }

    public static Component itemName(ItemStack stack) {
        if (!enabled || stack == null || stack.isEmpty()) return null;
        if (stack.has(DataComponents.CUSTOM_NAME)) return null;
        if (!UnknownItemHelper.isKnown(stack.getItem())) return null;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return null;
        return NAME_CACHE.computeIfAbsent(
            "i|" + seed + "|" + ClientEraState.getPlayerEra().name() + "|" + id,
            k -> {
                String fallback = fallbackItemName(stack);
                LanguageConcept concept = LanguageConceptResolver.forRegistry(
                    id, fallback, ConceptRole.ITEM, itemTags(stack.getItem()));
                return Component.literal(capitalize(word(concept, LanguageRegister.COMMON)));
            });
    }

    public static Component entityName(EntityType<?> type) {
        if (!enabled || type == null) return null;
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (id == null) return null;
        return NAME_CACHE.computeIfAbsent(
            "e|" + seed + "|" + ClientEraState.getPlayerEra().name() + "|" + id,
            k -> {
                String fallback = type.getDescription().getString();
                LanguageConcept concept = LanguageConceptResolver.forRegistry(id, fallback, ConceptRole.ENTITY);
                return Component.literal(capitalize(word(concept, LanguageRegister.COMMON)));
            });
    }

    public static List<DictionaryEntry> dictionaryEntries() {
        List<DictionaryEntry> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR || !UnknownItemHelper.isKnownToSettlement(item)) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) continue;
            String gloss = fallbackItemName(item);
            LanguageConcept concept = LanguageConceptResolver.forRegistry(id, gloss, ConceptRole.ITEM, itemTags(item));
            addEntry(out, seen, "Goods", concept, gloss);
        }

        for (String jobId : ClientLaborState.getJobIds()) {
            LanguageConcept concept = LanguageConceptResolver.forJob(jobId, false);
            addEntry(out, seen, "Work", concept, LanguageConceptResolver.fallbackJobName(jobId, false));
        }

        for (String id : ClientResearchState.getCompleted()) {
            ResearchDefinition def = ClientResearchState.getTree().get(id);
            String gloss = def == null ? LanguageConceptResolver.bestBase(id, id) : def.name();
            LanguageConcept concept = new LanguageConcept(
                "research:" + id, gloss, ConceptRole.ABSTRACT,
                LanguageConceptResolver.bestBase(id, gloss), "", "knowledge");
            addEntry(out, seen, "Knowledge", concept, gloss);
        }

        for (String id : ClientCultureState.getCompleted()) {
            ResearchDefinition def = ClientCultureState.getTree().get(id);
            String gloss = def == null ? LanguageConceptResolver.bestBase(id, id) : def.name();
            LanguageConcept concept = new LanguageConcept(
                "culture:" + id, gloss, ConceptRole.ABSTRACT,
                LanguageConceptResolver.bestBase(id, gloss), "", "custom");
            addEntry(out, seen, "Tradition", concept, gloss);
        }

        out.sort(Comparator
            .comparing(DictionaryEntry::category)
            .thenComparing(DictionaryEntry::gloss)
            .thenComparing(DictionaryEntry::word));
        return out;
    }

    private static void addEntry(List<DictionaryEntry> out, Set<String> seen,
                                 String category, LanguageConcept concept, String gloss) {
        String key = category + "|" + concept.id() + "|" + gloss;
        if (!seen.add(key)) return;
        out.add(new DictionaryEntry(
            category,
            capitalize(word(concept, LanguageRegister.WRITTEN)),
            gloss == null || gloss.isBlank() ? concept.base() : gloss,
            rootNote(concept, gloss)));
    }

    private static String word(LanguageConcept concept, LanguageRegister register) {
        return LanguageProfile.of(seed)
            .formsForConcept(concept, ClientEraState.getPlayerEra())
            .get(register == null ? LanguageRegister.COMMON : register);
    }

    private static String rootNote(LanguageConcept concept, String gloss) {
        if (concept == null || concept.family() == null || concept.family().isBlank()) return "";
        String family = concept.family().trim();
        String cleanGloss = gloss == null ? "" : gloss.trim();
        if (family.equalsIgnoreCase(cleanGloss) || cleanGloss.toLowerCase().contains(family.toLowerCase())) {
            return "";
        }
        return titleCase(family) + " root";
    }

    private static List<String> itemTags(Item item) {
        try {
            return item.builtInRegistryHolder().tags()
                .map(tag -> tag.location().toString())
                .toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private static String fallbackItemName(ItemStack stack) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                return Component.translatable(stack.getDescriptionId()).getString();
            }
        } catch (RuntimeException ignored) {
            // Fall through to registry id parsing. Tooltips should never fail because of language.
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "item" : id.getPath();
    }

    private static String fallbackItemName(Item item) {
        try {
            return item.getDescription().getString();
        } catch (RuntimeException ignored) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            return id == null ? "item" : id.getPath();
        }
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) return "Na";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String titleCase(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        for (String part : value.replace('_', ' ').split(" ")) {
            if (part.isBlank()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) out.append(part.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return out.toString();
    }
}
