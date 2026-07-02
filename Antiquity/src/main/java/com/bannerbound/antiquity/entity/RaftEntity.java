package com.bannerbound.antiquity.entity;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

/**
 * A primitive raft. It extends vanilla {@link Boat} so it inherits, for free, the water
 * float/sink physics, WASD steering, paddle sounds, mount/dismount and the leash/elastic-drag
 * plumbing (the vanilla controllers key off {@code instanceof Boat}). Unlike a vanilla boat it is
 * not crafted/placed: it's a formed structure (a line of 3 thatch blocks, right-clicked with an
 * oar — see AntiquityEvents) and it carries its own thatch "integrity" health:
 * <ul>
 *   <li>It takes damage like a boat but is destroyed by integrity, not vanilla's 40-damage rule.</li>
 *   <li>Integrity is shown by how low it floats — {@link RaftRenderer} lerps the float height from
 *       1.8 (full) down to 1.2 (wrecked). The value lives here; the renderer reads it.</li>
 *   <li>Right-click with a thatch bundle repairs it (happy-villager particles).</li>
 *   <li>When destroyed it drops 2–3 thatch blocks.</li>
 * </ul>
 */
public class RaftEntity extends Boat {
    /** Synced thatch integrity, [0, MAX_HEALTH]. Drives the destroy threshold and the float height. */
    private static final EntityDataAccessor<Float> DATA_HEALTH =
        SynchedEntityData.defineId(RaftEntity.class, EntityDataSerializers.FLOAT);
    /** Synced: was the tow rope a fiber rope (true) or a vanilla lead (false)? Picks the rope visual. */
    private static final EntityDataAccessor<Boolean> DATA_FIBER_LEASH =
        SynchedEntityData.defineId(RaftEntity.class, EntityDataSerializers.BOOLEAN);
    /** Synced: a "ghost" raft is non-interactable scenery — a fisher NPC's conjured vessel. It floats
     *  and animates like a real raft but can't be damaged, repaired, boarded, or destroyed by the
     *  player, and drops nothing. Synced so the client also refuses the predicted mount/repair. */
    private static final EntityDataAccessor<Boolean> DATA_GHOST =
        SynchedEntityData.defineId(RaftEntity.class, EntityDataSerializers.BOOLEAN);

    /** Full integrity. Tunable; sets how many hits + how much each repair matters. Sturdy enough to
     *  shrug off a handful of spear/arrow hits before it starts wrecking. */
    public static final float MAX_HEALTH = 100.0F;
    /** Each incoming hit removes (raw damage × this) integrity — a spear/arrow chips ~12–18. */
    private static final float HIT_DAMAGE_SCALE = 6.0F;
    /** Integrity restored per thatch bundle (4 bundles fully repair a wreck). */
    private static final float REPAIR_AMOUNT = 25.0F;
    /** Fraction of horizontal speed a fully-wrecked raft keeps each tick (full integrity = 1.0). A
     *  value below 1 lowers its terminal speed, so a battered raft is sluggish. */
    private static final float WRECKED_SPEED_FACTOR = 0.86F;

    // ── Leash tie point: the bow "notch" ────────────────────────────────────────────────────────
    // A fiber rope / lead ties on here, and you must look AT this point to attach. Offset is in
    // entity-local space (X sideways, Y up, Z toward the bow). If the rope grabs/renders at the
    // STERN, flip NOTCH_FORWARD's sign; nudge NOTCH_UP if the knot floats above/below the notch.
    private static final double NOTCH_FORWARD = 3.0;
    private static final double NOTCH_UP = 0.6;
    private static final double NOTCH_SIDE = 0.0;

    /** Hittable sub-boxes (a row of deck boxes + the bow notch), repositioned with the raft each tick. */
    private final RaftPart[] parts;
    /** Number of deck boxes along the hull. A square AABB can't rotate, so several small boxes tile the
     *  length and approximate the hull much better than two big squares when the raft sits diagonally. */
    private static final int DECK_PART_COUNT = 5;
    /** Footprint of each deck box (≈ the walkable interior width). Smaller = tighter fit when turned. */
    private static final float DECK_PART_SIZE = 1.1F;
    /** Deck boxes are thin slabs (floor only) so their walls don't shove you when the raft bobs. */
    private static final float DECK_PART_HEIGHT = 0.2F;
    /** Half-length the row of deck boxes spans (blocks, bow ↔ stern). */
    private static final double DECK_SPAN = 1.2;
    /** Height of the model's deck floor (blocks) — the geometry the renderer lift is measured against.
     *  The walkable surface height is {@code renderFloatHeight() - this}, so collision tracks the
     *  visible deck (including the damage-sink), instead of a fixed guess. */
    private static final double MODEL_DECK_Y = 1.369;
    /** Render-side float curve: full integrity rides at 1.7, a wreck sinks to 1.2; on land the model
     *  drops 0.35 so it sits on the ground instead of hovering. Used by BOTH the renderer (model lift)
     *  and the collision (deck-part height) so the two never disagree. */
    public float renderFloatHeight() {
        float f = Mth.lerp(this.getIntegrityFraction(), 1.2F, 1.7F);
        if (!this.isOnWater()) {
            f -= 0.35F;
        }
        return f;
    }

    /** Height above the entity origin of the walkable deck surface (tracks the visible deck). */
    private double deckSurfaceY() {
        return this.renderFloatHeight() - MODEL_DECK_Y;
    }
    /** Server-only: the rope-fence post currently showing its "roped" model because a fiber-leashed
     *  raft is hitched to it (so we can revert it on untie). */
    private BlockPos ropedPostPos = null;
    /** Server-only: ticks a GHOST raft has sat with no passenger. Conjured vessels are owned by the
     *  fisher's work goal; if one is ever orphaned (fisher died at sea, dismounted on a reload with
     *  no resumable trip, a missed cleanup path), it despawns itself instead of littering the sea. */
    private int ghostEmptyTicks;
    /** An empty ghost raft despawns after this long (~5s) — long enough for boarding to land. */
    private static final int GHOST_EMPTY_DESPAWN_TICKS = 100;
    /** Server-only: ticks into the current AI paddle stroke — drives the ghost raft's own paddle
     *  splash (vanilla's never fires for an AI-rowed boat; see the ghost branch in {@link #tick}). */
    private int paddleSoundTicks;

    public RaftEntity(EntityType<? extends Boat> entityType, Level level) {
        super(entityType, level);
        RaftPart[] built = new RaftPart[DECK_PART_COUNT + 1];
        for (int i = 0; i < DECK_PART_COUNT; i++) {
            // Thin slabs: a flat floor, not a wall. Their sides sit below your feet, so walking into
            // one can't shove you sideways — that lateral push (each tick, as the boxes teleport to
            // follow the bobbing raft) was the jitter. You stand on the top; there's nothing to bump.
            built[i] = new RaftPart(this, RaftPart.Role.DECK, DECK_PART_SIZE, DECK_PART_HEIGHT);
        }
        built[DECK_PART_COUNT] = new RaftPart(this, RaftPart.Role.NOTCH, 0.5F, 0.5F);
        this.parts = built;
        // Reserve a contiguous block of entity ids (parent + parts) so the client recreates the parts
        // with matching ids — the vanilla EnderDragon multipart idiom (see #setId).
        this.setId(ENTITY_COUNTER.getAndAdd(this.parts.length + 1) + 1);
    }

    @Override
    public void setId(int id) {
        super.setId(id);
        if (this.parts != null) { // null during the super() constructor's own field init
            for (int i = 0; i < this.parts.length; i++) {
                this.parts[i].setId(id + i + 1);
            }
        }
    }

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public net.neoforged.neoforge.entity.PartEntity<?>[] getParts() {
        return this.parts;
    }

    /** Spawn constructor used when an oar forms a raft from thatch (see AntiquityEvents). */
    public RaftEntity(Level level, double x, double y, double z) {
        this(BannerboundAntiquity.RAFT.get(), level);
        this.setPos(x, y, z);
        this.xo = x;
        this.yo = y;
        this.zo = z;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_HEALTH, MAX_HEALTH);
        builder.define(DATA_FIBER_LEASH, true);
        builder.define(DATA_GHOST, false);
    }

    /** True for a fisher NPC's conjured, non-interactable vessel (see {@link #DATA_GHOST}). */
    public boolean isGhost() {
        return this.entityData.get(DATA_GHOST);
    }

    public void setGhost(boolean ghost) {
        this.entityData.set(DATA_GHOST, ghost);
    }

    /** Spawn a ghost raft (non-interactable scenery a fisher rides), already added to the level. */
    public static RaftEntity spawnGhost(ServerLevel level, double x, double y, double z, float yaw) {
        RaftEntity raft = new RaftEntity(level, x, y, z);
        raft.setGhost(true);
        raft.setYRot(yaw);
        raft.yRotO = yaw;
        if (!level.addFreshEntity(raft)) {
            return null;
        }
        return raft;
    }

    /** True when the tow rope is a fiber rope (green ribbon); false for a vanilla lead (brown). */
    public boolean isFiberLeash() {
        return this.entityData.get(DATA_FIBER_LEASH);
    }

    public void setFiberLeash(boolean fiber) {
        this.entityData.set(DATA_FIBER_LEASH, fiber);
    }

    public float getRaftHealth() {
        return this.entityData.get(DATA_HEALTH);
    }

    public void setRaftHealth(float health) {
        this.entityData.set(DATA_HEALTH, Mth.clamp(health, 0.0F, MAX_HEALTH));
    }

    /** 0 = wrecked, 1 = full. Used by the renderer to pick the float height. */
    public float getIntegrityFraction() {
        return Mth.clamp(this.getRaftHealth() / MAX_HEALTH, 0.0F, 1.0F);
    }

    @Override
    public void tick() {
        super.tick();
        // Boat never ticks its own leash (only Mobs do), so drive it here: this resolves a saved
        // holder and runs the close/elastic/too-far distance behaviour (Boat#elasticRangeLeashBehaviour
        // hauls the raft toward the holder past 6 blocks). Server-authoritative.
        if (!this.level().isClientSide) {
            Leashable.tickLeash(this);
            this.reconcileRopedPost();
            if (this.isGhost()) {
                // Orphaned ghost vessels clean themselves up (see ghostEmptyTicks).
                ghostEmptyTicks = this.getPassengers().isEmpty() ? ghostEmptyTicks + 1 : 0;
                if (ghostEmptyTicks > GHOST_EMPTY_DESPAWN_TICKS) {
                    this.discard();
                    return;
                }
                // A ghost raft is rowed by an AI goal that can't supply paddle input — and vanilla
                // Boat#tick force-CLEARS the paddle state every tick when the first passenger isn't a
                // player. The goal's setPaddleState fought that clear, so the synced flag flickered
                // and the client snapped between the rowing loop and the rest pose ("jittering oars").
                // Derive the state from actual hull motion instead, here AFTER super.tick's clear, so
                // the end-of-tick synced value is stable regardless of entity tick order.
                Vec3 motion = this.getDeltaMovement();
                boolean rowing = motion.x * motion.x + motion.z * motion.z > 0.0025; // > ~0.05 b/t
                this.setPaddleState(rowing, rowing);
                // Vanilla's paddle splash never fires for an AI-rowed boat (the server clears the
                // paddle state before its own sound check; the client's playSound(null, …) no-ops),
                // so play the stroke ourselves at the vanilla cadence (one full stroke ≈ 16 ticks).
                if (rowing) {
                    if (++paddleSoundTicks >= 16) {
                        paddleSoundTicks = 0;
                        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                            net.minecraft.sounds.SoundEvents.BOAT_PADDLE_WATER, this.getSoundSource(),
                            1.0F, 0.8F + 0.4F * this.random.nextFloat());
                    }
                } else {
                    paddleSoundTicks = 12;   // first splash lands shortly after setting off
                }
            }
        }
        // Boat physics only run on the controlling instance (server for an empty raft, the riding
        // client otherwise); match that so we don't double-apply or fight the synced position.
        if (this.isControlledByLocalInstance()) {
            this.applyBuoyancy();
            this.applyDamageSlowdown();
        }
        // Drag the hit-parts along (both sides: the client needs them for picking, the server for
        // attack/projectile hits).
        this.positionParts();
    }

    /** Move the deck + notch hit-parts to follow the raft's position and yaw. */
    private void positionParts() {
        // Put the deck boxes' TOP at the visible deck (which rises/sinks with float height), so the
        // walkable surface always matches the model instead of drifting underwater.
        double deckBottom = this.deckSurfaceY() - this.parts[0].getBbHeight();
        for (int i = 0; i < DECK_PART_COUNT; i++) {
            double t = (double) i / (DECK_PART_COUNT - 1);            // 0 (bow) .. 1 (stern)
            double forward = Mth.lerp(t, DECK_SPAN, -DECK_SPAN);
            placePart(this.parts[i], 0.0, deckBottom, forward);
        }
        Vec3 n = this.getNotchPosition(1.0F);
        RaftPart notch = this.parts[DECK_PART_COUNT];
        notch.xo = notch.getX();
        notch.yo = notch.getY();
        notch.zo = notch.getZ();
        notch.setPos(n.x, n.y - notch.getBbHeight() / 2.0, n.z); // centre the small box on the notch
    }

    private void placePart(RaftPart part, double sideways, double vertical, double forward) {
        Vec3 off = new Vec3(sideways, 0.0, forward).yRot(-this.getYRot() * ((float) Math.PI / 180.0F));
        part.xo = part.getX();
        part.yo = part.getY();
        part.zo = part.getZ();
        part.setPos(this.getX() + off.x, this.getY() + vertical + off.y, this.getZ() + off.z);
    }

    /**
     * Keep a rope-fence post's {@code roped} model in sync with a fiber-leashed raft hitched to it,
     * so it shows its "with rope" variant. The raft tie is a vanilla leash knot (not the mod's
     * post-to-post tie), so the blockstate isn't driven automatically — we force it here while tied
     * and let {@link RopeTies#refreshRoped} restore the real state when the raft leaves.
     */
    private void reconcileRopedPost() {
        BlockPos target = null;
        if (this.isFiberLeash()
                && this.getLeashHolder() instanceof net.minecraft.world.entity.decoration.LeashFenceKnotEntity knot) {
            BlockPos kp = knot.getPos();
            if (this.level().getBlockState(kp).getBlock()
                    instanceof com.bannerbound.antiquity.block.RopeFencePostBlock) {
                target = kp;
            }
        }
        if (!java.util.Objects.equals(target, this.ropedPostPos)) {
            if (this.ropedPostPos != null) {
                com.bannerbound.antiquity.RopeTies.refreshRoped(this.level(),
                    new com.bannerbound.antiquity.RopeAnchor(this.ropedPostPos, 0));
            }
            this.ropedPostPos = target;
        }
        if (target != null) {
            // Re-assert each tick (a no-op once set) so it can't be left off by an unrelated refresh.
            com.bannerbound.antiquity.RopeTies.setRopedModel(this.level(),
                new com.bannerbound.antiquity.RopeAnchor(target, 0), true);
        }
    }

    /** World-space position of the bow notch (the rope tie point), interpolated for rendering. */
    public Vec3 getNotchPosition(float partialTick) {
        double ex = Mth.lerp(partialTick, this.xo, this.getX());
        double ey = Mth.lerp(partialTick, this.yo, this.getY());
        double ez = Mth.lerp(partialTick, this.zo, this.getZ());
        float yaw = Mth.lerp(partialTick, this.yRotO, this.getYRot());
        Vec3 local = new Vec3(NOTCH_SIDE, NOTCH_UP, NOTCH_FORWARD).yRot(-yaw * ((float) Math.PI / 180.0F));
        return new Vec3(ex + local.x, ey + local.y, ez + local.z);
    }

    /**
     * Attach / detach the tow rope. Called when the dedicated bow-notch hit-part is clicked (see
     * {@link RaftPart}), so there's no "am I aiming at the notch" check here — clicking the notch box
     * IS the aim. A fiber rope or lead leashes the raft to you; a bare-hand tug while you hold the
     * rope drops it back. Anything else passes (so the notch click does nothing rather than mounting).
     */
    public InteractionResult tieRope(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        boolean rope = stack.is(Items.LEAD) || stack.is(BannerboundAntiquity.FIBER_ROPE.get());
        if (rope && !this.isLeashed()) {
            if (!this.level().isClientSide) {
                this.setFiberLeash(stack.is(BannerboundAntiquity.FIBER_ROPE.get()));
                this.setLeashedTo(player, true);
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide());
        }
        if (stack.isEmpty() && this.getLeashHolder() == player) {
            if (!this.level().isClientSide) {
                // No item drop: attaching never consumed the rope, so detaching mustn't mint one.
                this.dropLeash(true, false);
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide());
        }
        return InteractionResult.PASS;
    }

    /** A raft on a rope shouldn't snap free — past the break distance keep hauling it in instead of
     *  dropping the leash (capped so a far holder doesn't yank it violently). */
    @Override
    public void leashTooFarBehaviour() {
        Entity holder = this.getLeashHolder();
        if (holder != null) {
            this.elasticRangeLeashBehaviour(holder, (float) Math.min(this.distanceTo(holder), 9.0));
        }
    }

    /**
     * Genuine buoyancy: float up to the water surface even when the raft is resting on a shallow
     * bottom. Vanilla treats "sitting on a block under shallow water" as ON_LAND and won't lift it
     * (that's why a raft formed in water sank). We find the water surface in the raft's column and
     * ease the hull up to it. Only ever pushes up, so it can't fight gravity on dry land.
     */
    private void applyBuoyancy() {
        double surface = Double.NaN;
        BlockPos base = this.blockPosition();
        for (int dy = -1; dy <= 1; dy++) {
            BlockPos p = base.above(dy);
            var fluid = this.level().getFluidState(p);
            if (fluid.is(FluidTags.WATER)) {
                surface = p.getY() + fluid.getHeight(this.level(), p);
            }
        }
        if (Double.isNaN(surface)) {
            return; // no water around the hull — nothing to float on
        }
        double target = surface - 0.15; // hull rides a touch into the water
        if (this.getY() < target) {
            Vec3 v = this.getDeltaMovement();
            double lift = Math.min((target - this.getY()) * 0.2, 0.12);
            if (v.y < lift) {
                this.setDeltaMovement(v.x, lift, v.z);
            }
        }
    }

    /** A battered raft is sluggish: shave horizontal speed as integrity drops (full = no change). */
    private void applyDamageSlowdown() {
        float factor = Mth.lerp(this.getIntegrityFraction(), WRECKED_SPEED_FACTOR, 1.0F);
        if (factor < 1.0F) {
            Vec3 v = this.getDeltaMovement();
            this.setDeltaMovement(v.x * factor, v.y, v.z * factor);
        }
    }

    /**
     * Boat-style damage handling, but destruction is driven by our integrity, not vanilla's 40-damage
     * rule. We still set the transient {@code damage}/{@code hurtTime} so the renderer's hurt wobble
     * plays; that value decays each tick in {@link Boat#tick()}.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isGhost()) {
            return false; // conjured scenery — can't be damaged or destroyed
        }
        if (this.level().isClientSide || this.isRemoved()) {
            return true;
        }
        if (this.isInvulnerableTo(source)) {
            return false;
        }
        this.setHurtDir(-this.getHurtDir());
        this.setHurtTime(10);
        this.markHurt();
        this.setDamage(this.getDamage() + amount * 10.0F); // visual wobble only
        this.gameEvent(GameEvent.ENTITY_DAMAGE, source.getEntity());
        if (source.getEntity() instanceof Player player && player.getAbilities().instabuild) {
            this.discard();
            return true;
        }
        this.setRaftHealth(this.getRaftHealth() - amount * HIT_DAMAGE_SCALE);
        if (this.getRaftHealth() <= 0.0F) {
            this.destroyRaft();
        }
        return true;
    }

    /** Break the raft into 2–3 thatch blocks (no raft item — rafts aren't picked up whole). */
    private void destroyRaft() {
        if (this.level().getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
            int count = 2 + this.random.nextInt(2);
            this.spawnAtLocation(new ItemStack(BannerboundAntiquity.THATCH_ITEM.get(), count));
        }
        this.kill();
    }

    /**
     * Repair with a thatch bundle (consumes one, heals a bit, green happy-villager particles).
     * Anything else falls through to the vanilla boat interaction (mount).
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (this.isGhost()) {
            return InteractionResult.PASS; // can't board, repair, or otherwise touch a conjured raft
        }
        ItemStack stack = player.getItemInHand(hand);
        if (stack.is(BannerboundAntiquity.THATCH_BUNDLE.get()) && this.getRaftHealth() < MAX_HEALTH) {
            if (!this.level().isClientSide) {
                this.setRaftHealth(this.getRaftHealth() + REPAIR_AMOUNT);
                if (!player.hasInfiniteMaterials()) {
                    stack.shrink(1);
                }
                this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                    BannerboundAntiquity.THATCH_PLACE_SOUND, SoundSource.BLOCKS, 1.0F, 1.0F);
                if (this.level() instanceof ServerLevel server) {
                    server.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                        this.getX(), this.getY() + 0.4, this.getZ(), 12, 0.6, 0.3, 0.6, 0.0);
                }
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        // A raft only carries you on water — you can't board it while it's beached. (Repair above
        // still works on land so you can patch it before launching.)
        if (!this.isOnWater()) {
            return InteractionResult.PASS;
        }
        return super.interact(player, hand);
    }

    /**
     * A conjured (ghost) raft is a single fisher's ride: she boards with a FORCED startRiding, which
     * bypasses this check, so any UNFORCED attempt to climb aboard — a farmer or other citizen whose
     * pathing strays onto the vessel — is rejected here. Players and ordinary (non-ghost) rafts keep
     * vanilla behaviour (and the 2-seat cap).
     */
    @Override
    protected boolean canAddPassenger(Entity passenger) {
        if (this.isGhost() && passenger instanceof com.bannerbound.core.entity.CitizenEntity) {
            return false;
        }
        return super.canAddPassenger(passenger);
    }

    /** True when the raft is floating on / sitting in water (so it's boardable). */
    private boolean isOnWater() {
        BlockPos base = this.blockPosition();
        return this.level().getFluidState(base).is(FluidTags.WATER)
            || this.level().getFluidState(base.below()).is(FluidTags.WATER);
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        // Drop the "roped" model we forced onto a post when this raft was hitched to it.
        if (this.ropedPostPos != null && !this.level().isClientSide) {
            com.bannerbound.antiquity.RopeTies.refreshRoped(this.level(),
                new com.bannerbound.antiquity.RopeAnchor(this.ropedPostPos, 0));
            this.ropedPostPos = null;
        }
        super.remove(reason);
    }

    /** Pick result / boat-name source — a raft is made of thatch. */
    @Override
    public Item getDropItem() {
        return BannerboundAntiquity.THATCH_ITEM.get();
    }

    @Override
    protected Component getTypeName() {
        return Component.translatable("entity.bannerboundantiquity.raft");
    }

    /** Rafts don't shatter into planks on a fall (vanilla boat behaviour); they just don't fall-damage. */
    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
        if (onGround) {
            this.resetFallDistance();
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("RaftHealth", this.getRaftHealth());
        tag.putBoolean("FiberLeash", this.isFiberLeash());
        // Ghost survives a save: a sailing fisher's conjured vessel must NOT reload as a real,
        // repairable, thatch-dropping raft (synced data isn't persisted on its own).
        tag.putBoolean("Ghost", this.isGhost());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("RaftHealth")) {
            this.setRaftHealth(tag.getFloat("RaftHealth"));
        }
        if (tag.contains("FiberLeash")) {
            this.setFiberLeash(tag.getBoolean("FiberLeash"));
        }
        if (tag.contains("Ghost")) {
            this.setGhost(tag.getBoolean("Ghost"));
        }
    }

    /** The raft seats three (the storage variant, later, will seat two + a cargo slot). */
    @Override
    protected int getMaxPassengers() {
        return 3;
    }

    /**
     * Seat riders along the deck length (this raft is long), on top of the flat deck. One rider sits
     * centred; two or three spread fore-to-aft so they don't overlap.
     */
    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity entity, EntityDimensions dimensions, float partialTick) {
        int count = this.getPassengers().size();
        int index = this.getPassengers().indexOf(entity);
        double forward; // along the raft (+ = toward the bow)
        if (count <= 1 || index < 0) {
            forward = 0.0;
        } else if (count == 2) {
            forward = index == 0 ? 0.8 : -0.8;
        } else {
            forward = index == 0 ? 1.1 : (index == 1 ? 0.0 : -1.1);
        }
        double seatY = dimensions.height() * 0.8888889F;
        // Citizens sit ~0.7 lower than players would: a player brings its own seated "vehicle
        // attachment" offset, a mob doesn't — the same seat point leaves an NPC hovering above
        // the deck (visible on the sailing fishers).
        if (entity instanceof com.bannerbound.core.entity.CitizenEntity) {
            seatY -= 0.7;
        }
        return new Vec3(0.0, seatY, forward)
            .yRot(-this.getYRot() * (float) (Math.PI / 180.0));
    }

    /** Don't collide with our own hit-parts — they sit on top of us and would otherwise jam movement. */
    @Override
    public boolean canCollideWith(Entity entity) {
        if (entity instanceof RaftPart part && part.getParent() == this) {
            return false;
        }
        return super.canCollideWith(entity);
    }

    /**
     * The hull itself is NOT a standing surface — only the deck hit-parts are (see {@link RaftPart}).
     * A vanilla boat's whole 0.6-tall box is solid, which here sat taller than the deck parts and made
     * the deck "stepped": you'd drop walking to an edge, dismount into the taller box, and snag on
     * destroy. With only the deck parts solid, the walking surface is one uniform height.
     */
    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    /** Step the rider out onto the deck surface (not into the hull or the water beside it). */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        return new Vec3(this.getX(), this.getY() + this.deckSurfaceY() + 0.02, this.getZ());
    }
}
