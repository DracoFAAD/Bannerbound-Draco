package com.bannerbound.antiquity.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Custom {@link RenderType}s for hunting visuals. Subclassing {@code RenderType} is the standard
 * modder trick to reach its {@code protected} {@code create(...)} factory and the inherited
 * {@link net.minecraft.client.renderer.RenderStateShard} constants — the class is never actually
 * instantiated.
 */
@OnlyIn(Dist.CLIENT)
public final class HuntingRenderTypes extends RenderType {
    private HuntingRenderTypes() {
        super(null, null, null, 0, false, false, null, null);
    }

    /**
     * Flat, translucent, full-bright position+color geometry that writes colour but not depth — for
     * the blood-trail direction cone laid on the ground. No texture, so it needs no art asset.
     */
    public static final RenderType BLOOD_CONE = create(
        "bannerbound_blood_cone",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.TRIANGLES,
        1536,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(POSITION_COLOR_SHADER)
            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
            .setCullState(NO_CULL)
            .setDepthTestState(LEQUAL_DEPTH_TEST)
            .setWriteMaskState(COLOR_WRITE)
            .createCompositeState(false));
}
