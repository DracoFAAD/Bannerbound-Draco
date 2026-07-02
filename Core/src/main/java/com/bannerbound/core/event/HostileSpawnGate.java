package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.vanilla.VanillaContentState;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * Cancels hostile-mob spawning when vanilla content is stripped
 * ({@link VanillaContentState#isEnabled()} is false). A from-scratch settlement faces its own
 * threats (barbarians, raiders) — not vanilla zombies welling up out of the dark.
 *
 * <p>Only <i>world-driven</i> spawn sources are cancelled (natural, chunk-gen, structure, spawner,
 * patrol, reinforcement, jockey). Deliberate sources — spawn eggs, {@code /summon}, conversions,
 * breeding, etc. — are left alone so admins/creative can still place a hostile to test. Passive
 * animals (and Antiquity's AI-converted wolves/ocelots, which stay {@code CREATURE}) are never
 * touched. This is the broad counterpart to {@code ResourceChunkPopulator.onFinalizeSpawn}, which
 * suppresses only managed farm animals — the two coexist, each cancelling its own targets.
 *
 * <p>{@link #onFinalizeSpawn} only catches <i>fresh</i> spawns — it never fires for a hostile that
 * was written into a saved chunk before the flag took effect (a world played as vanilla first, or
 * mobs that spawned in a border chunk and persisted). Those re-enter the world through
 * {@link #onEntityJoin} with {@link EntityJoinLevelEvent#loadedFromDisk()} true and would otherwise
 * slip through, so we discard them there too. Disk-loaded mobs carry no spawn reason, so the
 * deliberate-placement carve-out can't apply — anything {@code /summon}ed and then saved will be
 * cleared on the next reload, which is the acceptable trade for closing the leak.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class HostileSpawnGate {
    private HostileSpawnGate() {
    }

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (VanillaContentState.isEnabled()) return; // vanilla untouched
        if (!isHostile(event.getEntity())) return;   // leave passive animals alone
        switch (event.getSpawnType()) {
            case NATURAL, CHUNK_GENERATION, STRUCTURE, SPAWNER, PATROL, REINFORCEMENT, JOCKEY ->
                event.setSpawnCancelled(true);
            default -> {
                // SPAWN_EGG, COMMAND, MOB_SUMMONED, BUCKET, DISPENSER, BREEDING, CONVERSION, EVENT,
                // TRIAL_SPAWNER, SPAWNER_EGG, etc. — deliberate placements, left to player/admin.
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (VanillaContentState.isEnabled()) return;     // vanilla untouched
        if (!event.loadedFromDisk()) return;             // fresh spawns handled by onFinalizeSpawn
        if (!isHostile(event.getEntity())) return;       // leave passive animals alone
        event.setCanceled(true);                         // persisted hostile — never let it rejoin
    }

    private static boolean isHostile(Entity e) {
        if (e instanceof Enemy) return true; // marker on zombies, skeletons, creepers, illagers, ...
        return e instanceof Mob mob && mob.getType().getCategory() == MobCategory.MONSTER;
    }
}
