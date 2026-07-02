package com.bannerbound.antiquity.entity;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.item.HideQuality;
import com.bannerbound.antiquity.tannery.HideGrading;
import com.bannerbound.antiquity.tannery.Hides;
import com.bannerbound.core.api.herder.HerderHooks;

import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Antiquity's herder harvest behaviour: a culled domesticated animal yields its per-species raw HIDE
 * (the tannery's input), quality graded by the pen's living conditions and the herder's skill. The
 * five hide species map via {@link Hides}; other species yield no hide. Installed once at common
 * setup, mirroring {@code AntiquityHunterHooks}.
 */
public final class AntiquityHerderHooks implements HerderHooks.Extension {
    @Override
    public ItemStack herdHide(Animal victim, double livingConditions, int herderXp) {
        Item hide = Hides.hideFor(victim.getType());
        if (hide == null) return ItemStack.EMPTY;
        HideQuality quality = HideGrading.gradeHerd(livingConditions, herderXp);
        ItemStack stack = new ItemStack(hide);
        stack.set(BannerboundAntiquity.HIDE_QUALITY.get(), quality);
        return stack;
    }
}
