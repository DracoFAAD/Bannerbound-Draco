package com.bannerbound.core.client;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.CitizenGender;
import com.bannerbound.core.api.settlement.Era;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Shared citizen-skin resolution: {@code textures/entity/citizen/<man|woman>_<era>_NN.png}, chosen
 * from gender, era, and a stable variant seed — the same scheme {@link CitizenRenderer} uses for
 * real citizens. Extracted so the decorative crowd ({@link CrowdRenderer}) draws from the exact
 * same art, making its movers visually indistinguishable from real citizens. Falls back to
 * {@code citizen.png} when an era/gender set has no art yet.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class CitizenSkins {
    private static final ResourceLocation FALLBACK =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/entity/citizen.png");
    private static final int MAX_VARIANT_PROBE = 16;
    private static final Map<String, Integer> variantCountCache = new HashMap<>();

    private CitizenSkins() {
    }

    /** Texture for the given gender/era, picking a stable variant from {@code variantSeed}. */
    public static ResourceLocation texture(CitizenGender gender, Era era, int variantSeed) {
        String setKey = gender.texturePrefix() + "_" + era.key();
        int variantCount = variantCountCache.computeIfAbsent(setKey, CitizenSkins::probeVariantCount);
        if (variantCount <= 0) return FALLBACK;
        int variant = Math.floorMod(variantSeed, variantCount) + 1;
        return textureFor(setKey, variant);
    }

    private static int probeVariantCount(String setKey) {
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
