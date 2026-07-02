package com.bannerbound.core.crisis;

import java.util.List;

/** Data-pack definition for a scripted crisis. */
public record CrisisDefinition(
    String id,
    String category,
    String title,
    String headline,
    String body,
    String prompt,
    String background,
    List<CrisisDefinition.ArtLayer> backgroundLayers,
    String chronicleEntry,
    String startSound,
    int priority,
    Trigger trigger,
    CompletionEffects completionEffects,
    List<CrisisChoice> choices
) {
    public CrisisDefinition {
        id = id == null ? "" : id;
        category = category == null ? "" : category;
        title = title == null ? "" : title;
        headline = headline == null ? "" : headline;
        body = body == null ? "" : body;
        prompt = prompt == null ? "" : prompt;
        background = background == null ? "" : background;
        backgroundLayers = backgroundLayers == null ? List.of() : List.copyOf(backgroundLayers);
        chronicleEntry = chronicleEntry == null ? "" : chronicleEntry;
        startSound = startSound == null ? "" : startSound;
        trigger = trigger == null ? Trigger.manual() : trigger;
        completionEffects = completionEffects == null ? CompletionEffects.none() : completionEffects;
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    public CrisisChoice choice(String choiceId) {
        for (CrisisChoice choice : choices) {
            if (choice.id().equals(choiceId)) return choice;
        }
        return null;
    }

    public record Trigger(String type, String target, String government, String source,
                          String biome, String comparison, int count, double rate,
                          int durationTicks, int delayTicks, boolean requiresGovernment) {
        public Trigger {
            type = type == null ? "" : type;
            target = target == null ? "" : target;
            government = government == null ? "" : government;
            source = source == null ? "" : source;
            biome = biome == null ? "" : biome;
            comparison = comparison == null ? "" : comparison;
            durationTicks = Math.max(0, durationTicks);
            delayTicks = Math.max(0, delayTicks);
        }

        public static Trigger manual() {
            return new Trigger("manual", "", "", "", "", "", 1, 0.0, 0, 0, false);
        }
    }

    public record ArtLayer(String texture, float parallax, float driftX, float driftY,
                           float scale, float opacity, int revealDelayMs,
                           int revealDurationMs) {
        public ArtLayer {
            texture = texture == null ? "" : texture;
            parallax = Math.max(-4.0f, Math.min(4.0f, parallax));
            driftX = Math.max(-80.0f, Math.min(80.0f, driftX));
            driftY = Math.max(-80.0f, Math.min(80.0f, driftY));
            scale = Math.max(0.25f, Math.min(3.0f, scale <= 0.0f ? 1.0f : scale));
            opacity = Math.max(0.0f, Math.min(1.0f, opacity));
            revealDelayMs = Math.max(0, revealDelayMs);
            revealDurationMs = Math.max(120, revealDurationMs);
        }
    }

    public record CompletionEffects(int complianceDelta, int resentmentDelta) {
        public static CompletionEffects none() {
            return new CompletionEffects(0, 0);
        }

        public boolean isEmpty() {
            return complianceDelta == 0 && resentmentDelta == 0;
        }
    }
}
