package com.bannerbound.core.entity;

import java.util.UUID;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.barbarian.CampWanderGoal;
import com.bannerbound.core.citystate.CityState;
import com.bannerbound.core.citystate.CityStateData;

import net.minecraft.core.BlockPos;
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
 * A city-state mercenary — hired muscle a city-state fields ONLY while at war (see CITY_STATES plan
 * §2). Its own entity type, subclassing {@link CitizenEntity} (reuses the citizen model/render), so
 * {@code HurtByTargetGoal.setAlertOthers()} rallies only fellow mercenaries, and citizens recognise
 * them by class.
 *
 * <p><b>Defend-only:</b> like a barbarian camp member it wanders the city-state centre and fights
 * enemies who approach — but city-states never <i>send</i> raids (mercenaries don't march on the
 * player), so the wander-anchor behaviour IS the defence. The "enemy" is any player / player-citizen
 * of a settlement the city-state is actively at war with. Never persisted ({@code markSimulated});
 * respawned slowly by {@code CityStateWarManager}.
 */
public class MercenaryEntity extends CitizenEntity implements CombatantCitizen {
    private BlockPos homeCenter = null;
    private UUID cityStateId = null;
    private double damage = -1.0;
    private double attackSpeed = -1.0;
    private Item meleeItem = null;
    private double combatSpeed = 1.0;

    public MercenaryEntity(EntityType<? extends CitizenEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return CitizenEntity.createAttributes();
    }

    @Override
    protected void registerGoals() {
        // Bespoke combat AI (NOT super.registerGoals() — no citizen work/panic; a struck mercenary fights).
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(0, new CitizenCombatGoal(this, 0.7));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, LivingEntity.class,
            10, true, false, (Predicate<LivingEntity>) this::isCityStateEnemy));
    }

    /** Configures a freshly-spawned mercenary: its melee weapon, the city-state it defends, and the
     *  wander-anchor goal. Melee-only in Phase 2 (ranged is a later enhancement). Call once after
     *  {@code addFreshEntity}. */
    public void markMercenary(BlockPos homeCenter, UUID cityStateId, double damage, double attackSpeed,
                              Item meleeWeapon) {
        this.homeCenter = homeCenter;
        this.cityStateId = cityStateId;
        this.damage = damage;
        this.attackSpeed = attackSpeed;
        this.meleeItem = (meleeWeapon != null && meleeWeapon != Items.AIR) ? meleeWeapon : null;
        this.combatSpeed = 0.85 + getRandom().nextDouble() * 0.30;
        if (meleeItem != null) {
            this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(meleeItem));
            this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        }
        // Wander the city-state centre (defenders hold ground; they never raid out).
        this.goalSelector.addGoal(5, new CampWanderGoal(this, homeCenter, 12, 0.8));
    }

    public BlockPos homeCenter() { return homeCenter; }
    public UUID cityStateId() { return cityStateId; }
    public double combatDamage() { return damage; }
    public double combatAttackSpeed() { return attackSpeed; }
    public Item meleeItem() { return meleeItem; }
    public Item rangedItem() { return null; }
    public boolean prefersRanged() { return false; }
    public double combatSpeed() { return combatSpeed; }

    /** True if {@code e} is a player (or player-citizen) of a settlement this city-state is actively
     *  at war with. */
    private boolean isCityStateEnemy(LivingEntity e) {
        if (e == this || e == null || !e.isAlive()) return false;
        if (cityStateId == null || !(level() instanceof ServerLevel sl)) return false;
        CityState cs = CityStateData.get(sl).getById(cityStateId);
        if (cs == null) return false;
        if (e instanceof Player p) {
            Settlement s = SettlementData.get(sl).getByPlayer(p.getUUID());
            return s != null && cs.isActiveEnemy(s.id());
        }
        if (e instanceof MercenaryEntity || e instanceof BarbarianEntity) return false;
        if (e instanceof CitizenEntity c) {
            Settlement s = c.getSettlement();
            return s != null && cs.isActiveEnemy(s.id());
        }
        return false;
    }

    /** No info panel on right-click (mercenaries have no happiness/parley GUI). */
    @Override
    public net.minecraft.world.InteractionResult mobInteract(net.minecraft.world.entity.player.Player player,
                                                             net.minecraft.world.InteractionHand hand) {
        return net.minecraft.world.InteractionResult.PASS;
    }
}
