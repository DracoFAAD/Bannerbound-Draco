package com.bannerbound.core.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.DiggerWorkGoal;
import com.bannerbound.core.item.ForemansRodItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Highlights the worker(s) a held Foreman's Rod is targeting, so the player can see which digger
 * they've got selected. Uses the entity's CLIENT-ONLY glowing flag ({@code setGlowingTag}), so the
 * outline shows only to the player holding the rod — not as a real, server-synced glow visible to
 * everyone. A rod bound to one digger lights up that digger; in "all" mode every digger lights up.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class ForemanGlowHandler {
    /** How far around the player to (un)light workers. */
    private static final double RANGE = 64.0;

    private ForemanGlowHandler() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return;

        ItemStack rod = heldRod(player);
        String wsType = rod == null ? null : rod.get(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get());
        boolean diggerRod = ForemansRodItem.DIGGER_TYPE.equals(wsType);
        String targetUuid = rod == null ? null : rod.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
        boolean allMode = targetUuid == null || targetUuid.isEmpty();

        AABB box = player.getBoundingBox().inflate(RANGE);
        List<CitizenEntity> citizens = level.getEntitiesOfClass(CitizenEntity.class, box);
        for (CitizenEntity c : citizens) {
            boolean glow = diggerRod
                && c.isClientJob(DiggerWorkGoal.JOB_TYPE_ID)
                && (allMode || targetUuid.equals(c.getUUID().toString()));
            // Set every tick so the glow clears the instant the rod is put away or retargeted, and
            // is re-applied after any entity-data resync that would clear the shared flag.
            c.setClientGlow(glow);
        }
    }

    private static ItemStack heldRod(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.is(BannerboundCore.FOREMANS_ROD.get())) return main;
        ItemStack off = player.getOffhandItem();
        if (off.is(BannerboundCore.FOREMANS_ROD.get())) return off;
        return null;
    }
}
