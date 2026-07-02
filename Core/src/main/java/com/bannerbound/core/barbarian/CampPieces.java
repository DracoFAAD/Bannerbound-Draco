package com.bannerbound.core.barbarian;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/**
 * Discovers the authored camp building pieces for each {@link CampType} by scanning the datapack
 * folder {@code data/<ns>/structures/<type>/*.nbt} (any namespace — addons can contribute), and
 * classifies each by filename into a {@link Role}. Results are cached per type and rebuilt on
 * {@code /reload}.
 *
 * <p>Returned ids are {@code StructureTemplateManager} ids (the path with the {@code structures/}
 * prefix and {@code .nbt} suffix stripped), so {@code level.getStructureManager().get(id)} resolves.
 */
public final class CampPieces {
    public enum Role { CHIEF, STOCKPILE, HUT }

    public record Piece(ResourceLocation id, Role role) {
    }

    private static final Map<CampType, List<Piece>> CACHE = new ConcurrentHashMap<>();

    private CampPieces() {
    }

    /** All pieces authored for a type. Until a type has its own builds, every type reuses the TRIBE
     *  set (the first authored). Empty only if even TRIBE has none (→ caller's procedural fallback). */
    public static List<Piece> forType(MinecraftServer server, CampType type) {
        List<Piece> own = CACHE.computeIfAbsent(type, t -> scan(server, t));
        if (own.isEmpty() && type != CampType.TRIBE) {
            return forType(server, CampType.TRIBE);
        }
        return own;
    }

    public static List<Piece> ofRole(MinecraftServer server, CampType type, Role role) {
        List<Piece> out = new ArrayList<>();
        for (Piece p : forType(server, type)) {
            if (p.role() == role) out.add(p);
        }
        return out;
    }

    /** Drop the cache so newly-added .nbt files are picked up after a {@code /reload}. */
    public static void clearCache() {
        CACHE.clear();
    }

    private static List<Piece> scan(MinecraftServer server, CampType type) {
        // 1.21 folder is "structure" (SINGULAR) — vanilla ships data/minecraft/structure/…, and
        // StructureTemplateManager.get() resolves ids against that folder.
        String dir = "structure/" + type.name().toLowerCase(Locale.ROOT);
        List<Piece> out = new ArrayList<>();
        Map<ResourceLocation, ?> found =
            server.getResourceManager().listResources(dir, loc -> loc.getPath().endsWith(".nbt"));
        for (ResourceLocation full : found.keySet()) {
            String path = full.getPath(); // structure/<type>/<name>.nbt
            String id = path.substring("structure/".length(), path.length() - ".nbt".length());
            out.add(new Piece(ResourceLocation.fromNamespaceAndPath(full.getNamespace(), id),
                roleOf(path)));
        }
        return out;
    }

    private static Role roleOf(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.contains("chief")) return Role.CHIEF;
        if (p.contains("stockpile") || p.contains("store")) return Role.STOCKPILE;
        return Role.HUT;
    }
}
