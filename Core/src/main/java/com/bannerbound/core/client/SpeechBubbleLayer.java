package com.bannerbound.core.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;
import org.joml.Matrix4f;

import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.social.ConversationTopic;
import com.bannerbound.core.social.WorkstationIcons;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws the conversation speech bubble above a citizen's head when their {@code DATA_BUBBLE}
 * synched-data slot is non-zero. Invoked from {@link CitizenRenderer#render} <em>after</em>
 * {@code super.render(...)} returns — so the pose stack is back in world space relative to
 * the entity's render position, no model flips applied. This is the same context vanilla
 * name tags render in (via {@code EntityRenderer.renderNameTag} called by the parent class
 * after its own popPose).
 *
 * <p>Originally a {@link net.minecraft.client.renderer.entity.layers.RenderLayer}, but layers
 * are invoked <em>inside</em> {@code LivingEntityRenderer.render}'s push/pop block — by then
 * the pose has {@code scale(-1, -1, 1) + translate(0, -1.501, 0)} applied, which flips
 * everything upside-down and mirrors X. Sidesteppingthat by drawing post-super.
 *
 * <p><b>Animation</b> — wall-clock-driven, one cycle per BUBBLE phase:
 * <ul>
 *   <li>{@code 0–300 ms}: scale 0 → 1 with ease-out ({@code 1 - (1-t)²}).</li>
 *   <li>{@code 300–3500 ms}: full size, full alpha.</li>
 *   <li>{@code 3500–4000 ms}: alpha 1 → 0 linear fade-out.</li>
 *   <li>{@code > 4000 ms}: hidden (server flips DATA_BUBBLE back to 0 at 4000 ms).</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class SpeechBubbleLayer {
    /** Master scale — 1.5× the vanilla nametag's 0.025 so the bubble reads at a glance. */
    private static final float SCALE = 0.0375f;
    /** Topic icon is rendered at SCALE / ICON_DIVISOR so it fits comfortably inside the bubble. */
    private static final float ICON_DIVISOR = 1.5f;
    /** JOB item icons go through {@link ItemDisplayContext#GUI}, which draws the model at unit
     *  size in y-UP model space — the vanilla 16× GUI-cell scale (and its accompanying -Y flip)
     *  that {@code GuiGraphics} applies is NOT in play here. So the item path needs its own world
     *  scale, much larger than {@code SCALE * scale}, to read at the same size as the font-glyph
     *  topics. Tunable — bump up/down to taste against the bubble outline. */
    private static final float ITEM_ICON_SCALE = 0.4f;
    /** Billboard-local depth offset for the topic icon — applied AFTER cameraOrientation so it's
     *  in screen space, not world space. In Minecraft's PoseStack after {@code cameraOrientation},
     *  <b>positive</b> local Z is toward the camera (a -Z offset puts the glyph FURTHER from the
     *  viewer in depth-buffer terms, which made the icon fail the depth test against the bubble
     *  drawn at Z = 0 and disappear behind it). World-space offsets are angle-dependent (the
     *  original bug); billboard-local with the correct sign is angle-independent and always
     *  places the icon in front of the bubble glyph. */
    private static final float ICON_Z_OFFSET = 0.005f;
    private static final long SCALE_IN_MS = 300L;
    private static final long FADE_OUT_MS = 500L;
    private static final long TOTAL_MS = 4_000L;

    private static final Map<UUID, AnimState> ANIM_STATES = new HashMap<>();

    private static final class AnimState {
        int lastBubble = 0;
        long startMs = 0L;
    }

    private SpeechBubbleLayer() {}

    /** Called from {@code CitizenRenderer.render} after {@code super.render} returns.
     *  Pose stack is in world space at the entity's render origin. */
    public static void draw(CitizenEntity entity, PoseStack pose, MultiBufferSource buffers,
                            int packedLight) {
        int bubbleId = entity.getBubbleTopic();
        AnimState state = ANIM_STATES.computeIfAbsent(entity.getUUID(), id -> new AnimState());
        long now = System.currentTimeMillis();
        if (bubbleId != state.lastBubble) {
            state.lastBubble = bubbleId;
            state.startMs = now;
        }
        if (bubbleId == 0) return;

        long elapsed = now - state.startMs;
        if (elapsed >= TOTAL_MS) return;

        float scale = 1.0f;
        float alpha = 1.0f;
        if (elapsed < SCALE_IN_MS) {
            float t = elapsed / (float) SCALE_IN_MS;
            float oneMinus = 1.0f - t;
            scale = 1.0f - oneMinus * oneMinus; // ease-out
        } else if (elapsed > TOTAL_MS - FADE_OUT_MS) {
            alpha = Math.max(0.0f, (TOTAL_MS - elapsed) / (float) FADE_OUT_MS);
        }
        if (scale <= 0.0f || alpha <= 0.0f) return;

        ConversationTopic topic = ConversationTopic.fromBubbleId(bubbleId);
        if (topic == null) return;
        int subType = ConversationTopic.subTypeFromPackedId(bubbleId);
        Component bubbleGlyph = Icons.bubble();
        // Null = JOB topic with no workstation; we still draw the empty bubble but skip the
        // inner icon. The bubble alone is enough to signal "talking about my job, which is
        // nothing" — matching the user's spec: "no job (No icon)".
        Component topicGlyph = topicComponentFor(topic, subType, entity.getEra());

        int aByte = Math.min(255, Math.max(0, (int) (alpha * 255.0f)));
        int color = (aByte << 24) | 0x00FFFFFF;

        Font font = Minecraft.getInstance().font;

        // ── Bubble background pose ───────────────────────────────────────────────────────────
        pose.pushPose();
        // Position above the nametag baseline (nametag is at bbHeight + 0.5) by another 0.35.
        pose.translate(0.0f, entity.getBbHeight() + 0.85f, 0.0f);
        pose.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        // Vanilla nametag scale convention: positive X, negative Y (Font outputs y-down; we
        // want y-up in world), positive Z. SCALE * scale folds the animation into the factor.
        pose.scale(SCALE * scale, -SCALE * scale, SCALE * scale);

        Matrix4f bubbleMatrix = pose.last().pose();
        float bubbleW = font.width(bubbleGlyph);
        float bubbleH = font.lineHeight;
        font.drawInBatch(bubbleGlyph, -bubbleW / 2f, -bubbleH / 2f, color, false,
            bubbleMatrix, buffers, Font.DisplayMode.NORMAL, 0, packedLight);
        pose.popPose();

        // ── Topic icon. JOB renders a real ItemStack (so the player sees the actual workstation
        //    block's hotbar icon); everything else renders a font glyph. Both paths use the same
        //    billboard-local Z offset to sit just in front of the bubble glyph. ──────────────
        if (topic == ConversationTopic.JOB) {
            ItemStack workstationStack = jobIconFor(subType, entity);
            // Always draw something for JOB now — an unemployed citizen shows a barrier (handled
            // inside jobIconFor) so "I'd work but there's nothing to do" reads clearly.
            if (!workstationStack.isEmpty()) {
                drawItemIcon(workstationStack, entity, pose, buffers, packedLight, scale);
            }
        } else if (topicGlyph != null) {
            drawGlyphIcon(topicGlyph, entity, pose, buffers, packedLight, color, scale, font);
        }
    }

    /**
     * Draws a red "!" above a citizen that can't work due to a problem (a BLOCKED work status —
     * no tool, banner down, storage full, …). Called from {@code CitizenRenderer.render} after
     * {@code super.render} returns, in the same clean world-space pose the bubble uses. Sits a touch
     * higher than the conversation bubble so the two don't overlap when both are showing. Cheap: a
     * single font glyph, billboard-locked the vanilla nametag way.
     */
    public static void drawBlocked(CitizenEntity entity, PoseStack pose, MultiBufferSource buffers,
                                   int packedLight) {
        if (!entity.isWorkBlocked()) return;
        Font font = Minecraft.getInstance().font;
        // Gentle pulse so it reads as an alert without being distracting.
        long now = System.currentTimeMillis();
        float pulse = 0.85f + 0.15f * (float) Math.sin(now / 250.0);
        int aByte = Math.min(255, Math.max(0, (int) (pulse * 255.0f)));
        int color = (aByte << 24) | 0x00FF3030; // red

        pose.pushPose();
        // Well above the conversation bubble (which sits at bbHeight + 0.85 and is ~0.5 tall) so the
        // "!" clears bubble.png entirely even when both show at once.
        pose.translate(0.0f, entity.getBbHeight() + 1.55f, 0.0f);
        pose.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        // Depth nudge toward the camera AFTER the billboard orientation (same as the topic glyphs)
        // so the alert never z-fights or pops behind the bubble sprite at oblique angles.
        pose.translate(0.0f, 0.0f, ICON_Z_OFFSET);
        // Slightly larger than the topic icon so the alert is glanceable.
        float s = SCALE * 1.2f;
        pose.scale(s, -s, s);
        Matrix4f matrix = pose.last().pose();
        Component glyph = Component.literal("!");
        float w = font.width(glyph);
        font.drawInBatch(glyph, -w / 2f, -font.lineHeight / 2f, color, false,
            matrix, buffers, Font.DisplayMode.NORMAL, 0, packedLight);
        pose.popPose();
    }

    /** Font-glyph icon path — used by CULTURE/FOOD/SCIENCE/HAPPINESS. Same matrix dance as the
     *  bubble background: billboard-lock via {@code cameraOrientation}, push toward the camera
     *  in billboard space so the icon depth-tests in front of the bubble glyph. */
    private static void drawGlyphIcon(Component glyph, CitizenEntity entity, PoseStack pose,
                                       MultiBufferSource buffers, int packedLight,
                                       int color, float scale, Font font) {
        pose.pushPose();
        pose.translate(0.0f, entity.getBbHeight() + 0.85f, 0.0f);
        pose.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        pose.translate(0.0f, 0.0f, ICON_Z_OFFSET);
        float iconScale = (SCALE / ICON_DIVISOR) * scale;
        pose.scale(iconScale, -iconScale, iconScale);
        Matrix4f iconMatrix = pose.last().pose();
        float iconW = font.width(glyph);
        float iconH = font.lineHeight;
        font.drawInBatch(glyph, -iconW / 2f, -iconH / 2f, color, false,
            iconMatrix, buffers, Font.DisplayMode.NORMAL, 0, packedLight);
        pose.popPose();
    }

    /** ItemStack icon path — used by JOB so the bubble shows the actual workstation block's
     *  hotbar appearance, not a stand-in font glyph. Rendered in {@link ItemDisplayContext#GUI}
     *  (flat, like in the inventory) and billboard-locked the same way the glyph path is. */
    private static void drawItemIcon(ItemStack stack, CitizenEntity entity, PoseStack pose,
                                      MultiBufferSource buffers, int packedLight, float scale) {
        pose.pushPose();
        pose.translate(0.0f, entity.getBbHeight() + 0.85f, 0.0f);
        pose.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        pose.translate(0.0f, 0.0f, ICON_Z_OFFSET);
        // Unlike the font-glyph path, do NOT flip Y: GUI items render in y-UP model space (the
        // vanilla -Y flip lives in GuiGraphics' separate 16× cell scale, which we don't apply
        // here), so a negative Y would draw the icon upside down. ITEM_ICON_SCALE compensates for
        // that missing cell scale so the item reads at roughly the same size as the glyph topics.
        float iconScale = ITEM_ICON_SCALE * scale;
        pose.scale(iconScale, iconScale, iconScale);
        // Show the REAL workstation/tool icon even when the viewing player hasn't unlocked that
        // item yet. Without this, ItemRendererMixin swaps any item the local player doesn't know
        // to the question-mark model — but a JOB bubble is a UI cue ("this is my job"), not a
        // discoverable world item, so it should ignore the unknown-item treatment. Same bypass the
        // research tooltip's "Unlocked Items" grid uses; reset in finally so nothing else inherits
        // it (render thread is single-threaded, so a plain flag is safe).
        UnknownItemHelper.setBypassUnknownSwap(true);
        try {
            Minecraft.getInstance().getItemRenderer().renderStatic(
                stack, ItemDisplayContext.GUI,
                packedLight, OverlayTexture.NO_OVERLAY,
                pose, buffers, Minecraft.getInstance().level, 0);
        } finally {
            UnknownItemHelper.setBypassUnknownSwap(false);
        }
        pose.popPose();
    }

    /** Resolves the ItemStack shown in a JOB bubble. Per-role icons: Digger → current tool-age
     *  shovel, Farmer → current tool-age hoe (both synced on the citizen), Forager → poppy,
     *  Fisher → fishing rod, Stocker → bundle, Forester → its log block. A citizen with no job
     *  (subType 0, or a type whose item is missing) shows a barrier — "I'd work, but there's
     *  nothing to do." */
    private static ItemStack jobIconFor(int subType, CitizenEntity entity) {
        String typeId = WorkstationIcons.typeIdOf(subType);
        if (typeId == null) {
            return new ItemStack(net.minecraft.world.item.Items.BARRIER);
        }
        return switch (typeId) {
            case "diggers_slab" -> nonEmptyOrBarrier(entity.getToolShovelItem());
            case "farmers_granary" -> nonEmptyOrBarrier(entity.getToolHoeItem());
            case "foragers_basket" -> new ItemStack(net.minecraft.world.item.Items.POPPY);
            case "fishers_creel" -> new ItemStack(net.minecraft.world.item.Items.FISHING_ROD);
            case "stockpile_rack" -> new ItemStack(net.minecraft.world.item.Items.BUNDLE);
            default -> {
                // Forester (and any future block-icon workstation) keeps its workstation block.
                ItemStack ws = WorkstationIcons.itemOrdinal(subType);
                yield ws.isEmpty() ? new ItemStack(net.minecraft.world.item.Items.BARRIER) : ws;
            }
        };
    }

    /** Wrap an item as a stack, falling back to a barrier when the item is AIR (e.g. no tool age
     *  researched yet, so the shovel/hoe resolves to AIR). */
    private static ItemStack nonEmptyOrBarrier(net.minecraft.world.item.Item item) {
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return new ItemStack(net.minecraft.world.item.Items.BARRIER);
        }
        return new ItemStack(item);
    }

    /** Resolves the inner glyph for non-JOB topics. JOB is handled separately because it draws
     *  an ItemStack, not a font glyph. Returns {@code null} for topics with no glyph to show. */
    private static Component topicComponentFor(ConversationTopic topic, int subType, Era era) {
        return switch (topic) {
            case CULTURE   -> Icons.culture(era);
            case FOOD      -> Icons.food(era);
            case SCIENCE   -> Icons.science(era);
            case HAPPINESS -> Icons.happinessForBucket(subType);
            case JOB       -> null; // handled in draw() via drawItemIcon
        };
    }
}
