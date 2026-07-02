package com.bannerbound.antiquity.client;

import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.animation.Keyframe;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The Bellows "Push" animation (Blockbench export): the top board tilts down and the spine compresses
 * over one second, then springs back. Played once per jump on the Bellows Block.
 */
@OnlyIn(Dist.CLIENT)
public final class BellowsAnimations {
    private BellowsAnimations() {}

    public static final AnimationDefinition PUSH = AnimationDefinition.Builder.withLength(1.0F)
        .addAnimation("Bellows_Top", new AnimationChannel(AnimationChannel.Targets.ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F),
                AnimationChannel.Interpolations.CATMULLROM),
            new Keyframe(0.125F, KeyframeAnimations.degreeVec(-25.0F, 0.0F, 0.0F),
                AnimationChannel.Interpolations.CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(0.0F, 0.0F, 0.0F),
                AnimationChannel.Interpolations.CATMULLROM)))
        .addAnimation("Bellows_Top", new AnimationChannel(AnimationChannel.Targets.POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0.0F, 0.0F, 0.0F),
                AnimationChannel.Interpolations.CATMULLROM),
            new Keyframe(0.125F, KeyframeAnimations.posVec(0.0F, -1.0F, 0.0F),
                AnimationChannel.Interpolations.CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.posVec(0.0F, 0.0F, 0.0F),
                AnimationChannel.Interpolations.CATMULLROM)))
        .addAnimation("Spine", new AnimationChannel(AnimationChannel.Targets.SCALE,
            new Keyframe(0.0F, KeyframeAnimations.scaleVec(1.0F, 1.0F, 1.0F),
                AnimationChannel.Interpolations.CATMULLROM),
            new Keyframe(0.125F, KeyframeAnimations.scaleVec(1.0F, 0.3F, 1.0F),
                AnimationChannel.Interpolations.CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.scaleVec(1.0F, 1.0F, 1.0F),
                AnimationChannel.Interpolations.CATMULLROM)))
        .build();
}
