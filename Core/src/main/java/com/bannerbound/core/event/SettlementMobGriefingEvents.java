package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;

/**
 * Per-chunk override of vanilla's world-wide {@code mobGriefing} game rule. Whenever a mob is
 * about to do something the rule guards (creeper / ghast / wither explosion block damage,
 * enderman pickup, sheep grass-eating, zombie door breaking, villager farming etc.), this
 * listener consults {@link SettlementData} for the entity's current chunk and {@link
 * EntityMobGriefingEvent#setCanGrief(boolean) denies} the action if the chunk is owned by any
 * settlement. Unclaimed chunks behave per the normal game rule — players exploring outside
 * their territory still see vanilla creeper craters.
 *
 * <p>The lookup is keyed by the entity's <em>current</em> chunk at the time the rule is
 * checked, which for the creeper case is the moment the fuse runs out (creeper is standing on
 * the block it would crater). That's the right anchor: a creeper that wanders into a settlement
 * and explodes there gets neutered; one that explodes a block outside the territory damages it
 * normally.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class SettlementMobGriefingEvents {
    private SettlementMobGriefingEvents() {
    }

    @SubscribeEvent
    public static void onMobGriefing(EntityMobGriefingEvent event) {
        Entity entity = event.getEntity();
        Level level = entity.level();
        if (level.isClientSide()) return;
        if (!(level instanceof ServerLevel sl)) return;
        // SettlementData is overworld-keyed; griefing checks in The Nether / The End fall through
        // to the vanilla game rule (no settlements there anyway, but the lookup would NPE).
        MinecraftServer server = sl.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        long chunkKey = new ChunkPos(entity.blockPosition()).toLong();
        if (data.getByChunk(chunkKey) != null) {
            event.setCanGrief(false);
        }
    }
}
