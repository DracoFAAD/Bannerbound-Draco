package com.bannerbound.core.client;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import org.jcodec.api.FrameGrab;
import org.jcodec.api.PictureWithMetadata;
import org.jcodec.common.io.ByteBufferSeekableByteChannel;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.ColorUtil;
import org.jcodec.scale.Transform;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Pure-Java decoder for one looping tutorial clip (TUTORIAL_POPUP_PLAN.md section 3, Tier A):
 * a daemon worker thread reads the bundled MP4 fully into memory, demuxes and decodes it with
 * JCodec, converts each frame YUV to RGB, and publishes packed ABGR pixel arrays through an
 * AtomicReference that the render thread drains via pollFrame (ClipPlaybackManager uploads them
 * into a DynamicTexture). Pacing sleeps each frame to its container timestamp, and end-of-stream
 * restarts the FrameGrab over the same in-memory buffer for looping. Frames rotate through a
 * three-buffer pool so steady playback allocates nothing; a consumer that lags one publish behind
 * risks a brief torn frame, never a crash. Any decode error marks the player failed (logged once)
 * and the UI quietly falls back to the poster or a placeholder. The encode contract this decoder
 * expects is H.264 BASELINE yuv420p at 480p or below - see the codex_clips README.
 */
@OnlyIn(Dist.CLIENT)
final class ClipVideoPlayer {
    private final String clipId;
    private final ResourceLocation videoLocation;
    private final boolean loop;
    private final AtomicReference<int[]> pendingFrame = new AtomicReference<>();
    private final int[][] framePool = new int[3][];
    private int poolIndex;
    private Picture rgbScratch;
    private Transform transform;
    private volatile int frameWidth;
    private volatile int frameHeight;
    private volatile boolean failed;
    private volatile boolean stopped;

    ClipVideoPlayer(String clipId, ResourceLocation videoLocation, boolean loop) {
        this.clipId = clipId;
        this.videoLocation = videoLocation;
        this.loop = loop;
        Thread thread = new Thread(this::run, "bannerbound-clip-" + videoLocation.getPath());
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    int[] pollFrame() {
        return pendingFrame.getAndSet(null);
    }

    int frameWidth() {
        return frameWidth;
    }

    int frameHeight() {
        return frameHeight;
    }

    boolean failed() {
        return failed;
    }

    void stop() {
        stopped = true;
    }

    private void run() {
        try {
            Resource resource = Minecraft.getInstance().getResourceManager()
                .getResource(videoLocation).orElse(null);
            if (resource == null) {
                failed = true;
                return;
            }
            byte[] bytes;
            try (InputStream in = resource.open()) {
                bytes = in.readAllBytes();
            }
            ByteBuffer master = ByteBuffer.wrap(bytes);
            do {
                FrameGrab grab = FrameGrab.createFrameGrab(
                    ByteBufferSeekableByteChannel.readFromByteBuffer(master.duplicate()));
                long origin = System.nanoTime();
                PictureWithMetadata frame;
                while (!stopped && (frame = grab.getNativeFrameWithMetadata()) != null) {
                    long waitNanos = origin + (long) (frame.getTimestamp() * 1.0e9) - System.nanoTime();
                    if (waitNanos > 1_000_000L) {
                        Thread.sleep(waitNanos / 1_000_000L);
                    }
                    publish(frame.getPicture());
                }
            } while (loop && !stopped);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Throwable ex) {
            failed = true;
            BannerboundCore.LOGGER.warn("Tutorial clip {} failed to decode ({}): {}",
                clipId, videoLocation, ex.toString());
        }
    }

    private void publish(Picture src) {
        if (rgbScratch == null || rgbScratch.getWidth() != src.getWidth()
                || rgbScratch.getHeight() != src.getHeight()) {
            rgbScratch = Picture.create(src.getWidth(), src.getHeight(), ColorSpace.RGB);
            transform = ColorUtil.getTransform(src.getColor(), ColorSpace.RGB);
        }
        transform.transform(src, rgbScratch);
        int width = src.getCroppedWidth();
        int height = src.getCroppedHeight();
        int stride = rgbScratch.getWidth();
        byte[] data = rgbScratch.getPlaneData(0);
        int[] out = framePool[poolIndex];
        if (out == null || out.length != width * height) {
            out = new int[width * height];
            framePool[poolIndex] = out;
        }
        poolIndex = (poolIndex + 1) % framePool.length;
        for (int y = 0; y < height; y++) {
            int rowIn = y * stride * 3;
            int rowOut = y * width;
            for (int x = 0; x < width; x++) {
                int base = rowIn + x * 3;
                // JCodec stores samples as signed bytes offset by -128; NativeImage wants ABGR.
                int r = (data[base] + 128) & 0xFF;
                int g = (data[base + 1] + 128) & 0xFF;
                int b = (data[base + 2] + 128) & 0xFF;
                out[rowOut + x] = 0xFF000000 | (b << 16) | (g << 8) | r;
            }
        }
        frameWidth = width;
        frameHeight = height;
        pendingFrame.set(out);
    }
}
