package com.bannerbound.core.language;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

/** Normalizes registry ids, job ids, and fallback English labels into language concepts. */
public final class LanguageConceptResolver {
    private static final Map<String, String> JOB_BASES = Map.ofEntries(
        Map.entry("foresters_log", "wood"),
        Map.entry("fishers_creel", "fish"),
        Map.entry("foragers_basket", "forage"),
        Map.entry("farmers_granary", "grain"),
        Map.entry("diggers_slab", "stone"),
        Map.entry("miners_claim", "ore"),
        Map.entry("hunters_camp", "hunt"),
        Map.entry("herders_pen", "herd"),
        Map.entry("builder", "wall"),
        Map.entry("stocker", "haul"),
        Map.entry("crafter", "craft"),
        Map.entry("fletchery", "arrow"),
        Map.entry("carpentry", "wood"),
        Map.entry("general_crafts", "craft"),
        Map.entry("mixed", "craft")
    );

    private static final Set<String> WOOD_SPECIES = Set.of(
        "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
        "pale_oak", "bamboo", "crimson", "warped", "fir", "pine", "cedar", "willow",
        "maple", "mahogany", "ebony"
    );
    private static final Set<String> WOOD_KINDS = Set.of(
        "log", "wood", "plank", "planks", "stick", "sticks", "slab", "stair", "stairs",
        "fence", "gate", "door", "trapdoor", "sign", "button", "boat", "chest", "barrel",
        "ladder"
    );
    private static final Set<String> STONE_TYPES = Set.of(
        "stone", "cobblestone", "deepslate", "andesite", "diorite", "granite", "tuff",
        "calcite", "basalt", "blackstone", "sandstone", "limestone", "marble", "slate",
        "flint", "obsidian"
    );
    private static final Set<String> STONE_KINDS = Set.of(
        "stone", "cobblestone", "brick", "bricks", "slab", "stair", "stairs", "wall", "button",
        "tile", "tiles", "cobble", "shard"
    );
    private static final Set<String> METALS = Set.of(
        "copper", "iron", "gold", "netherite", "tin", "bronze", "steel", "silver",
        "lead", "zinc", "brass", "nickel"
    );
    private static final Set<String> MINERALS = Set.of(
        "coal", "diamond", "emerald", "lapis", "redstone", "quartz", "amethyst", "clay",
        "charcoal"
    );
    private static final Set<String> MATERIAL_KINDS = Set.of(
        "ore", "ingot", "nugget", "gem", "dust", "block", "raw", "shard", "brick",
        "bricks", "charcoal"
    );
    private static final Set<String> TOOL_KINDS = Set.of(
        "pickaxe", "axe", "shovel", "hoe", "sword", "spear", "bow", "rod", "knife",
        "hammer", "saw", "chisel", "shear", "shears"
    );
    private static final Set<String> FOOD_WORDS = Set.of(
        "fish", "cod", "salmon", "bread", "wheat", "grain", "apple", "berries", "berry",
        "meat", "beef", "porkchop", "mutton", "chicken", "egg", "carrot", "potato",
        "beetroot", "melon", "pumpkin"
    );

    private LanguageConceptResolver() {
    }

    public static LanguageConcept forRegistry(ResourceLocation id, String fallback, ConceptRole role) {
        return forRegistry(id, fallback, role, List.of());
    }

    public static LanguageConcept forRegistry(ResourceLocation id, String fallback, ConceptRole role,
                                              Iterable<String> tagIds) {
        String key = id == null ? "unknown" : id.toString();
        ConceptParts parts = conceptParts(id == null ? "" : id.getPath(), fallback, role, tagIds);
        String searchable = (id == null ? "" : id.getPath()) + " " + (fallback == null ? "" : fallback)
            + " " + tagText(tagIds);
        LanguageConcept fallbackConcept = new LanguageConcept(
            key, parts.base(), parts.role(), parts.family(), parts.modifier(), parts.kind());
        return LanguageConceptOverrideLoader.applyOverride(fallbackConcept, key, searchable);
    }

    public static LanguageConcept forJob(String jobTypeId, boolean quarryUnlocked) {
        String id = jobTypeId == null || jobTypeId.isBlank() ? "unemployed" : jobTypeId;
        if ("diggers_slab".equals(id) && quarryUnlocked) {
            return LanguageConceptOverrideLoader.applyOverride(
                new LanguageConcept("job:quarryworker", "quarryworker", ConceptRole.PROFESSION,
                    "quarry", "", "worker"),
                "job:quarryworker",
                "diggers slab quarry quarryworker");
        }
        String base = JOB_BASES.getOrDefault(id, bestBase(id, id));
        return LanguageConceptOverrideLoader.applyOverride(
            new LanguageConcept("job:" + id, fallbackJobName(id, false), ConceptRole.PROFESSION,
                base, "", "worker"),
            "job:" + id,
            id + " " + base);
    }

    public static String fallbackJobName(String jobTypeId, boolean quarryUnlocked) {
        if (jobTypeId == null || jobTypeId.isBlank()) return "Unemployed";
        if ("diggers_slab".equals(jobTypeId) && quarryUnlocked) return "Quarryworker";
        return switch (jobTypeId) {
            case "foresters_log" -> "Forester";
            case "fishers_creel" -> "Fisher";
            case "foragers_basket" -> "Forager";
            case "farmers_granary" -> "Farmer";
            case "diggers_slab" -> "Digger";
            case "miners_claim" -> "Miner";
            case "hunters_camp" -> "Hunter";
            case "herders_pen" -> "Herder";
            case "builder" -> "Builder";
            case "stocker" -> "Stocker";
            case "crafter" -> "Crafter";
            default -> titleCase(bestBase(jobTypeId, jobTypeId));
        };
    }

    public static String surnameFallback(String concept, String jobTypeId) {
        String key = concept == null || concept.isBlank() ? jobTypeId : concept;
        if (key == null || key.isBlank()) return "Worker";
        return switch (key) {
            case "wood", "foresters_log" -> "Forester";
            case "fish", "fishers_creel" -> "Fisher";
            case "forage", "foragers_basket" -> "Forager";
            case "grain", "farmers_granary" -> "Farmer";
            case "stone", "diggers_slab" -> "Mason";
            case "ore", "miners_claim" -> "Miner";
            case "hunt", "hunters_camp" -> "Hunter";
            case "herd", "herders_pen" -> "Herder";
            case "wall", "builder" -> "Builder";
            case "haul", "stocker" -> "Stocker";
            case "arrow", "fletchery" -> "Fletcher";
            case "craft", "crafter", "general_crafts", "mixed" -> "Crafter";
            case "carpentry" -> "Woodworker";
            default -> titleCase(bestBase(key, key));
        };
    }

    public static String bestBase(String path, String fallback) {
        String fromFallback = cleanLabel(fallback);
        if (!fromFallback.isBlank() && !fromFallback.contains(".")) return simpleHead(fromFallback);
        String fromPath = cleanLabel(path);
        return fromPath.isBlank() ? "concept" : simpleHead(fromPath);
    }

    private static ConceptParts conceptParts(String path, String fallback, ConceptRole requestedRole,
                                             Iterable<String> tagIds) {
        List<String> tokens = tokens(path, fallback, tagIds);
        String simple = tokens.isEmpty() ? bestBase(path, fallback) : tokens.get(tokens.size() - 1);
        ConceptRole role = requestedRole == null || requestedRole == ConceptRole.ITEM
            ? guessRole(String.join(" ", tokens))
            : requestedRole;

        String tool = firstPresent(tokens, TOOL_KINDS);
        if (!tool.isBlank()) {
            String material = firstMaterial(tokens);
            return new ConceptParts(joinNonblank(material, tool), ConceptRole.TOOL,
                tool, material, "");
        }

        String woodKind = firstPresent(tokens, WOOD_KINDS);
        String woodSpecies = woodSpecies(tokens);
        if (!woodKind.isBlank() || !woodSpecies.isBlank()) {
            String kind = woodKind.isBlank() ? "wood" : singular(woodKind);
            return new ConceptParts(joinNonblank(woodSpecies, kind), ConceptRole.MATERIAL,
                "wood", woodSpecies, kind);
        }

        String material = firstMaterial(tokens);
        String materialKind = firstPresent(tokens, MATERIAL_KINDS);
        if (!material.isBlank()) {
            String kind = materialKind.isBlank() ? material : singular(materialKind);
            ConceptRole materialRole = FOOD_WORDS.contains(material) ? ConceptRole.FOOD : ConceptRole.MATERIAL;
            return new ConceptParts(joinNonblank(material, kind), materialRole, material, "", kind);
        }

        String stoneType = firstPresent(tokens, STONE_TYPES);
        String stoneKind = firstPresent(tokens, STONE_KINDS);
        if (!stoneType.isBlank() || !stoneKind.isBlank()) {
            String kind = stoneKind.isBlank() ? "stone" : singular(stoneKind);
            String modifier = stoneType.equals("stone") ? "" : stoneType;
            return new ConceptParts(joinNonblank(modifier, kind), ConceptRole.MATERIAL,
                "stone", modifier, kind);
        }

        String food = firstPresent(tokens, FOOD_WORDS);
        if (!food.isBlank()) {
            String family = foodFamily(food);
            return new ConceptParts(food, ConceptRole.FOOD, family, food.equals(family) ? "" : food, "food");
        }

        if (role == ConceptRole.ENTITY) {
            return new ConceptParts(bestBase(path, fallback), ConceptRole.ENTITY, bestBase(path, fallback), "", "creature");
        }

        String kind = simple.isBlank() ? "item" : singular(simple);
        return new ConceptParts(bestBase(path, fallback), role, kind, modifierBefore(tokens, kind), kind);
    }

    private static List<String> tokens(String path, String fallback, Iterable<String> tagIds) {
        String source = cleanLabel(path) + " " + cleanLabel(fallback) + " " + tagText(tagIds);
        source = source.trim();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String[] raw = source.isBlank() ? new String[0] : source.split(" ");
        for (int i = 0; i < raw.length; i++) {
            if (raw[i].isBlank()) continue;
            out.add(singular(raw[i]));
            if (i + 1 < raw.length) {
                String joined = raw[i] + "_" + raw[i + 1];
                if (WOOD_SPECIES.contains(joined)) {
                    out.add(joined);
                    i++;
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static String tagText(Iterable<String> tagIds) {
        if (tagIds == null) return "";
        StringBuilder out = new StringBuilder();
        for (String tag : tagIds) {
            if (tag == null || tag.isBlank()) continue;
            if (out.length() > 0) out.append(' ');
            int colon = tag.indexOf(':');
            out.append(colon >= 0 ? tag.substring(colon + 1) : tag);
        }
        return out.toString();
    }

    private static String firstPresent(List<String> tokens, Set<String> options) {
        for (String token : tokens) {
            String singular = singular(token);
            if (options.contains(token)) return token;
            if (options.contains(singular)) return singular;
        }
        return "";
    }

    private static String firstMaterial(List<String> tokens) {
        for (String token : tokens) {
            if (METALS.contains(token) || MINERALS.contains(token) || FOOD_WORDS.contains(token)) return token;
        }
        if (tokens.contains("raw")) {
            for (String token : tokens) {
                if (!"raw".equals(token)) return token;
            }
        }
        return "";
    }

    private static String woodSpecies(List<String> tokens) {
        for (String token : tokens) {
            if (WOOD_SPECIES.contains(token)) return token.replace('_', ' ');
        }
        return "";
    }

    private static String modifierBefore(List<String> tokens, String kind) {
        if (tokens.isEmpty() || kind == null || kind.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            if (token.equals(kind) || singular(token).equals(kind)) break;
            if (out.length() > 0) out.append(' ');
            out.append(token.replace('_', ' '));
        }
        return out.toString();
    }

    private static String foodFamily(String food) {
        return switch (food) {
            case "cod", "salmon", "fish" -> "fish";
            case "wheat", "grain", "bread" -> "grain";
            case "apple", "berries", "berry", "melon" -> "fruit";
            case "beef", "porkchop", "mutton", "chicken", "meat", "egg" -> "meat";
            case "carrot", "potato", "beetroot", "pumpkin" -> "vegetable";
            default -> food;
        };
    }

    private static String joinNonblank(String first, String second) {
        if (first == null || first.isBlank()) return second == null || second.isBlank() ? "item" : second;
        if (second == null || second.isBlank() || first.equals(second)) return first;
        return first + " " + second;
    }

    private static ConceptRole guessRole(String base) {
        String s = base == null ? "" : base.toLowerCase(Locale.ROOT);
        if (containsAny(s, "log", "wood", "stone", "ore", "iron", "copper", "clay", "brick")) {
            return ConceptRole.MATERIAL;
        }
        if (containsAny(s, "berry", "fish", "meat", "grain", "wheat", "bread", "apple")) {
            return ConceptRole.FOOD;
        }
        if (containsAny(s, "axe", "pickaxe", "shovel", "hoe", "rod", "bow", "sword", "spear")) {
            return ConceptRole.TOOL;
        }
        return ConceptRole.ITEM;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private static String cleanLabel(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
            .replaceAll("([a-z])([A-Z])", "$1 $2")
            .replaceAll("[^a-z0-9]+", " ")
            .replaceAll("\\b(c|item|items|block|blocks|entity|entities|minecraft|bannerbound|the|of|and|that|burn)\\b", " ")
            .trim()
            .replaceAll("\\s+", " ");
    }

    private static String simpleHead(String label) {
        if (label == null || label.isBlank()) return "concept";
        String[] parts = label.split(" ");
        if (parts.length == 1) return singular(parts[0]);
        String last = parts[parts.length - 1];
        String prev = parts[parts.length - 2];
        if (containsAny(last, "log", "plank", "ore", "ingot", "brick", "stone", "rod", "axe")) {
            return singular(prev + " " + last);
        }
        return singular(last);
    }

    private static String singular(String value) {
        String out = value == null ? "concept" : value.trim();
        if (out.endsWith("ves") && out.length() > 4) return out.substring(0, out.length() - 3) + "f";
        if (out.endsWith("ies") && out.length() > 4) return out.substring(0, out.length() - 3) + "y";
        if (out.endsWith("s") && out.length() > 3 && !out.endsWith("ss")) return out.substring(0, out.length() - 1);
        return out.isBlank() ? "concept" : out;
    }

    private static String titleCase(String value) {
        if (value == null || value.isBlank()) return "Worker";
        StringBuilder out = new StringBuilder();
        for (String part : value.replace('_', ' ').split(" ")) {
            if (part.isBlank()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) out.append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.length() == 0 ? "Worker" : out.toString();
    }

    private record ConceptParts(String base, ConceptRole role, String family, String modifier, String kind) {
    }
}
