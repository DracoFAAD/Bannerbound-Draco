package com.bannerbound.core.client;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.CitizenGender;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Renderer for {@link CitizenEntity}. Two things vary per citizen:
 * <ul>
 *   <li><b>Body model</b> — male citizens use the wide (Steve) player model, female citizens the
 *       slim (Alex) model. The active model is swapped in {@link #render} before the super call.</li>
 *   <li><b>Texture</b> — {@code textures/entity/citizen/<man|woman>_<era>_<NN>.png}, chosen from
 *       the citizen's gender, era, and stable variant seed. The number of {@code _NN}
 *       variants per (gender, era) is discovered by probing the resource manager and cached.
 *       The era is the settlement's <i>current</i> era, so citizens restyle as it advances.
 *       When no era/gender texture exists the renderer falls back to {@code citizen.png}.</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class CitizenRenderer extends HumanoidMobRenderer<CitizenEntity, HumanoidModel<CitizenEntity>> {
    /** The citizen whose held item is being rendered right now (set around {@code super.render}). Lets
     *  expansion held-item model wrappers — which only get an {@link net.minecraft.world.item.ItemDisplayContext},
     *  no entity — key their pose on this NPC the way they key the player on {@code Minecraft#player}
     *  (e.g. the Antiquity spear's raise-flip). Render thread only; cleared in a finally. */
    public static CitizenEntity CURRENT_RENDER;

    /** Fallback skin used until the player adds era/gender art. */
    private static final ResourceLocation FALLBACK_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/entity/citizen.png");
    /** Highest {@code _NN} suffix the variant probe will look for. */
    private static final int MAX_VARIANT_PROBE = 16;

    private final HumanoidModel<CitizenEntity> wideModel;
    private final HumanoidModel<CitizenEntity> slimModel;
    /** Cache of {@code "<man|woman>_<era>"} → number of texture variants found on disk. */
    private final Map<String, Integer> variantCountCache = new HashMap<>();

    public CitizenRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5f);
        this.wideModel = this.getModel();
        // PLAYER_SLIM bakes the narrow-arm (Alex) geometry — used for female citizens.
        this.slimModel = new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_SLIM));
        this.addLayer(new HumanoidArmorLayer<>(this,
            new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
            new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
            ctx.getModelManager()));
        // Speech bubble is NOT registered as a layer — it's drawn in render() after super
        // returns so it's in clean world space (the layer-iteration in LivingEntityRenderer
        // runs inside the model flip + translate, which mangles font orientation).
    }

    /** Shrink factor for child citizens — model gets uniformly scaled around the entity's pivot
     *  before the humanoid renderer draws. 0.65 reads as "noticeably smaller" without making
     *  pathfinding bbox collisions awkward. */
    private static final float CHILD_SCALE = 0.65f;

    @Override
    public void render(CitizenEntity entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        // Swap the body model to match the citizen's gender before the humanoid renderer draws.
        // LivingEntityRenderer.model is reassignable; the armor layers keep their own models.
        HumanoidModel<CitizenEntity> body = entity.getGender() == CitizenGender.FEMALE ? slimModel : wideModel;
        this.model = body;
        // Item-use arm poses: generic mob renderers leave the arms EMPTY (the item-use arm pose lives
        // in PlayerRenderer.getArmPose), so a citizen "using" a spear or bow shows no windup. Mirror
        // the player by setting THROW_SPEAR while the held item's use animation is SPEAR (the spear
        // fisher / hunter winding up) and BOW_AND_ARROW while it's BOW (the hunter drawing). Reset
        // both arms every frame since the gender models are shared/reused.
        HumanoidModel.ArmPose usePose = HumanoidModel.ArmPose.EMPTY;
        boolean usedMainHand = true;
        if (entity.isUsingItem()) {
            net.minecraft.world.item.UseAnim anim = entity.getUseItem().getUseAnimation();
            if (anim == net.minecraft.world.item.UseAnim.SPEAR) {
                usePose = HumanoidModel.ArmPose.THROW_SPEAR;
            } else if (anim == net.minecraft.world.item.UseAnim.BOW) {
                usePose = HumanoidModel.ArmPose.BOW_AND_ARROW;
            }
            if (usePose != HumanoidModel.ArmPose.EMPTY) {
                usedMainHand = entity.getUsedItemHand() == net.minecraft.world.InteractionHand.MAIN_HAND;
            }
        }
        boolean rightIsMain = entity.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
        boolean useRightArm = usedMainHand == rightIsMain;
        body.rightArmPose = useRightArm ? usePose : HumanoidModel.ArmPose.EMPTY;
        body.leftArmPose = useRightArm ? HumanoidModel.ArmPose.EMPTY : usePose;
        // Crouch-stalk (the hunter sneaking up on prey): mob renderers don't mirror the player's
        // crouch handling either, so feed the synced pose to the model ourselves every frame.
        body.crouching = entity.isCrouching();
        // Expose this citizen so the held-item model wrapper (which gets no entity) can match its raise
        // pose to this NPC, the way the player wrapper keys on Minecraft#player. Cleared in finally.
        CURRENT_RENDER = entity;
        try {
            if (entity.isChild()) {
                // Scale around the entity-feet pivot so the child stands on the ground, not sinks
                // or floats. Wrapping just the super.render in push/pop keeps the bubble draw below
                // in unscaled world space — bubble already uses entity.getBbHeight() so it follows
                // the bbox (the scale doesn't change bbHeight; SpeechBubbleLayer offsets stay sane).
                poseStack.pushPose();
                poseStack.scale(CHILD_SCALE, CHILD_SCALE, CHILD_SCALE);
                super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
                poseStack.popPose();
            } else {
                super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
            }
        } finally {
            CURRENT_RENDER = null;
        }
        // Bubble drawn AFTER super so we're back in clean world space (no model flip).
        SpeechBubbleLayer.draw(entity, poseStack, buffer, packedLight);
        // Red "!" for a citizen that can't work due to a problem (BLOCKED status) — same clean
        // world-space pose, stacked above the bubble line.
        SpeechBubbleLayer.drawBlocked(entity, poseStack, buffer, packedLight);
        // Occasional angry-villager puff above unhappy citizens so the player can SEE unhappiness
        // at a glance. Client-only and heavily throttled (one in many frames) so it reads as the
        // odd puff, not a cloud.
        maybeEmitUnhappyParticles(entity);
        // ...and the mirror cue: an occasional green happy-villager sparkle above content
        // citizens so a thriving settlement reads at a glance, not just a failing one.
        maybeEmitHappyParticles(entity);
    }

    /** Happiness at/below this (0..100) emits the occasional angry puff. */
    private static final int UNHAPPY_THRESHOLD = 30;
    /** Happiness at/below this puffs noticeably more often. */
    private static final int VERY_UNHAPPY_THRESHOLD = 15;
    /** Happiness at/above this (0..100) emits the occasional happy green sparkle. */
    private static final int HAPPY_THRESHOLD = 80;
    /** Happiness at/above this sparkles a little more often. */
    private static final int VERY_HAPPY_THRESHOLD = 95;

    /**
     * Spawns an occasional {@code ANGRY_VILLAGER} particle above an unhappy citizen's head.
     * Client-side, render-thread only, and probability-gated per frame so it stays subtle:
     * very-unhappy citizens puff a little more often than merely-unhappy ones. Children skipped.
     */
    private static void maybeEmitUnhappyParticles(CitizenEntity entity) {
        if (!entity.level().isClientSide || entity.isChild()) {
            return;
        }
        int happiness = entity.getHappiness();
        if (happiness > UNHAPPY_THRESHOLD) {
            return;
        }
        // Low per-frame chance: ~0.4% for unhappy, ~1% for very unhappy.
        var random = entity.getRandom();
        float chance = happiness <= VERY_UNHAPPY_THRESHOLD ? 0.010f : 0.004f;
        if (random.nextFloat() >= chance) {
            return;
        }
        double jitter = 0.30;
        double x = entity.getX() + (random.nextDouble() - 0.5) * jitter;
        double z = entity.getZ() + (random.nextDouble() - 0.5) * jitter;
        double y = entity.getEyeY() + 0.5;
        entity.level().addParticle(
            net.minecraft.core.particles.ParticleTypes.ANGRY_VILLAGER, x, y, z, 0.0, 0.0, 0.0);
    }

    /**
     * Spawns an occasional {@code HAPPY_VILLAGER} (green sparkle) above a content citizen's head
     * — the positive mirror of {@link #maybeEmitUnhappyParticles}. Rarer than the angry puff so a
     * thriving settlement twinkles gently rather than fogging up with green. Children skipped.
     */
    private static void maybeEmitHappyParticles(CitizenEntity entity) {
        if (!entity.level().isClientSide || entity.isChild()) {
            return;
        }
        int happiness = entity.getHappiness();
        if (happiness < HAPPY_THRESHOLD) {
            return;
        }
        // Low per-frame chance: ~0.25% for happy, ~0.5% for very happy (deliberately below the
        // angry rates so contentment is a subtle ambient cue, not the loud one).
        var random = entity.getRandom();
        float chance = happiness >= VERY_HAPPY_THRESHOLD ? 0.005f : 0.0025f;
        if (random.nextFloat() >= chance) {
            return;
        }
        double jitter = 0.30;
        double x = entity.getX() + (random.nextDouble() - 0.5) * jitter;
        double z = entity.getZ() + (random.nextDouble() - 0.5) * jitter;
        double y = entity.getEyeY() + 0.5;
        entity.level().addParticle(
            net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, x, y, z, 0.0, 0.0, 0.0);
    }

    @Override
    public ResourceLocation getTextureLocation(CitizenEntity entity) {
        CitizenGender gender = entity.getGender();
        Era era = entity.getEra();
        String setKey = gender.texturePrefix() + "_" + era.key();
        int variantCount = variantCountCache.computeIfAbsent(setKey, this::probeVariantCount);
        if (variantCount <= 0) {
            return FALLBACK_TEXTURE;
        }
        // Stable per-citizen variant reduced into the available range for this era/gender.
        int variant = Math.floorMod(entity.getTextureVariant(), variantCount) + 1;
        return textureFor(setKey, variant);
    }

    /** Counts how many {@code <setKey>_NN.png} files exist (contiguous from 01). */
    private int probeVariantCount(String setKey) {
        var resourceManager = Minecraft.getInstance().getResourceManager();
        int count = 0;
        for (int n = 1; n <= MAX_VARIANT_PROBE; n++) {
            if (resourceManager.getResource(textureFor(setKey, n)).isPresent()) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private static ResourceLocation textureFor(String setKey, int variant) {
        return ResourceLocation.fromNamespaceAndPath("bannerbound",
            String.format("textures/entity/citizen/%s_%02d.png", setKey, variant));
    }
}
