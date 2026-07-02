package com.bannerbound.core.client;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.GsonHelper;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client-side reader for assets/<namespace>/codex_clips/*.json metadata. */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientCodexClips {
    private static final Gson GSON = new Gson();
    private static final Map<String, Clip> CACHE = new HashMap<>();

    private ClientCodexClips() {
    }

    public static Clip get(String id) {
        String key = id == null ? "" : id.trim();
        if (key.isBlank()) return Clip.missing("");
        return CACHE.computeIfAbsent(key, ClientCodexClips::load);
    }

    private static Clip load(String id) {
        ResourceLocation clipId = ResourceLocation.tryParse(id);
        if (clipId == null) return Clip.missing(id);
        ResourceLocation json = ResourceLocation.fromNamespaceAndPath(clipId.getNamespace(),
            "codex_clips/" + clipId.getPath() + ".json");
        try {
            Resource resource = Minecraft.getInstance().getResourceManager().getResource(json).orElse(null);
            if (resource == null) return Clip.missing(id);
            try (InputStreamReader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                JsonElement element = GSON.fromJson(reader, JsonElement.class);
                JsonObject obj = element == null ? new JsonObject() : element.getAsJsonObject();
                return new Clip(
                    id,
                    GsonHelper.getAsString(obj, "video", ""),
                    GsonHelper.getAsString(obj, "poster", ""),
                    GsonHelper.getAsBoolean(obj, "loop", true),
                    GsonHelper.getAsBoolean(obj, "autoplay", false),
                    GsonHelper.getAsString(obj, "audio", ""),
                    GsonHelper.getAsString(obj, "url", ""),
                    true
                );
            }
        } catch (Exception ex) {
            return Clip.missing(id);
        }
    }

    public record Clip(String id, String video, String poster, boolean loop, boolean autoplay,
                       String audio, String url, boolean present) {
        public static Clip missing(String id) {
            return new Clip(id == null ? "" : id, "", "", true, false, "", "", false);
        }

        public ResourceLocation posterLocation() {
            return ResourceLocation.tryParse(poster);
        }
    }
}
