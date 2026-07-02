package com.bannerbound.antiquity.entity;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;

/**
 * A hittable sub-box of a {@link RaftEntity}. The raft is long, but a single entity AABB is a square
 * that can't rotate, so we hang a few of these parts on it (two for the deck halves, one for the bow
 * notch) and reposition them with the raft each tick — giving a raft-shaped, rotating click/attack
 * surface. Damage and interaction route back to the parent: the notch part ties the tow rope, the
 * deck parts board the raft. NeoForge includes part entities in {@code Level#getEntities}, so they
 * are picked for interaction exactly like a normal entity.
 */
public class RaftPart extends PartEntity<RaftEntity> {
    public enum Role { DECK, NOTCH }

    private final EntityDimensions size;
    public final Role role;

    public RaftPart(RaftEntity parent, Role role, float width, float height) {
        super(parent);
        this.role = role;
        this.size = EntityDimensions.scalable(width, height);
        this.refreshDimensions();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    /** The deck halves are solid so you can stand and walk on the raft (the notch box is not). The
     *  parent excludes these from its own collision so it doesn't get stuck on itself. A GHOST raft
     *  (a fisher NPC's conjured vessel) is pure scenery — nothing about it collides. */
    @Override
    public boolean canBeCollidedWith() {
        return this.role == Role.DECK && !this.getParent().isGhost();
    }

    /** Ghost rafts aren't clickable/attackable either — the player can't reach the parent through us. */
    @Override
    public boolean isPickable() {
        return !this.isRemoved() && !this.getParent().isGhost();
    }

    /** Treat the part as its parent for "is this that entity?" checks (e.g. leash/passenger tests). */
    @Override
    public boolean is(Entity entity) {
        return this == entity || this.getParent() == entity;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return this.isInvulnerableTo(source) ? false : this.getParent().hurt(source, amount);
    }

    @Override
    public InteractionResult interactAt(Player player, Vec3 hitVec, InteractionHand hand) {
        return this.role == Role.NOTCH
            ? this.getParent().tieRope(player, hand)
            : this.getParent().interact(player, hand);
    }

    @Override
    public ItemStack getPickResult() {
        return this.getParent().getPickResult();
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return this.size;
    }

    /** Parts aren't saved or spawned on their own — the parent recreates them. */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity entity) {
        throw new UnsupportedOperationException();
    }
}
