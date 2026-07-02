package com.bannerbound.antiquity.client;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.entity.RaftEntity;
import com.bannerbound.antiquity.entity.RaftPart;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Makes a raft a proper moving platform underfoot: when the local player is standing on a raft's
 * (solid) deck and not riding it, carry them along by the raft's per-tick movement. The raft's deck
 * is already a hard surface (see {@link RaftPart}), but a moving entity doesn't drag a passenger the
 * way a moving block does, so without this you'd slide off the back the moment it moves. Done on the
 * client for the local player only — the player is client-authoritative, so nudging their position
 * here syncs cleanly with no rubber-banding (mobs / remote players just stand, which is fine).
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public final class RaftWalkEvents {
    private RaftWalkEvents() {
    }

    @SubscribeEvent
    static void onPlayerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LocalPlayer player) || player.isPassenger()) {
            return;
        }
        RaftEntity raft = raftUnderfoot(player);
        if (raft == null) {
            return;
        }
        // Carry the player by the raft's per-tick motion AND rotation: take the player's offset from
        // the raft's OLD centre, spin it by the raft's yaw change, and re-anchor it to the NEW centre.
        // That keeps them planted on the deck whether it slides or turns (translation-only would let a
        // turning raft swing out from under them). We don't rotate their view — they keep aiming where
        // they look, which is what you want for throwing spears off a turning raft.
        double dyaw = net.minecraft.util.Mth.wrapDegrees(raft.getYRot() - raft.yRotO);
        double dx = raft.getX() - raft.xo;
        double dz = raft.getZ() - raft.zo;
        if (dx * dx + dz * dz <= 1.0E-6 && Math.abs(dyaw) <= 1.0E-3) {
            return;
        }
        Vec3 rel = new Vec3(player.getX() - raft.xo, 0.0, player.getZ() - raft.zo)
            .yRot((float) (-dyaw * Math.PI / 180.0));
        player.setPos(raft.getX() + rel.x, player.getY(), raft.getZ() + rel.z);
    }

    /** The raft whose deck the player is standing on, or null. */
    private static RaftEntity raftUnderfoot(LocalPlayer player) {
        AABB box = player.getBoundingBox();
        AABB probe = new AABB(box.minX, box.minY - 0.5, box.minZ, box.maxX, box.minY + 0.05, box.maxZ);
        for (Entity e : player.level().getEntities(player, probe,
                e -> e instanceof RaftEntity || (e instanceof RaftPart p && p.canBeCollidedWith()))) {
            return e instanceof RaftPart part ? part.getParent() : (RaftEntity) e;
        }
        return null;
    }
}
