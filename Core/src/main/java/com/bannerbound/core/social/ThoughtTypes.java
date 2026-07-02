package com.bannerbound.core.social;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceLocation;

/**
 * The registry of all {@link ThoughtType}s — Core's built-in {@link ThoughtKind} constants plus any
 * an expansion registers. Keyed by {@link ThoughtType#id()}; this is what {@link Thought} resolves a
 * saved id back through, and the public seam an addon uses to publish a new thought:
 *
 * <pre>
 * public static final ThoughtType MY = ThoughtTypes.register(
 *     ThoughtType.builder(ResourceLocation.fromNamespaceAndPath("mymod", "my_thought"))
 *         .label("mymod.thought.my_thought").modifier(6).duration(4800, 7200).build());
 * // …later: citizen.getThoughts().add(MY, null, now, rng);
 * </pre>
 */
public final class ThoughtTypes {
    private ThoughtTypes() {}

    private static final Map<ResourceLocation, ThoughtType> REGISTRY = new ConcurrentHashMap<>();

    static {
        // Force the built-in enum to initialise so its constants self-register before any lookup
        // (e.g. a citizen load that resolves a saved id is the first reference to the thought system).
        ThoughtKind.bootstrap();
    }

    /** Register {@code type} under its id (first registration wins; duplicates are ignored). Returns
     *  the argument so it can be assigned to a {@code static final} field in one line. */
    public static <T extends ThoughtType> T register(T type) {
        REGISTRY.putIfAbsent(type.id(), type);
        return type;
    }

    /** The type registered under {@code id}, or {@code null} if none. */
    @Nullable
    public static ThoughtType byId(ResourceLocation id) {
        return id == null ? null : REGISTRY.get(id);
    }

    /** The type registered under the string form of an id, or {@code null} (unparseable / unknown). */
    @Nullable
    public static ThoughtType byId(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : REGISTRY.get(rl);
    }
}
