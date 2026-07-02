package com.bannerbound.core.client;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.network.CastCrisisChoicePayload;
import com.bannerbound.core.network.OpenCrisisScreenPayload;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.jetbrains.annotations.ApiStatus;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class CrisisScreen extends PolishedScreen {
    private static final ResourceLocation DEFAULT_CRISIS_ART =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/crisis.png");
    private static final int CHOICE_ROW_H = 42;
    private static final int UI_AFTER_ART_MS = 260;
    private static final int EXIT_ANIMATION_MS = 260;
    private static final float PRESENTATION_SPEED = 3.0f;
    private static final Map<ResourceLocation, ImageInfo> IMAGE_INFO = new HashMap<>();

    private OpenCrisisScreenPayload payload;
    private final long openedAtMs = Util.getMillis();
    private final List<Button> choiceButtons = new ArrayList<>();
    private Button waitingButton;
    private int lastHoveredChoice = -1;
    private boolean openSoundPlayed;
    private boolean closeSoundPlayed;
    private boolean exiting;
    private long exitStartedAtMs;

    public CrisisScreen(OpenCrisisScreenPayload payload) {
        super(Component.literal(payload.title()));
        this.payload = payload;
    }

    @Override
    protected boolean drawsDimmedBackground() {
        return false;
    }

    /** Live in-place update (vote/advice tally) of an already-open screen. Keeps the open
     *  animation timers ({@link #openedAtMs}) so the cinematic art doesn't re-fade on every vote. */
    public void refresh(OpenCrisisScreenPayload updated) {
        if (exiting || updated == null) return;
        this.payload = updated;
        rebuildWidgets();
    }

    @Override
    protected void init() {
        if (!openSoundPlayed) {
            openSoundPlayed = true;
            playUiSound(BannerboundCore.CRISIS_MENU_OPEN_SOUND.get(), 1.0f, 0.95f);
        }
        clearWidgets();
        choiceButtons.clear();
        waitingButton = null;
        int left = choiceLeft();
        int buttonW = choiceWidth(left);
        int y = choiceTop();
        if (payload.awaitingChoice()) {
            List<OpenCrisisScreenPayload.Choice> choices = payload.choices();
            for (int i = 0; i < choices.size(); i++) {
                OpenCrisisScreenPayload.Choice choice = choices.get(i);
                MutableComponent label = Component.empty();
                if (!choice.viable()) {
                    label.append(Component.literal("! ").withStyle(ChatFormatting.RED));
                }
                label.append(Component.literal(choice.label()));
                if (payload.councilVote()) {
                    label.append(Component.literal("  " + choice.votes() + "/" + payload.requiredVotes()));
                } else if (choice.votes() > 0) {
                    // Advisory tally (chiefdom): shows the chief how members are leaning, and shows
                    // members their own advice was counted.
                    label.append(Component.literal("  (" + choice.votes() + ")").withStyle(ChatFormatting.GRAY));
                }
                // Advisers stay on the screen after clicking (they may change their advice and
                // watch the tally); deciders close on commit.
                final boolean advise = !payload.canChoose() && payload.canAdvise();
                Button button = PolishButton.polished(label, b -> {
                    PacketDistributor.sendToServer(new CastCrisisChoicePayload(choice.id()));
                    if (!advise) {
                        b.active = false;
                        this.onClose();
                    }
                }).bounds(left, y + i * CHOICE_ROW_H, buttonW, 28).build();
                button.visible = false;
                button.active = false;
                choiceButtons.add(button);
                addRenderableWidget(button);
            }
            if (!payload.canChoose() && !payload.canAdvise()) {
                waitingButton = PolishButton.polished(
                    Component.translatable("bannerbound.crisis.waiting"),
                    b -> this.onClose()
                ).bounds(left, y + choices.size() * CHOICE_ROW_H + 8, buttonW, 22).build();
                waitingButton.visible = false;
                addRenderableWidget(waitingButton);
            }
        }
    }

    @Override
    public void onClose() {
        if (exiting) return;
        if (!closeSoundPlayed) {
            closeSoundPlayed = true;
            playUiSound(BannerboundCore.CRISIS_MENU_CLOSE_SOUND.get(), 1.0f, 0.92f);
        }
        exiting = true;
        exitStartedAtMs = Util.getMillis();
        for (Button button : choiceButtons) {
            button.active = false;
        }
        if (waitingButton != null) {
            waitingButton.active = false;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (exiting && Util.getMillis() - exitStartedAtMs >= EXIT_ANIMATION_MS) {
            super.onClose();
        }
    }

    @Override
    protected void renderPolishedBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        drawCinematicBackground(g, mouseX, mouseY, partialTick);
        updateChoiceButtonAnimations();

        int uiStart = uiStartMs();
        float panel = uiProgress(uiStart, 520);
        if (panel <= 0.0f) return;
        int left = Math.max(26, this.width / 2 - 430) - Math.round((1f - panel) * 28f);
        int maxTextW = Math.min(560, this.width - left - 42);
        int y = Math.max(34, this.height / 2 - 185);

        g.fill(left - 14, y - 18, left + maxTextW + 18, y + 190,
            fadeColor(0x66000000, panel));
        g.fillGradient(left - 14, y - 18, left + maxTextW + 18, y + 190,
            fadeColor(0x88020B0B, panel), fadeColor(0x22020B0B, panel));

        float category = uiProgress(uiStart + 90, 360);
        if (category > 0.0f) {
            drawSlidingString(g, payload.category(), left, y, 0xFFB8B2A8, category, false);
        }
        y += 18;
        float headline = uiProgress(uiStart + 210, 380);
        if (headline > 0.0f) {
            drawSlidingString(g, payload.headline(), left, y, 0xFFFFFFFF, headline, true);
            int lineW = Math.round(Math.min(maxTextW, 480) * headline);
            g.fill(left, y + 16, left + lineW, y + 17, fadeColor(0xFFD4AF37, headline));
        }
        y += 34;
        float body = uiProgress(uiStart + 430, 520);
        if (body > 0.0f) {
            y = drawSlidingWrapped(g, payload.body(), left, y, maxTextW, 0xFFE3DACB, body);
        } else {
            y += wrappedHeight(Component.literal(payload.body()), maxTextW);
        }
        y += 14;

        if (payload.awaitingChoice()) {
            String promptText = payload.prompt().isBlank()
                ? (payload.councilVote() ? "The council must agree on a response." : "Choose a response.")
                : payload.prompt();
            float prompt = uiProgress(uiStart + 720, 430);
            if (prompt > 0.0f) {
                y = drawSlidingWrapped(g, promptText, left, y, maxTextW, 0xFF73D7E0, prompt);
            } else {
                y += wrappedHeight(Component.literal(promptText), maxTextW);
            }
            if (payload.councilVote()) {
                y += 8;
                float votes = uiProgress(uiStart + 840, 360);
                if (votes > 0.0f) {
                    drawSlidingString(g, "Votes needed: " + payload.requiredVotes() + " of " + payload.onlineMembers(),
                        left, y, 0xFFB8B2A8, votes, false);
                }
            }
            drawChoiceFrames(g);
        } else {
            OpenCrisisScreenPayload.Choice chosen = chosenChoice();
            if (chosen != null) {
                float chosenLabel = uiProgress(uiStart + 720, 430);
                drawSlidingString(g, chosen.label(), left, y, 0xFFFFD47A, chosenLabel, false);
                y += 18;
                String outcome = chosen.outcome().isBlank()
                    ? "The settlement has chosen its answer."
                    : chosen.outcome();
                drawSlidingWrapped(g, outcome, left, y, maxTextW, 0xFF73D7E0,
                    uiProgress(uiStart + 900, 500));
            }
        }
    }

    @Override
    protected void renderPolishedExtras(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int left = Math.max(26, this.width / 2 - 430);
        int y = Math.max(30, this.height / 2 - 180);
        float title = uiProgress(uiStartMs() + 40, 360);
        if (title > 0.0f) {
            drawSlidingString(g, payload.title(), left, y - 22, 0xFFFFD47A, title, true);
        }
        if (payload.awaitingChoice() && !exiting) {
            int hovered = hoveredChoice(mouseX, mouseY);
            if (hovered != lastHoveredChoice) {
                lastHoveredChoice = hovered;
                if (hovered >= 0) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.12f, 1.85f);
                }
            }
            drawChoiceTooltip(g, mouseX, mouseY);
        }
    }

    private void drawChoiceFrames(GuiGraphics g) {
        int left = choiceLeft();
        int w = choiceWidth(left);
        int top = choiceTop();
        for (int i = 0; i < payload.choices().size(); i++) {
            OpenCrisisScreenPayload.Choice choice = payload.choices().get(i);
            float ease = choiceProgress(i);
            if (ease <= 0.0f) continue;
            int rowY = top + i * CHOICE_ROW_H - 5;
            int dx = Math.round((1f - ease) * 28f);
            g.fill(left - 8 - dx, rowY, left + w + 8 - dx, rowY + CHOICE_ROW_H - 5,
                fadeColor(choice.viable() ? 0x7710181A : 0x88241414, ease));
            if (!choice.viable()) {
                g.fill(left - 8 - dx, rowY, left - 4 - dx, rowY + CHOICE_ROW_H - 5,
                    fadeColor(0xD0D04A3A, ease));
                g.drawString(this.font, "!", left - 23 - dx, rowY + 10,
                    fadeColor(0xFFFF7777, ease), true);
            }
        }
    }

    private void drawChoiceTooltip(GuiGraphics g, int mouseX, int mouseY) {
        int idx = hoveredChoice(mouseX, mouseY);
        if (idx < 0 || idx >= payload.choices().size()) return;
        OpenCrisisScreenPayload.Choice choice = payload.choices().get(idx);
        String desc = choice.description().isBlank() ? choice.label() : choice.description();
        String warning = choice.viable() ? "" : choice.warning();
        String outcome = choice.outcome().isBlank() ? "" : "Outcome: " + choice.outcome();
        int tw = Math.min(300, Math.max(230, this.width / 3));
        int textW = tw - 16;
        int th = 22 + wrappedHeight(Component.literal(desc), textW);
        if (!warning.isBlank()) th += 6 + wrappedHeight(Component.literal(warning), textW);
        if (!outcome.isBlank()) th += 6 + wrappedHeight(Component.literal(outcome), textW);
        int tx = Math.min(mouseX + 14, this.width - tw - 8);
        int ty = Math.min(mouseY + 12, this.height - th - 8);
        g.pose().pushPose();
        g.pose().translate(0, 0, 500);
        g.fill(tx, ty, tx + tw, ty + th, 0xF0101416);
        g.renderOutline(tx, ty, tw, th, 0xFFD4AF37);
        int y = ty + 8;
        g.drawString(this.font, Component.literal(choice.label()).withStyle(ChatFormatting.GOLD),
            tx + 8, y, 0xFFFFD47A, false);
        y += 13;
        y = drawWrapped(g, this.font, Component.literal(desc), tx + 8, y, textW, 0xFFE6E6E6);
        if (!warning.isBlank()) {
            y += 4;
            y = drawWrapped(g, this.font, Component.literal("! " + warning).withStyle(ChatFormatting.RED),
                tx + 8, y, textW, 0xFFFF7777);
        }
        if (!outcome.isBlank()) {
            y += 4;
            drawWrapped(g, this.font, Component.literal(outcome), tx + 8, y, textW, 0xFF73D7E0);
        }
        g.pose().popPose();
    }

    private int wrappedHeight(Component text, int width) {
        return Math.max(1, this.font.split(text, Math.max(1, width)).size()) * (this.font.lineHeight + 1);
    }

    private int hoveredChoice(int mouseX, int mouseY) {
        int left = choiceLeft();
        int top = choiceTop();
        int w = choiceWidth(left);
        for (int i = 0; i < payload.choices().size(); i++) {
            if (choiceProgress(i) < 0.92f) continue;
            int rowY = top + i * CHOICE_ROW_H - 5;
            if (mouseX >= left - 8 && mouseX <= left + w + 8
                    && mouseY >= rowY && mouseY <= rowY + CHOICE_ROW_H - 5) {
                return i;
            }
        }
        return -1;
    }

    private OpenCrisisScreenPayload.Choice chosenChoice() {
        for (OpenCrisisScreenPayload.Choice choice : payload.choices()) {
            if (choice.id().equals(payload.chosenChoiceId())) return choice;
        }
        return null;
    }

    private void drawCinematicBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        drawWatercolorGround(g);
        boolean hasArt = false;
        List<OpenCrisisScreenPayload.ArtLayer> layers = payload.backgroundLayers();
        if (!layers.isEmpty()) {
            for (int i = 0; i < layers.size(); i++) {
                hasArt |= drawArtLayer(g, layers.get(i), i, mouseX, mouseY, partialTick);
            }
        } else {
            ResourceLocation art = backgroundArt();
            if (hasResource(art)) {
                hasArt = drawArtLayer(g, new OpenCrisisScreenPayload.ArtLayer(
                    art.toString(), 0.1f, 1.4f, 0.8f, 1.04f, 1.0f, 0, 1350),
                    0, mouseX, mouseY, partialTick);
            }
        }
        if (!hasArt) {
            drawFallbackBackground(g);
        }
    }

    private boolean drawArtLayer(GuiGraphics g, OpenCrisisScreenPayload.ArtLayer layer,
                                 int index, int mouseX, int mouseY, float partialTick) {
        if (layer == null || layer.texture().isBlank()) return false;
        ResourceLocation texture = ResourceLocation.tryParse(layer.texture());
        if (texture == null || !hasResource(texture)) return false;
        ImageInfo info = imageInfo(texture);
        if (!info.exists()) return false;
        float reveal = revealProgress(layer) * exitVisibility();
        CoverRect rect = coverRect(info, layer, index, mouseX, mouseY, partialTick);
        if (reveal >= 0.995f) {
            drawLayerBlit(g, texture, rect.x(), rect.y(), rect.w(), rect.h(),
                0f, 0f, info.width(), info.height(), info, layer.opacity());
        } else if (reveal > 0.0f) {
            drawWatercolorSpread(g, texture, info, rect, layer, index, reveal);
        }
        return true;
    }

    private CoverRect coverRect(ImageInfo info, OpenCrisisScreenPayload.ArtLayer layer,
                                int index, int mouseX, int mouseY, float partialTick) {
        float baseScale = Math.max(this.width / (float) info.width(), this.height / (float) info.height());
        float layerScale = Math.max(1.0f, layer.scale());
        int drawW = Math.max(this.width + 2, Math.round(info.width() * baseScale * layerScale));
        int drawH = Math.max(this.height + 2, Math.round(info.height() * baseScale * layerScale));
        float mx = this.width <= 0 ? 0.0f : ((mouseX / (float) this.width) - 0.5f) * 2.0f;
        float my = this.height <= 0 ? 0.0f : ((mouseY / (float) this.height) - 0.5f) * 2.0f;
        float time = (Util.getMillis() - openedAtMs + partialTick * 50.0f) / 1000.0f;
        float parallaxPx = 38.0f * layer.parallax();
        float driftX = (float) Math.sin(time * 0.34f + index * 1.73f) * layer.driftX();
        float driftY = (float) Math.cos(time * 0.27f + index * 2.11f) * layer.driftY();
        int x = Math.round((this.width - drawW) * 0.5f - mx * parallaxPx + driftX);
        int y = Math.round((this.height - drawH) * 0.5f - my * parallaxPx * 0.55f + driftY);
        return new CoverRect(x, y, drawW, drawH);
    }

    private float revealProgress(OpenCrisisScreenPayload.ArtLayer layer) {
        long elapsed = Util.getMillis() - openedAtMs - scaledMs(layer.revealDelayMs());
        if (elapsed <= 0L) return 0.0f;
        float raw = Math.min(1.0f, elapsed / (float) Math.max(80, scaledMs(layer.revealDurationMs())));
        return smoothstep(raw);
    }

    private void drawWatercolorSpread(GuiGraphics g, ResourceLocation texture, ImageInfo info,
                                      CoverRect rect, OpenCrisisScreenPayload.ArtLayer layer,
                                      int layerIndex, float reveal) {
        if (drawLiquidRevealShader(g, texture, rect, layer, layerIndex, reveal)) return;
        drawLayerBlit(g, texture, rect.x(), rect.y(), rect.w(), rect.h(),
            0f, 0f, info.width(), info.height(), info,
            layer.opacity() * clamp(reveal * 1.15f, 0.0f, 1.0f));
    }

    private boolean drawLiquidRevealShader(GuiGraphics g, ResourceLocation texture, CoverRect rect,
                                           OpenCrisisScreenPayload.ArtLayer layer, int layerIndex,
                                           float reveal) {
        ShaderInstance shader = CrisisRevealShaders.crisisReveal();
        if (shader == null) return false;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, texture);
        setUniform(shader, "RevealProgress", reveal);
        setUniform(shader, "RevealTime", (Util.getMillis() - openedAtMs) / 1000.0f * PRESENTATION_SPEED);
        setUniform(shader, "LayerSeed", layerIndex + 1.0f);
        setUniform(shader, "LayerAlpha", clamp(layer.opacity(), 0.0f, 1.0f));
        setUniform(shader, "Aspect", rect.h() <= 0 ? 1.0f : rect.w() / (float) rect.h());

        Matrix4f matrix = g.pose().last().pose();
        BufferBuilder buffer = Tesselator.getInstance()
            .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(matrix, rect.x(), rect.y() + rect.h(), 0).setUv(0.0f, 1.0f);
        buffer.addVertex(matrix, rect.x() + rect.w(), rect.y() + rect.h(), 0).setUv(1.0f, 1.0f);
        buffer.addVertex(matrix, rect.x() + rect.w(), rect.y(), 0).setUv(1.0f, 0.0f);
        buffer.addVertex(matrix, rect.x(), rect.y(), 0).setUv(0.0f, 0.0f);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        return true;
    }

    private static void setUniform(ShaderInstance shader, String name, float value) {
        com.mojang.blaze3d.shaders.Uniform uniform = shader.getUniform(name);
        if (uniform != null) uniform.set(value);
    }

    private static void playUiSound(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    private void drawLayerBlit(GuiGraphics g, ResourceLocation texture, int x, int y, int w, int h,
                               float u, float v, int uw, int vh, ImageInfo info, float alpha) {
        if (w <= 0 || h <= 0 || alpha <= 0.0f) return;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, clamp(alpha, 0.0f, 1.0f));
        g.blit(texture, x, y, w, h, u, v, uw, vh, info.width(), info.height());
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private void drawWatercolorGround(GuiGraphics g) {
        g.fillGradient(0, 0, this.width, this.height, 0xFF0A0B0A, 0xFF17120E);
        g.fillGradient(0, 0, this.width, this.height, 0x331F302D, 0x22110B07);
    }

    private void updateChoiceButtonAnimations() {
        if (!payload.awaitingChoice()) return;
        int left = choiceLeft();
        int top = choiceTop();
        int buttonW = choiceWidth(left);
        for (int i = 0; i < choiceButtons.size(); i++) {
            Button button = choiceButtons.get(i);
            float p = choiceProgress(i);
            int dx = Math.round((1.0f - p) * 28.0f);
            button.setX(left - dx);
            button.setY(top + i * CHOICE_ROW_H);
            button.setWidth(buttonW);
            button.visible = p > 0.03f;
            button.active = !exiting && (payload.canChoose() || payload.canAdvise()) && p > 0.92f;
        }
        if (waitingButton != null) {
            float p = choiceProgress(choiceButtons.size());
            int dx = Math.round((1.0f - p) * 28.0f);
            waitingButton.setX(left - dx);
            waitingButton.setY(top + choiceButtons.size() * CHOICE_ROW_H + 8);
            waitingButton.setWidth(buttonW);
            waitingButton.visible = p > 0.03f;
            waitingButton.active = !exiting && p > 0.92f;
        }
    }

    private void drawFallbackBackground(GuiGraphics g) {
        g.fill(0, 0, this.width, this.height, 0xFF050506);
        int horizon = (int) (this.height * 0.58f);
        g.fillGradient(0, 0, this.width, this.height, 0xFF090B0E, 0xFF0F1718);
        g.fill(0, horizon, this.width, this.height, 0xCC111414);
        int right = Math.max(this.width / 2 + 80, this.width - 380);
        g.fillGradient(right, 0, this.width, this.height, 0x002B3738, 0xAA263334);
        for (int i = 0; i < 9; i++) {
            int x = (i * 173 + 47) % Math.max(1, this.width);
            int w = 80 + (i * 29) % 150;
            int alpha = 32 + (i * 9) % 38;
            g.fill(x - w / 2, horizon - 40 - i * 8, x + w / 2, horizon + 8 + i * 3,
                (alpha << 24) | 0xD8D8D8);
        }
    }

    private ResourceLocation backgroundArt() {
        if (!payload.background().isBlank()) {
            ResourceLocation parsed = ResourceLocation.tryParse(payload.background());
            if (parsed != null) return parsed;
        }
        return DEFAULT_CRISIS_ART;
    }

    private boolean hasResource(ResourceLocation art) {
        return Minecraft.getInstance().getResourceManager().getResource(art).isPresent();
    }

    private static ImageInfo imageInfo(ResourceLocation texture) {
        return IMAGE_INFO.computeIfAbsent(texture, CrisisScreen::readImageInfo);
    }

    private static ImageInfo readImageInfo(ResourceLocation texture) {
        try {
            Resource resource = Minecraft.getInstance().getResourceManager().getResource(texture).orElse(null);
            if (resource == null) return ImageInfo.missing();
            try (NativeImage image = NativeImage.read(resource.open())) {
                return new ImageInfo(true, Math.max(1, image.getWidth()), Math.max(1, image.getHeight()));
            }
        } catch (Exception ex) {
            return ImageInfo.missing();
        }
    }

    private static float smoothstep(float value) {
        float t = clamp(value, 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private int uiStartMs() {
        return artUiStartMs() + UI_AFTER_ART_MS;
    }

    private int artUiStartMs() {
        List<OpenCrisisScreenPayload.ArtLayer> layers = payload.backgroundLayers();
        if (!layers.isEmpty()) {
            OpenCrisisScreenPayload.ArtLayer primary = layers.get(0);
            if (primary != null) {
                return primary.revealDelayMs() + Math.round(primary.revealDurationMs() * 0.68f);
            }
        }
        return 920;
    }

    private float uiProgress(int delayMs, int durationMs) {
        long elapsed = Util.getMillis() - openedAtMs - scaledMs(delayMs);
        if (elapsed <= 0L) return 0.0f;
        return PolishedScreen.easeOutCubic(Math.min(1.0f, elapsed / (float) Math.max(1, scaledMs(durationMs))))
            * exitVisibility();
    }

    private float choiceProgress(int index) {
        return uiProgress(uiStartMs() + 980 + index * 130, 430);
    }

    private static int scaledMs(int ms) {
        return Math.max(1, Math.round(ms / PRESENTATION_SPEED));
    }

    private float exitVisibility() {
        if (!exiting) return 1.0f;
        float raw = Math.min(1.0f, (Util.getMillis() - exitStartedAtMs) / (float) EXIT_ANIMATION_MS);
        return 1.0f - PolishedScreen.easeOutCubic(raw);
    }

    private int drawSlidingWrapped(GuiGraphics g, String text, int x, int y, int maxWidth,
                                   int color, float progress) {
        int dx = Math.round((1.0f - progress) * 22.0f);
        return drawWrapped(g, this.font, Component.literal(text), x - dx, y, maxWidth,
            fadeColor(color, progress));
    }

    private void drawSlidingString(GuiGraphics g, String text, int x, int y, int color,
                                   float progress, boolean shadow) {
        int dx = Math.round((1.0f - progress) * 22.0f);
        g.drawString(this.font, text, x - dx, y, fadeColor(color, progress), shadow);
    }

    private static int fadeColor(int argb, float progress) {
        int alpha = Math.round(((argb >>> 24) & 0xFF) * clamp(progress, 0.0f, 1.0f));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int choiceLeft() {
        return Math.max(26, this.width / 2 - 430);
    }

    private int choiceWidth(int left) {
        return Math.min(430, Math.max(280, this.width - left - 60));
    }

    private int choiceTop() {
        int rows = payload.awaitingChoice() ? payload.choices().size() : 1;
        int needed = rows * CHOICE_ROW_H + 64;
        int preferred = Math.max(245, this.height / 2 + 46);
        return Math.max(148, Math.min(preferred, this.height - needed));
    }

    private record ImageInfo(boolean exists, int width, int height) {
        static ImageInfo missing() {
            return new ImageInfo(false, 16, 9);
        }
    }

    private record CoverRect(int x, int y, int w, int h) {
    }
}
