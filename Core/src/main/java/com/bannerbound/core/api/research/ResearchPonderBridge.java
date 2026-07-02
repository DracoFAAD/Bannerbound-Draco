package com.bannerbound.core.api.research;

import java.util.function.Consumer;
import java.util.function.IntPredicate;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side bridge for opening a Create-style Ponder scene from the research tree.
 * <p>
 * Core never depends on Create. An expansion mod that <em>does</em> depend on Create
 * (typically as a soft dependency) registers an opener here at client setup; the research
 * screen then shows a "Hold [W] to Ponder" tooltip line on guided nodes and forwards a
 * <em>held</em> W key to the opener. When no opener is registered (no Create-aware expansion
 * present), {@link #isAvailable} returns {@code false} and the research screen draws no ponder
 * affordance — players without Create see no trace of the system.
 * <p>
 * Hold semantics match Create's inventory tooltip: tap-and-release does nothing; the W key
 * must be held for {@link #HOLD_DURATION_MILLIS} milliseconds over a guided node for the scene
 * to open. Progress is tracked in wall-clock nanoseconds so the bar fills smoothly every render
 * frame rather than stepping at the 20 Hz game tick. The screen drives the lifecycle by calling
 * {@link #beginHold}, {@link #cancelHold}, and {@link #tickHold} from its keyPressed /
 * keyReleased / tick hooks.
 * <p>
 * The trigger key defaults to {@code W} so it lines up with Create's own keybind out of the
 * box. An expansion may override it via {@link #setKeyMatcher} to honour a player-rebound
 * Create keybind.
 */
@OnlyIn(Dist.CLIENT)
public final class ResearchPonderBridge {
    /** Time the W key must be held over a guided node before the ponder opens. */
    public static final long HOLD_DURATION_MILLIS = 750L;
    private static final long HOLD_DURATION_NANOS = HOLD_DURATION_MILLIS * 1_000_000L;

    private static Consumer<String> opener = null;
    private static IntPredicate keyMatcher = key -> key == GLFW.GLFW_KEY_W;

    // Hold-to-ponder state. Only one node can be "charging" at a time per client.
    // holdStartNanos == -1L means "not holding"; otherwise it's the System.nanoTime() the
    // current hold started, used to compute progress against HOLD_DURATION_NANOS.
    @Nullable private static String holdingSceneId = null;
    private static long holdStartNanos = -1L;

    private ResearchPonderBridge() {}

    /** Registers the function that opens a Ponder scene by id. Pass {@code null} to clear. */
    public static void setOpener(Consumer<String> opener) {
        ResearchPonderBridge.opener = opener;
    }

    /** Overrides the key predicate used to decide whether a key event triggers a ponder.
     *  Default = {@code W}. Passing {@code null} restores the default. */
    public static void setKeyMatcher(IntPredicate matcher) {
        ResearchPonderBridge.keyMatcher = matcher != null ? matcher : (key -> key == GLFW.GLFW_KEY_W);
    }

    /** True when a Ponder-capable expansion has registered an opener. */
    public static boolean isAvailable() {
        return opener != null;
    }

    /** True when the given key event should trigger the ponder. */
    public static boolean matchesKey(int keyCode) {
        return keyMatcher.test(keyCode);
    }

    /** Opens the named ponder scene immediately. No-op if no opener is registered or the id
     *  is blank. Usually called via {@link #tickHold} once the hold threshold is reached —
     *  external callers can still use it to bypass the hold timing (e.g. a debug command). */
    public static void open(String sceneId) {
        Consumer<String> o = opener;
        if (o != null && sceneId != null && !sceneId.isEmpty()) {
            o.accept(sceneId);
        }
    }

    // ─── Hold-to-ponder ────────────────────────────────────────────────────────────────────────

    /** Called by the research screen on keyPressed. If the key + node both qualify, starts a
     *  charge; returns {@code true} so the screen consumes the event. Safe to call on key
     *  auto-repeat — repeated calls for the same node don't restart the timer. */
    public static boolean beginHold(@Nullable String hoveredSceneId, int keyCode) {
        if (!isAvailable() || !matchesKey(keyCode)) return false;
        if (hoveredSceneId == null || hoveredSceneId.isEmpty()) return false;
        if (hoveredSceneId.equals(holdingSceneId)) {
            return true; // already charging this node — keep going
        }
        holdingSceneId = hoveredSceneId;
        holdStartNanos = System.nanoTime();
        return true;
    }

    /** Called by the research screen on keyReleased. Cancels an in-progress hold. */
    public static boolean cancelHold(int keyCode) {
        if (!matchesKey(keyCode)) return false;
        boolean wasHolding = holdingSceneId != null;
        holdingSceneId = null;
        holdStartNanos = -1L;
        return wasHolding;
    }

    /** Called by the research screen each tick. Cancels the hold if the mouse drifts off the
     *  charging node, and fires {@link #open} once the elapsed time reaches
     *  {@link #HOLD_DURATION_MILLIS}. Render-frame progress (for the bar visual) comes from
     *  {@link #holdProgress} instead — it's recomputed from wall-clock time every frame so the
     *  bar fills smoothly between game ticks.
     *
     *  @param currentlyHoveredSceneId the scene id of the currently hovered node, or {@code null}
     *  @return progress fraction {@code 0..1} at this tick (0 = no hold, 1 = just fired) */
    public static float tickHold(@Nullable String currentlyHoveredSceneId) {
        if (holdingSceneId == null) return 0f;
        if (currentlyHoveredSceneId == null || !holdingSceneId.equals(currentlyHoveredSceneId)) {
            holdingSceneId = null;
            holdStartNanos = -1L;
            return 0f;
        }
        float p = computeProgress();
        if (p >= 1f) {
            String toOpen = holdingSceneId;
            holdingSceneId = null;
            holdStartNanos = -1L;
            open(toOpen);
            return 1f;
        }
        return p;
    }

    /** True when a hold is currently charging. Cheap; safe to call every frame. */
    public static boolean isHolding() {
        return holdingSceneId != null;
    }

    /** Current charge progress {@code 0..1}, recomputed from wall-clock time on every call —
     *  call this from the render path (the tooltip rebuilds each frame) for a smooth bar. */
    public static float holdProgress() {
        return computeProgress();
    }

    private static float computeProgress() {
        if (holdingSceneId == null || holdStartNanos < 0L) {
            return 0f;
        }
        long elapsed = System.nanoTime() - holdStartNanos;
        if (elapsed <= 0L) return 0f;
        if (elapsed >= HOLD_DURATION_NANOS) return 1f;
        return (float) ((double) elapsed / (double) HOLD_DURATION_NANOS);
    }

    /** The "Hold [W] to Ponder" hint component, with the same progress-bar treatment Create
     *  uses for its inventory tooltip: idle shows the text in DARK_GRAY (with the {@code [W]}
     *  keybind in GRAY); while holding, the text is replaced by a row of {@code |} characters
     *  that fill up from left to right — filled portion in GRAY, remaining portion in DARK_GRAY.
     *  Pass the calling screen's {@link Font} so the bar's total width matches the text width. */
    public static Component holdToPonderHint(Font font) {
        final String baseText = "Hold [W] to Ponder";
        float progress = holdProgress();
        if (progress <= 0f) {
            return Component.literal("Hold ")
                .append(Component.literal("[W]").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" to Ponder"))
                .withStyle(ChatFormatting.DARK_GRAY);
        }
        float charWidth = font.width("|");
        int total = Math.max(1, (int) Math.floor(font.width(baseText) / charWidth));
        int current = Math.max(0, Math.min(total, (int) (progress * total)));
        MutableComponent bar = Component.literal("");
        if (current > 0) {
            bar.append(Component.literal("|".repeat(current)).withStyle(ChatFormatting.GRAY));
        }
        if (current < total) {
            bar.append(Component.literal("|".repeat(total - current)).withStyle(ChatFormatting.DARK_GRAY));
        }
        return bar;
    }
}
