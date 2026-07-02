package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import org.joml.Quaternionf;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.poison.Poisons;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

/**
 * Lays a curare-UNCONSCIOUS entity flat on the ground (passed out). One {@link RenderLivingEvent.Pre}
 * handler covers players, citizens and animals alike — it tips the whole model 90° about its forward
 * axis before the renderer draws the body, so it works generically without per-model code. Reads the
 * synced curare deadlines, so no Core import is needed (citizens included).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public final class CurarePoseEvents {
    private CurarePoseEvents() {}

    @SubscribeEvent
    static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (!Poisons.isCurareUnconscious(entity, entity.level().getGameTime())) {
            return;
        }
        // Roll the model 90° onto its SIDE about its own facing axis, pivoting at the feet — works for
        // bipeds (fall on side) AND quadrupeds (knocked over), unlike a forward face-plant which would
        // stand a four-legged mob on its nose. Lift by half the body width first so the side-lying body
        // rests on the ground instead of half-sinking. (Tune the 0.5 if a model floats or clips.)
        PoseStack ps = event.getPoseStack();
        double yaw = Math.toRadians(entity.yBodyRot);
        float fx = (float) -Math.sin(yaw);
        float fz = (float) Math.cos(yaw);
        ps.translate(0.0F, entity.getBbWidth() * 0.5F, 0.0F);
        ps.mulPose(new Quaternionf().rotationAxis((float) Math.toRadians(90.0), fx, 0.0F, fz));
    }
}
