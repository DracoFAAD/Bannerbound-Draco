package com.bannerbound.core.entity;

import java.util.UUID;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.barbarian.BarbarianCamp;
import com.bannerbound.core.barbarian.BarbarianData;
import com.bannerbound.core.barbarian.BarbarianRangedGoal;
import com.bannerbound.core.barbarian.CampRelationState;
import com.bannerbound.core.barbarian.CampWanderGoal;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * A barbarian camp member — its OWN entity type, subclassing {@link CitizenEntity} so it reuses the
 * citizen model/skin/render and base behaviour, but is a distinct class. Being a separate type means
 * {@code HurtByTargetGoal.setAlertOthers()} rallies only other barbarians (not the player's citizens,
 * which are plain {@code CitizenEntity}), and player citizens target barbarians via a clean
 * {@code instanceof} check. All barbarian-specific state/AI lives here, off {@code CitizenEntity}.
 *
 * <p>Barbarians run a bespoke {@link #registerGoals} (combat + camp wander + hostile targeting) — not
 * the citizen work/patrol/panic set (citizens flee when hurt; barbarians fight).
 */
public class BarbarianEntity extends CitizenEntity implements CombatantCitizen {
    private BlockPos campCenter = null;
    private UUID campId = null;
    private double damage = -1.0;        // capability melee/thrown half-hearts
    private double attackSpeed = -1.0;   // capability attacks/sec
    private Item rangedItem = null;      // bow held at distance (kiters)
    private Item meleeItem = null;       // weapon swapped to in melee
    private boolean kite = false;        // bowman: hold distance, melee only when cornered
    private double combatSpeed = 1.0;    // per-member chase-speed variance (set at spawn; <1 = slower)
    private boolean messenger = false;        // a diplomat travelling to a settlement (no combat/wander AI)
    private UUID messengerSettlementId = null; // the SPECIFIC settlement this messenger was sent to

    public BarbarianEntity(EntityType<? extends CitizenEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return CitizenEntity.createAttributes(); // same body as a citizen
    }

    @Override
    protected void registerGoals() {
        // Bespoke barbarian AI — deliberately NOT super.registerGoals() (skips citizen work/patrol/
        // conversation and especially PanicGoal, so a struck barbarian fights instead of fleeing).
        // Data-dependent goals (camp wander, ranged) are added later in markBarbarianMember.
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(0, new CitizenCombatGoal(this, 0.7)); // base; ×combatSpeed variance
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        // Retaliate (safe to alert others now — only fellow BarbarianEntity, not the player's citizens).
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
        // Attack the player / their citizens when this camp is HOSTILE toward them.
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, LivingEntity.class,
            10, true, false, (Predicate<LivingEntity>) this::isCampEnemy));
    }

    /** Configures a freshly-spawned barbarian: weapons, the camp it belongs to, and the data-dependent
     *  goals (camp wander/leash + ranged). Call once after {@code addFreshEntity}. */
    public void markBarbarianMember(BlockPos campCenter, UUID campId, double damage, double attackSpeed,
                                    Item weapon, boolean ranged, @Nullable ResourceLocation projectileId,
                                    Item meleeWeapon, boolean kite) {
        this.campCenter = campCenter;
        this.campId = campId;
        this.damage = damage;
        this.attackSpeed = attackSpeed;
        this.rangedItem = (ranged && weapon != null && weapon != Items.AIR) ? weapon : null;
        this.meleeItem = (meleeWeapon != null && meleeWeapon != Items.AIR) ? meleeWeapon : weapon;
        this.kite = kite && ranged;
        // Per-member chase-speed variance so a charging mob isn't a uniform sprint. Centred well below
        // the citizen base — barbarians were closing far too fast (see CitizenCombatGoal use).
        this.combatSpeed = 0.85 + getRandom().nextDouble() * 0.30; // 0.85–1.15 × the goal's base 0.7
        Item held = this.kite && rangedItem != null ? rangedItem : meleeItem;
        if (held != null && held != Items.AIR) {
            this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(held));
            this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        }
        this.goalSelector.addGoal(5, new CampWanderGoal(this, campCenter, 10, 0.8));
        if (ranged && projectileId != null) {
            this.goalSelector.addGoal(3, new BarbarianRangedGoal(this, projectileId, damage, this.kite));
        }
    }

    public BlockPos campCenter() { return campCenter; }
    public UUID campId() { return campId; }
    public double combatDamage() { return damage; }
    public double combatAttackSpeed() { return attackSpeed; }
    public Item meleeItem() { return meleeItem; }
    public Item rangedItem() { return rangedItem; }

    /** A kiting bowman prefers range — its combat goal only melees when cornered. */
    public boolean prefersRanged() { return kite; }

    /** Per-member multiplier on combat chase speed (variance set at spawn). */
    public double combatSpeed() { return combatSpeed; }

    /** True if this member is one of its camp's commanders (gets a crown + counts toward defeat). */
    public boolean isCommander() {
        if (campId == null || !(level() instanceof ServerLevel sl)) return false;
        BarbarianCamp camp = BarbarianData.get(sl).getById(campId);
        return camp != null && camp.commanderIds.contains(getUUID());
    }

    public boolean isMessenger() { return messenger; }
    public UUID messengerSettlementId() { return messengerSettlementId; }

    /** Turns this barbarian into a travelling diplomat sent to a SPECIFIC settlement. Strips combat/wander
     *  AI (the MessengerManager drives its navigation) and tags it so right-click opens the parley. */
    public void markMessenger(BlockPos campCenter, UUID campId, UUID settlementId) {
        this.campCenter = campCenter;
        this.campId = campId;
        this.messenger = true;
        this.messengerSettlementId = settlementId;
        clearAllGoals();
        setCustomNameVisible(true);
    }

    /** Removes every goal — used for the manager-driven messenger (no vanilla AI fighting our nav). */
    public void clearAllGoals() {
        this.goalSelector.removeAllGoals(g -> true);
        this.targetSelector.removeAllGoals(g -> true);
    }

    /** Right-click: a messenger opens the barter screen; any other barbarian has no info panel. */
    @Override
    public net.minecraft.world.InteractionResult mobInteract(net.minecraft.world.entity.player.Player player,
                                                             net.minecraft.world.InteractionHand hand) {
        if (messenger && !level().isClientSide
                && player instanceof net.minecraft.server.level.ServerPlayer sp) {
            com.bannerbound.core.barbarian.MessengerManager.openBarter(sp, this);
            return net.minecraft.world.InteractionResult.CONSUME;
        }
        return net.minecraft.world.InteractionResult.PASS;
    }

    /** True if {@code e} is a valid target: the player (or a player citizen) of a settlement this
     *  barbarian's camp is HOSTILE toward. */
    private boolean isCampEnemy(LivingEntity e) {
        if (e == this || e == null || !e.isAlive()) return false;
        if (campId == null || !(level() instanceof ServerLevel sl)) return false;
        BarbarianCamp camp = BarbarianData.get(sl).getById(campId);
        if (camp == null) return false;
        if (e instanceof Player p) {
            return campHostileTo(camp, SettlementData.get(sl).getByPlayer(p.getUUID()));
        }
        if (e instanceof BarbarianEntity) return false; // never each other
        if (e instanceof CitizenEntity c) {
            return campHostileTo(camp, c.getSettlement());
        }
        return false;
    }

    /** Whether {@code camp} is hostile toward settlement {@code s} (null = the type's default stance). */
    public static boolean campHostileTo(BarbarianCamp camp, Settlement s) {
        if (camp.type.isAlwaysHostile()) return true;
        if (s == null) return camp.type.defaultRelation() == CampRelationState.HOSTILE;
        return camp.relationToward(s.id()) == CampRelationState.HOSTILE;
    }
}
