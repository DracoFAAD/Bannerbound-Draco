package com.bannerbound.core.language;

import java.util.Locale;
import java.util.Random;

import com.bannerbound.core.api.settlement.Era;

/**
 * Deterministic language generator used by both server labels and client-side item names.
 *
 * <p>This is intentionally self-contained: no lang-file mutation, no network calls, and no
 * Minecraft registries. Callers supply a normalized concept id/gloss and this class supplies
 * repeatable historical/register forms for that settlement seed.
 */
public final class LanguageProfile {
    private static final String[] VOWEL_SETS = {
        "a i u", "a e i o", "a e i o u", "a i o", "e i a", "a e u"
    };
    private static final String[] CONSONANT_SETS = {
        "k t m n s l r p",
        "b d g m n l r s",
        "p t k f s m n r",
        "v z r l n m d g",
        "ch sh t k n m l s",
        "h w y n l r t s"
    };

    private final long seed;
    private final String[] vowels;
    private final String[] consonants;
    private final String prefix;
    private final String suffix;
    private final boolean prefersPrefix;
    private final int compoundStyle;
    private final int modifierStyle;

    private LanguageProfile(long seed) {
        this.seed = seed;
        Random rng = new Random(mix(seed, "profile"));
        this.vowels = VOWEL_SETS[rng.nextInt(VOWEL_SETS.length)].split(" ");
        this.consonants = CONSONANT_SETS[rng.nextInt(CONSONANT_SETS.length)].split(" ");
        this.prefix = affix("prefix", true);
        this.suffix = affix("suffix", false);
        this.prefersPrefix = rng.nextBoolean();
        this.compoundStyle = rng.nextInt(5);
        this.modifierStyle = rng.nextInt(4);
    }

    public static LanguageProfile of(long seed) {
        return new LanguageProfile(seed);
    }

    public String evolveWord(String base, Era era, ConceptRole role) {
        return formsForWord(base, era, role, "compat:" + base).defaultWord(era, role);
    }

    public String evolveName(String base, Era era) {
        return formsForName(base, era, "name:" + base).defaultName(era);
    }

    public RegisterForms formsForWord(String base, Era era, ConceptRole role, String salt) {
        ConceptRole actual = role == null ? ConceptRole.BASE_NOUN : role;
        String historical = historicalForm(base, era, false, actual, salt);
        String archaic = historicalForm(base, archaicAgeFor(era), false, actual, salt + ":archaic");
        String spoken = spoken(historical, era);
        String written = written(historical, era);
        String formal = formal(written, era, actual);
        String common = common(spoken, written, era, actual);
        String technical = technical(common, written, era, actual, salt);
        String handle = handle(common, written, era, salt);
        String fallback = historical + archaic + normalized(base);
        return new RegisterForms(
            repair(spoken, fallback, era, false),
            repair(written, fallback, era, false),
            repair(formal, fallback, era, false),
            repair(common, fallback, era, false),
            repair(archaic, fallback, era, false),
            repair(technical, fallback, era, false),
            repair(handle, fallback, era, era.ordinal() >= Era.MODERN.ordinal())
        );
    }

    public RegisterForms formsForConcept(LanguageConcept concept, Era era) {
        LanguageConcept actual = concept == null
            ? new LanguageConcept("unknown", "concept", ConceptRole.BASE_NOUN)
            : concept;
        String historical = historicalConceptForm(actual, era);
        String archaic = historicalConceptForm(actual, archaicAgeFor(era));
        String spoken = spoken(historical, era);
        String written = written(historical, era);
        String formal = formal(written, era, actual.role());
        String common = common(spoken, written, era, actual.role());
        String technical = technical(common, written, era, actual.role(), conceptSalt(actual));
        String handle = handle(common, written, era, conceptSalt(actual));
        String fallback = historical + archaic + normalized(actual.base());
        return new RegisterForms(
            repair(spoken, fallback, era, false),
            repair(written, fallback, era, false),
            repair(formal, fallback, era, false),
            repair(common, fallback, era, false),
            repair(archaic, fallback, era, false),
            repair(technical, fallback, era, false),
            repair(handle, fallback, era, era.ordinal() >= Era.MODERN.ordinal())
        );
    }

    public RegisterForms formsForName(String base, Era era, String salt) {
        String historical = historicalForm(base, era, true, ConceptRole.BASE_NOUN, salt);
        String archaic = historicalForm(base, archaicAgeFor(era), true, ConceptRole.BASE_NOUN, salt + ":archaic");
        String spoken = capitalize(spoken(historical, era));
        String written = capitalize(written(historical, era));
        String formal = capitalize(formal(written, era, ConceptRole.FAMILY_NAME));
        String common = capitalize(shortName(spoken, written, era));
        String technical = capitalize(technical(common, written, era, ConceptRole.FAMILY_NAME, salt));
        String handle = capitalize(handle(common, written, era, salt));
        String fallback = historical + archaic + normalized(base);
        return new RegisterForms(
            repair(spoken, fallback, era, true),
            repair(written, fallback, era, true),
            repair(formal, fallback, era, true),
            repair(common, fallback, era, true),
            repair(capitalize(archaic), fallback, era, true),
            repair(technical, fallback, era, true),
            repair(handle, fallback, era, era.ordinal() >= Era.MODERN.ordinal())
        );
    }

    /** Earned-surname forms. Shares the concept's ancestral stem with the lexicon's word for that
     *  concept — so a hunter's surname reads as the same word family as the "Hunter" job entry —
     *  while {@code variation} (e.g. the citizen's UUID) perturbs ONLY the family-name ending.
     *  Every same-profession surname therefore shares a stem but differs at the tail. */
    public RegisterForms formsForFamilyName(LanguageConcept concept, Era era, String variation) {
        LanguageConcept actual = concept == null
            ? new LanguageConcept("unknown", "concept", ConceptRole.FAMILY_NAME)
            : concept;
        String v = variation == null ? "" : variation;
        String historical = familyNameForm(actual, era, v);
        String archaic = familyNameForm(actual, archaicAgeFor(era), v + ":archaic");
        String spoken = capitalize(spoken(historical, era));
        String written = capitalize(written(historical, era));
        String formal = capitalize(formal(written, era, ConceptRole.FAMILY_NAME));
        String common = capitalize(shortName(spoken, written, era));
        String technical = capitalize(technical(common, written, era, ConceptRole.FAMILY_NAME, v));
        String handle = capitalize(handle(common, written, era, v));
        String fallback = historical + archaic + normalized(actual.base());
        return new RegisterForms(
            repair(spoken, fallback, era, true),
            repair(written, fallback, era, true),
            repair(formal, fallback, era, true),
            repair(common, fallback, era, true),
            repair(capitalize(archaic), fallback, era, true),
            repair(technical, fallback, era, true),
            repair(handle, fallback, era, era.ordinal() >= Era.MODERN.ordinal())
        );
    }

    private String familyNameForm(LanguageConcept concept, Era era, String variation) {
        String root = familyNameTail(conceptCore(concept), variation);
        String salt = "surname:" + variation;
        for (Era step : Era.values()) {
            root = evolveStep(root, step, salt);
            if (step == era) break;
        }
        return capitalize(root);
    }

    /** Appends the language's family-name marker ("in", per {@link #applyMorphology}) to the shared
     *  stem, varying the join per citizen. Always suffixes — the recognizable stem leads and only
     *  the tail changes between same-profession families. */
    private String familyNameTail(String root, String variation) {
        if (root == null || root.isBlank()) return "in";
        Random rng = new Random(mix(seed, "surname-tail:" + variation));
        String marker = "in";
        return switch (rng.nextInt(4)) {
            case 0 -> root + marker;                                              // …in
            case 1 -> root + vowels[rng.nextInt(vowels.length)] + marker;         // …ain / …uin
            case 2 -> root + "-" + marker;                                        // …-in
            default -> root + marker + consonants[rng.nextInt(consonants.length)]; // …ins / …int
        };
    }

    private String historicalForm(String base, Era era, boolean name, ConceptRole role, String salt) {
        String root = rootFor(base, name, salt);
        root = applyMorphology(root, role, salt);
        for (Era step : Era.values()) {
            root = evolveStep(root, step, salt);
            if (step == era) break;
        }
        return name ? capitalize(root) : root;
    }

    private String historicalConceptForm(LanguageConcept concept, Era era) {
        String root = ancestralConceptStem(concept);
        String salt = conceptSalt(concept);
        for (Era step : Era.values()) {
            root = evolveStep(root, step, salt);
            if (step == era) break;
        }
        return root;
    }

    private String ancestralConceptStem(LanguageConcept concept) {
        String family = normalizedOptional(concept.family());
        if (family.isBlank()) family = normalized(concept.base());
        String kind = normalizedOptional(concept.kind());
        if (samePart(kind, family)) kind = "";
        return applyMorphology(conceptCore(concept), concept.role(),
            "concept-morph:" + concept.role().name() + ":" + family + ":" + kind);
    }

    /** The pre-morphology stem of a concept — its family (plus kind and modifier) roots combined,
     *  before any role-specific affix. Both the lexicon's role forms and earned surnames build on
     *  this, so a citizen's family name grows from the very same stem as the job word the lexicon
     *  shows; only the trailing role affix differs. */
    private String conceptCore(LanguageConcept concept) {
        String family = normalizedOptional(concept.family());
        if (family.isBlank()) family = normalized(concept.base());
        String kind = normalizedOptional(concept.kind());
        String modifier = normalizedOptional(concept.modifier());
        if (samePart(kind, family)) kind = "";
        if (samePart(modifier, family) || samePart(modifier, kind)) modifier = "";

        String familyRoot = rootFor(family, false, "family:" + family);
        String kindRoot = kind.isBlank() ? "" : morphemeFor(kind, "kind:" + kind);
        String modifierRoot = modifier.isBlank() ? "" : morphemeFor(modifier, "modifier:" + modifier);

        String core = combineFamilyAndKind(familyRoot, kindRoot, conceptSalt(concept));
        if (!modifierRoot.isBlank()) {
            core = combineModifier(modifierRoot, core, conceptSalt(concept));
        }
        return core;
    }

    private String morphemeFor(String base, String salt) {
        String root = cleanup(rootFor(base, false, salt));
        int max = 3 + (int) Math.floorMod(mix(seed, salt + ":len"), 3);
        if (root.length() <= max) return root;
        return cleanup(root.substring(0, max));
    }

    private String combineFamilyAndKind(String familyRoot, String kindRoot, String salt) {
        if (kindRoot == null || kindRoot.isBlank()) return familyRoot;
        String link = vowels[Math.floorMod((int) mix(seed, salt + ":link"), vowels.length)];
        return switch (compoundStyle) {
            case 0 -> familyRoot + kindRoot;          // iron-ingot languages read material first
            case 1 -> kindRoot + familyRoot;          // head-first compounds
            case 2 -> familyRoot + "-" + kindRoot;    // marked prestige compounds
            case 3 -> familyRoot + link + kindRoot;   // fused with a linking vowel
            default -> kindRoot + link + familyRoot;
        };
    }

    private String combineModifier(String modifierRoot, String core, String salt) {
        String link = vowels[Math.floorMod((int) mix(seed, salt + ":modifier-link"), vowels.length)];
        return switch (modifierStyle) {
            case 0 -> modifierRoot + core;        // adjective before noun
            case 1 -> core + modifierRoot;        // adjective after noun
            case 2 -> modifierRoot + "-" + core;  // visibly marked adjective
            default -> core + link + modifierRoot;
        };
    }

    private String rootFor(String base, boolean name, String salt) {
        String norm = normalized(base);
        Random rng = new Random(mix(seed, "root:" + norm + ":" + salt));
        int syllables = name ? 3 : 2 + rng.nextInt(2);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < syllables; i++) {
            out.append(syllable(norm + ":" + i + ":" + salt, i == 0));
        }
        return out.toString();
    }

    /** A stable 2-char affix derived from a syllable. {@link #syllable} can return a single vowel
     *  when the optional leading consonant is dropped (non-initial syllables), so pad to length 2
     *  before slicing â€” otherwise {@code substring(0, 2)} runs off the end and crashes. */
    private String affix(String salt, boolean initial) {
        String s = syllable(salt, initial);
        while (s.length() < 2) s = s + vowels[0];
        return s.substring(0, 2);
    }

    private String syllable(String salt, boolean initial) {
        Random rng = new Random(mix(seed, "syllable:" + salt));
        String c = consonants[rng.nextInt(consonants.length)];
        String v = vowels[rng.nextInt(vowels.length)];
        if (!initial && rng.nextInt(5) == 0) c = "";
        if (rng.nextInt(6) == 0) {
            return c + v + consonants[rng.nextInt(consonants.length)];
        }
        return c + v;
    }

    private String applyMorphology(String root, ConceptRole role, String salt) {
        ConceptRole actual = role == null ? ConceptRole.BASE_NOUN : role;
        if (actual == ConceptRole.BASE_NOUN || actual == ConceptRole.ITEM || actual == ConceptRole.ENTITY) {
            return root;
        }
        Random rng = new Random(mix(seed, "morph:" + actual.name() + ":" + salt));
        String marker = switch (actual) {
            case PROFESSION -> "ar";
            case MATERIAL -> "en";
            case FOOD -> "um";
            case TOOL -> "ik";
            case PLACE, BUILDING -> "an";
            case ACTION -> "ai";
            case AUTHORITY -> "or";
            case ABSTRACT -> "el";
            case FAMILY_NAME -> "in";
            default -> suffix;
        };
        int style = rng.nextInt(5);
        return switch (style) {
            case 0 -> marker + root;
            case 1 -> root + marker;
            case 2 -> root.length() > 3 ? root.substring(0, 2) + marker + root.substring(2) : root + marker;
            case 3 -> root + "-" + marker;
            // Gemination of the final char. Guard the empty-root case so charAt never runs off the
            // end (same failure class as the prefix/suffix substring crash).
            default -> root.isEmpty() ? marker : root + root.charAt(root.length() - 1) + marker;
        };
    }

    private String evolveStep(String value, Era era, String salt) {
        String out = value.toLowerCase(Locale.ROOT);
        switch (era) {
            case ANCIENT -> {
                if (out.length() < 7) out = out + suffix;
            }
            case CLASSICAL -> {
                // Classical sits between the raw Ancient forms and Medieval's consonant shifts:
                // lightly soften the word and lengthen short stems, but keep the older shape.
                out = soften(out);
                if (out.length() < 7) out = out + suffix;
            }
            case MEDIEVAL -> {
                out = out.replace("sh", "s").replace("ch", "k");
                if (mix(seed, salt + ":medieval") % 3 == 0) out = out.replaceFirst("([aeiou])", "$1h");
            }
            case RENAISSANCE -> {
                out = soften(out).replace("-", "");
                if (prefersPrefix && out.length() > 5) out = prefix + out.substring(1);
            }
            case INDUSTRIAL -> {
                out = compact(soften(out), 1);
                out = standardizeEnding(out);
            }
            case DIESEL -> {
                out = compact(out.replace("kh", "k").replace("th", "t"), 1);
            }
            case ATOMIC -> {
                out = broadcastSmooth(out);
            }
            case MODERN -> {
                out = compact(broadcastSmooth(out), 2);
                out = standardizeEnding(out);
            }
            case FUTURE -> {
                out = compact(out, 2);
                if (Math.floorMod(mix(seed, salt + ":revive"), 5) == 0) out = revive(out);
            }
        }
        return cleanup(out);
    }

    private String spoken(String value, Era era) {
        String out = value.toLowerCase(Locale.ROOT);
        if (era.ordinal() >= Era.MEDIEVAL.ordinal()) out = compact(out, 1);
        if (era.ordinal() >= Era.INDUSTRIAL.ordinal()) out = broadcastSmooth(out);
        if (era.ordinal() >= Era.MODERN.ordinal()) out = compact(out, 1);
        return cleanup(out);
    }

    private String written(String value, Era era) {
        String out = value.toLowerCase(Locale.ROOT);
        if (era.ordinal() >= Era.RENAISSANCE.ordinal()) out = standardizeEnding(out);
        return cleanup(out);
    }

    private String formal(String value, Era era, ConceptRole role) {
        String out = value.toLowerCase(Locale.ROOT);
        if (era.ordinal() <= Era.MEDIEVAL.ordinal()) return out;
        if (role == ConceptRole.AUTHORITY || role == ConceptRole.BUILDING || role == ConceptRole.PLACE) {
            out = prefersPrefix ? prefix + out : out + suffix;
        }
        if (era.ordinal() >= Era.RENAISSANCE.ordinal()) out = standardizeEnding(out);
        return cleanup(out);
    }

    private String common(String spoken, String written, Era era, ConceptRole role) {
        String out = era.ordinal() >= Era.INDUSTRIAL.ordinal() ? spoken : written;
        if (role == ConceptRole.PROFESSION && out.length() < 5) out = out + "ar";
        if (era.ordinal() >= Era.MODERN.ordinal()) out = compact(out, 1);
        return cleanup(out);
    }

    private String technical(String common, String written, Era era, ConceptRole role, String salt) {
        if (era.ordinal() < Era.INDUSTRIAL.ordinal()) return written;
        String out = compact(common, 1);
        if ((role == ConceptRole.TOOL || role == ConceptRole.MATERIAL) && out.length() > 5) {
            out = out.substring(0, Math.min(5, out.length()));
        }
        if (Math.floorMod(mix(seed, salt + ":tech"), 4) == 0) out = out + digitTail(salt);
        return cleanup(out);
    }

    private String handle(String common, String written, Era era, String salt) {
        if (era.ordinal() < Era.MODERN.ordinal()) return common;
        String out = compact(common, era == Era.FUTURE ? 3 : 2);
        if (out.length() > 6) out = out.substring(0, 6);
        if (out.length() < 3) out = out + digitTail(salt);
        return cleanup(out);
    }

    private String shortName(String spoken, String written, Era era) {
        String out = era.ordinal() >= Era.INDUSTRIAL.ordinal() ? spoken : written;
        if (era.ordinal() >= Era.MODERN.ordinal() && out.length() > 5) {
            out = out.substring(0, Math.max(4, Math.min(6, out.length())));
        }
        return cleanup(out);
    }

    private String soften(String value) {
        return value.replace("k", "g").replace("t", "d").replace("p", "b");
    }

    private String broadcastSmooth(String value) {
        return value.replace("aa", "a").replace("ii", "i").replace("uu", "u")
            .replace("kh", "k").replace("sh", "s").replace("zh", "z");
    }

    private String standardizeEnding(String value) {
        if (value.length() < 4) return value;
        char last = value.charAt(value.length() - 1);
        if ("aeiou".indexOf(last) >= 0) return value;
        return value + vowels[Math.floorMod((int) mix(seed, "std:" + value), vowels.length)];
    }

    private String compact(String value, int passes) {
        String out = value;
        for (int i = 0; i < passes; i++) {
            out = out.replace("-", "");
            out = out.replaceAll("([aeiou])[aeiou]", "$1");
            if (out.length() > 6) out = out.replaceFirst("([bcdfghjklmnpqrstvwxyz])[aeiou]([bcdfghjklmnpqrstvwxyz])", "$1$2");
        }
        return cleanup(out);
    }

    private String revive(String value) {
        return value.length() < 5 ? value + suffix : value.substring(0, value.length() - 1) + "a";
    }

    private String repair(String value, String fallback, Era era, boolean name) {
        String out = cleanup(value);
        int min = era.ordinal() >= Era.MODERN.ordinal() ? 3 : 4;
        if (out.length() < min) out = cleanup(out + syllable("repair:" + fallback, false));
        if (out.length() > 16) out = out.substring(0, 16);
        return name ? capitalize(out) : out;
    }

    private String digitTail(String salt) {
        return Integer.toString((int) Math.floorMod(mix(seed, "digit:" + salt), 10));
    }

    private static Era archaicAgeFor(Era era) {
        if (era.ordinal() <= Era.MEDIEVAL.ordinal()) return Era.ANCIENT;
        if (era.ordinal() <= Era.INDUSTRIAL.ordinal()) return Era.MEDIEVAL;
        return Era.RENAISSANCE;
    }

    private static String normalized(String value) {
        if (value == null) return "concept";
        String out = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        return out.isBlank() ? "concept" : out.replace(" ", "_");
    }

    private static String normalizedOptional(String value) {
        if (value == null || value.isBlank()) return "";
        return normalized(value);
    }

    private static boolean samePart(String a, String b) {
        return a != null && b != null && !a.isBlank() && a.equals(b);
    }

    private static String conceptSalt(LanguageConcept concept) {
        if (concept == null) return "concept:unknown";
        return "concept:" + normalized(concept.family()) + ":"
            + normalizedOptional(concept.kind()) + ":"
            + normalizedOptional(concept.modifier());
    }

    private static String cleanup(String value) {
        String out = value == null ? "" : value.toLowerCase(Locale.ROOT);
        out = out.replaceAll("[^a-z0-9-]", "");
        out = out.replaceAll("([bcdfghjklmnpqrstvwxyz])\\1\\1+", "$1$1");
        out = out.replaceAll("([aeiou])\\1+", "$1");
        out = out.replaceAll("^-+|-+$", "");
        return out.isBlank() ? "na" : out;
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) return "Na";
        String lower = value.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public static long mix(long seed, String salt) {
        long h = seed ^ 0x9E3779B97F4A7C15L;
        String s = salt == null ? "" : salt;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0xBF58476D1CE4E5B9L;
            h ^= (h >>> 27);
        }
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        return h;
    }
}
