package com.bannerbound.core.language;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Optional semantic hints for generated language concepts.
 *
 * <p>Datapacks may add files under {@code data/<namespace>/language_concepts/*.json}:
 * <pre>
 * { "concepts": [
 *   { "id": "alexsmobs:gorilla", "gloss": "gorilla", "family": "ape", "role": "entity" },
 *   { "token": "ingot", "family": "metal", "role": "material" }
 * ] }
 * </pre>
 *
 * <p>The generated word still comes from the settlement seed. These entries only improve the
 * concept's semantic base and role before generation.
 */
public class LanguageConceptOverrideLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "language_concepts";
    private static final Gson GSON = new Gson();

    private static volatile Map<String, Entry> EXACT = Map.of();
    private static volatile Map<String, Entry> TOKENS = Map.of();

    public LanguageConceptOverrideLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, Entry> exact = new HashMap<>();
        Map<String, Entry> tokens = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> resource : resources.entrySet()) {
            try {
                JsonObject obj = resource.getValue().getAsJsonObject();
                JsonArray concepts = obj.has("concepts")
                    ? GsonHelper.getAsJsonArray(obj, "concepts")
                    : singleConceptArray(obj);
                for (JsonElement element : concepts) {
                    JsonObject concept = element.getAsJsonObject();
                    Entry entry = entryFrom(concept);
                    addString(concept, "id", entry, exact, false);
                    addArray(concept, "ids", entry, exact, false);
                    addString(concept, "token", entry, tokens, true);
                    addArray(concept, "tokens", entry, tokens, true);
                    if (!concept.has("id") && !concept.has("ids")
                            && !concept.has("token") && !concept.has("tokens")) {
                        exact.put(normalizeKey(resource.getKey().toString()), entry.withKey(resource.getKey().toString(), false));
                    }
                }
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse language_concepts {}", resource.getKey(), ex);
            }
        }
        EXACT = Map.copyOf(exact);
        TOKENS = Map.copyOf(tokens);
        BannerboundCore.LOGGER.info("Loaded language concept overrides: {} exact, {} token",
            exact.size(), tokens.size());
    }

    public static LanguageConcept applyOverride(String conceptId, String exactKey, String searchable,
                                                String fallbackBase, ConceptRole fallbackRole) {
        return applyOverride(
            new LanguageConcept(conceptId, fallbackBase, fallbackRole),
            exactKey,
            searchable);
    }

    public static LanguageConcept applyOverride(LanguageConcept fallback, String exactKey, String searchable) {
        Entry entry = find(exactKey, searchable);
        if (entry == null) {
            return fallback == null ? new LanguageConcept("unknown", "concept", ConceptRole.BASE_NOUN) : fallback;
        }
        LanguageConcept base = fallback == null
            ? new LanguageConcept("unknown", "concept", ConceptRole.BASE_NOUN)
            : fallback;
        String nextBase = entry.gloss().isBlank() ? base.base() : entry.gloss();
        String nextFamily = entry.family().isBlank() ? base.family() : entry.family();
        return base.withOverride(nextBase, nextFamily, entry.roleOr(base.role()));
    }

    public static Entry find(String exactKey, String searchable) {
        Entry exact = EXACT.get(normalizeKey(exactKey));
        if (exact != null) return exact;
        String haystack = " " + cleanTokens(searchable) + " ";
        for (Map.Entry<String, Entry> token : TOKENS.entrySet()) {
            if (haystack.contains(" " + token.getKey() + " ")) {
                return token.getValue();
            }
        }
        return null;
    }

    public static List<String> encodeForSync() {
        List<String> out = new ArrayList<>(EXACT.size() + TOKENS.size());
        for (Entry entry : EXACT.values()) out.add(entry.encode());
        for (Entry entry : TOKENS.values()) out.add(entry.encode());
        return out;
    }

    public static void replaceFromSync(List<String> encoded) {
        Map<String, Entry> exact = new HashMap<>();
        Map<String, Entry> tokens = new HashMap<>();
        if (encoded != null) {
            for (String line : encoded) {
                Entry entry = Entry.decode(line);
                if (entry == null) continue;
                if (entry.token()) tokens.put(cleanTokens(entry.key()), entry);
                else exact.put(normalizeKey(entry.key()), entry);
            }
        }
        EXACT = Map.copyOf(exact);
        TOKENS = Map.copyOf(tokens);
    }

    private static JsonArray singleConceptArray(JsonObject obj) {
        JsonArray array = new JsonArray();
        array.add(obj);
        return array;
    }

    private static Entry entryFrom(JsonObject concept) {
        String gloss = optionalString(concept, "gloss");
        String family = optionalString(concept, "family");
        ConceptRole role = parseRole(optionalString(concept, "role"));
        return new Entry("", gloss, family, role, false);
    }

    private static void addString(JsonObject obj, String field, Entry entry,
                                  Map<String, Entry> map, boolean token) {
        if (!obj.has(field)) return;
        String key = GsonHelper.getAsString(obj, field);
        map.put(token ? cleanTokens(key) : normalizeKey(key), entry.withKey(key, token));
    }

    private static void addArray(JsonObject obj, String field, Entry entry,
                                 Map<String, Entry> map, boolean token) {
        if (!obj.has(field)) return;
        for (JsonElement element : GsonHelper.getAsJsonArray(obj, field)) {
            String key = element.getAsString();
            map.put(token ? cleanTokens(key) : normalizeKey(key), entry.withKey(key, token));
        }
    }

    private static String optionalString(JsonObject obj, String field) {
        return obj.has(field) && !obj.get(field).isJsonNull()
            ? GsonHelper.getAsString(obj, field).trim()
            : "";
    }

    private static ConceptRole parseRole(String value) {
        if (value == null || value.isBlank()) return null;
        String key = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return ConceptRole.valueOf(key);
        } catch (IllegalArgumentException ex) {
            BannerboundCore.LOGGER.warn("Unknown language concept role '{}'", value);
            return null;
        }
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String cleanTokens(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", " ")
            .trim()
            .replaceAll("\\s+", " ");
    }

    private static String cleanField(String value) {
        return value == null ? "" : value.replace('\t', ' ').trim();
    }

    public record Entry(String key, String gloss, String family, ConceptRole role, boolean token) {
        public Entry {
            key = cleanField(key);
            gloss = cleanField(gloss);
            family = cleanField(family);
        }

        Entry withKey(String key, boolean token) {
            return new Entry(key, gloss, family, role, token);
        }

        String base(String fallback) {
            if (!family.isBlank()) return family;
            if (!gloss.isBlank()) return gloss;
            return fallback == null || fallback.isBlank() ? "concept" : fallback;
        }

        ConceptRole roleOr(ConceptRole fallback) {
            return role == null ? fallback : role;
        }

        String encode() {
            return (token ? "T" : "E") + "\t" + key + "\t" + gloss + "\t" + family + "\t"
                + (role == null ? "" : role.name());
        }

        static Entry decode(String line) {
            if (line == null || line.isBlank()) return null;
            String[] parts = line.split("\t", -1);
            if (parts.length < 5) return null;
            ConceptRole role = parseRole(parts[4]);
            return new Entry(parts[1], parts[2], parts[3], role, "T".equals(parts[0]));
        }
    }
}
