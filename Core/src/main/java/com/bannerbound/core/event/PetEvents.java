package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.entity.PetBonding;

import net.minecraft.world.entity.animal.Wolf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Re-attaches the {@link com.bannerbound.core.entity.PetFollowCitizenGoal} to citizen-bonded
 * wolves whenever they (re-)enter a level. Runtime-added goals don't survive a reload, so a
 * bonded wolf loaded from disk would otherwise stop following its citizen — this restores the
 * goal from the wolf's persistent {@code bb_pet_settlement} tag.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class PetEvents {
    private PetEvents() {}

    @SubscribeEvent
    public static void onWolfJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof Wolf wolf && PetBonding.isBondedPet(wolf)) {
            PetBonding.ensureFollowGoal(wolf);
        }
    }
}
