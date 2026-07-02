package com.bannerbound.core.client;

import com.bannerbound.core.BannerboundCore;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
@EventBusSubscriber(modid = BannerboundCore.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CrisisRevealShaders {
    private static ShaderInstance crisisReveal;

    private CrisisRevealShaders() {
    }

    public static ShaderInstance crisisReveal() {
        return crisisReveal;
    }

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(
                event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "crisis_reveal"),
                DefaultVertexFormat.POSITION_TEX
            ), shader -> crisisReveal = shader);
        } catch (Exception ex) {
            crisisReveal = null;
            BannerboundCore.LOGGER.warn("Crisis reveal shader failed to load; using simple reveal fallback.", ex);
        }
    }
}
