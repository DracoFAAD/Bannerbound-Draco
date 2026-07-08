package com.bannerbound.core.client;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Render-thread registry mapping clip ids to live video playback. videoTexture() is the whole
 * client API: the first call for a clip spins up its ClipVideoPlayer decode thread and returns
 * null until the first frame lands (callers show the poster or a placeholder meanwhile); after
 * that it drains any newly decoded frame into the clip's DynamicTexture and returns the texture's
 * location for a plain blit. Every call stamps lastTouched, and tick() (driven from the client
 * tick) reaps clips not rendered for IDLE_TIMEOUT_MS - stopping the decoder thread and releasing
 * the texture - so page turns and screen closes need no explicit lifecycle calls. A failed
 * player stays in the map as a tombstone until reaped, keeping videoTexture cheap to call every
 * frame. All state is confined to the render thread; only the frame handoff inside the player
 * crosses threads.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClipPlaybackManager {
    private static final long IDLE_TIMEOUT_MS = 2000L;
    private static final Map<String, Entry> ACTIVE = new HashMap<>();

    private ClipPlaybackManager() {
    }

    public static ResourceLocation videoTexture(ClientCodexClips.Clip clip) {
        if (clip == null || !clip.present() || clip.video().isBlank()) return null;
        ResourceLocation videoLocation = ResourceLocation.tryParse(clip.video());
        if (videoLocation == null) return null;
        Entry entry = ACTIVE.computeIfAbsent(clip.id(),
            id -> new Entry(new ClipVideoPlayer(id, videoLocation, clip.loop())));
        entry.lastTouchedMs = Util.getMillis();
        if (entry.player.failed()) return null;
        int[] frame = entry.player.pollFrame();
        if (frame != null) upload(entry, frame);
        return entry.textureLocation;
    }

    public static void tick() {
        if (ACTIVE.isEmpty()) return;
        long now = Util.getMillis();
        Iterator<Map.Entry<String, Entry>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Entry entry = it.next().getValue();
            if (now - entry.lastTouchedMs < IDLE_TIMEOUT_MS) continue;
            entry.player.stop();
            if (entry.textureLocation != null) {
                Minecraft.getInstance().getTextureManager().release(entry.textureLocation);
            }
            it.remove();
        }
    }

    private static void upload(Entry entry, int[] frame) {
        int width = entry.player.frameWidth();
        int height = entry.player.frameHeight();
        if (width <= 0 || height <= 0 || frame.length < width * height) return;
        if (entry.texture == null) {
            entry.texture = new DynamicTexture(width, height, false);
            entry.textureLocation = Minecraft.getInstance().getTextureManager()
                .register("bannerbound_clip", entry.texture);
        }
        NativeImage image = entry.texture.getPixels();
        if (image == null || image.getWidth() != width || image.getHeight() != height) return;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                image.setPixelRGBA(x, y, frame[row + x]);
            }
        }
        entry.texture.upload();
    }

    private static final class Entry {
        final ClipVideoPlayer player;
        DynamicTexture texture;
        ResourceLocation textureLocation;
        long lastTouchedMs;

        Entry(ClipVideoPlayer player) {
            this.player = player;
        }
    }
}
