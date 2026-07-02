package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.SpearFishing;
import com.bannerbound.antiquity.entity.SpearProjectile;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.Level;

/**
 * A primitive spear — a melee weapon that reaches ~2 blocks farther than a sword and can be
 * <b>thrown</b>. Hold right-click to wind up (bow-style), release to launch a {@link SpearProjectile}
 * along your look. The projectile is the <i>same</i> spear (its full stack/NBT is carried), so it
 * sticks in mobs / blocks and is recovered unchanged on pickup; the thrown hit deals this spear's
 * melee {@link #damage}. Throwing consumes the held spear (free in creative). Melee attacks cost 1
 * durability via {@link #hurtEnemy}; throwing costs none (it's recovered as the same spear).
 *
 * <p>Tiers are configured at registration (see {@code BannerboundAntiquity}): wood/bone 4 dmg,
 * stone 5.5; all 1.2 attack speed; durabilities 59 / 48 / 131.
 */
public class SpearItem extends Item {
    /** Attribute-modifier id for the spear's extra attack reach (no other reach use in the mod). */
    private static final ResourceLocation REACH_MODIFIER_ID =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "spear_reach");
    /** Extra entity-interaction (attack) range over the hand default — attack only, not blocks. */
    private static final double REACH_BONUS = 2.0;

    /** Launch velocity of the thrown spear and its spread. Lower than an arrow's full-draw 3.0
     *  (and well under a trident's 2.5) so a heavy spear arcs down sooner and falls short of a bow. */
    private static final float THROW_POWER = 1.5F;
    private static final float THROW_INACCURACY = 1.0F;
    /** Minimum wind-up before a release will throw — stops a stray tap from flinging the spear. */
    private static final int MIN_CHARGE_TICKS = 5;

    /** Melee damage; reused as the thrown projectile's base damage. */
    private final double damage;

    public SpearItem(Properties properties, int durability, double attackDamage, double attackSpeed) {
        super(properties.durability(durability).attributes(spearAttributes(attackDamage, attackSpeed)));
        this.damage = attackDamage;
    }

    /** Sword-style attack attributes plus +{@value #REACH_BONUS} entity-interaction range.
     *  {@code damage}/{@code speed} are the TOTAL displayed values; the player's base (1 dmg,
     *  4 speed) is subtracted to get the modifier. */
    private static ItemAttributeModifiers spearAttributes(double damage, double speed) {
        return ItemAttributeModifiers.builder()
            .add(Attributes.ATTACK_DAMAGE,
                new AttributeModifier(BASE_ATTACK_DAMAGE_ID, damage - 1.0, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND)
            .add(Attributes.ATTACK_SPEED,
                new AttributeModifier(BASE_ATTACK_SPEED_ID, speed - 4.0, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND)
            .add(Attributes.ENTITY_INTERACTION_RANGE,
                new AttributeModifier(REACH_MODIFIER_ID, REACH_BONUS, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND)
            .build();
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        stack.hurtAndBreak(1, attacker, EquipmentSlot.MAINHAND);
        return true;
    }

    // ── Charge & throw (bow-style: hold to wind up, release to launch) ───────────────────────

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        // SPEAR = the raise-over-shoulder throw wind-up (the look we want).
        return UseAnim.SPEAR;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000; // effectively "hold as long as you like" (bow/trident convention)
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        if (!level.isClientSide) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                BannerboundAntiquity.SPEAR_HOLD_SOUND.get(), SoundSource.PLAYERS, 0.8F, throwPitch(level));
        }
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    /** Random throw/hold pitch in the 0.8–1.2 range. */
    private static float throwPitch(Level level) {
        return 0.8F + level.getRandom().nextFloat() * 0.4F;
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (!(entity instanceof Player player)) {
            return;
        }
        int charge = this.getUseDuration(stack, entity) - timeLeft;
        if (charge < MIN_CHARGE_TICKS) {
            return;
        }
        if (!level.isClientSide) {
            boolean creative = player.hasInfiniteMaterials();
            // The thrown spear loses 1 durability (it's the projectile + the recovered item). If
            // that wears it out, it shatters on the throw — still launched, but lands as nothing.
            ItemStack thrown = stack.copy();
            thrown.setCount(1);
            if (!creative && thrown.isDamageableItem()) {
                thrown.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            }
            boolean tethered = false;
            if (!thrown.isEmpty()) {
                SpearProjectile spear = new SpearProjectile(level, player, thrown, this.damage);
                spear.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F,
                    THROW_POWER, THROW_INACCURACY);
                if (creative) {
                    spear.setCreativeOnlyPickup();
                }
                // Rope-tethered throw: plant rope in the OTHER hand + spear fishing researched. The
                // thrown rope BECOMES the rendered rope (1 consumed); the spear is then reelable.
                InteractionHand other = player.getUsedItemHand() == InteractionHand.MAIN_HAND
                    ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                ItemStack ropeStack = player.getItemInHand(other);
                if (ropeStack.is(BannerboundAntiquity.FIBER_ROPE.get()) && SpearFishing.unlocked(player)) {
                    spear.setRopeTethered(true);
                    tethered = true;
                    if (!creative) {
                        ropeStack.shrink(1);
                    }
                }
                level.addFreshEntity(spear);
            }
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                (tethered ? BannerboundAntiquity.SPEAR_THROW_ROPE_SOUND : BannerboundAntiquity.SPEAR_THROW_SOUND).get(),
                SoundSource.PLAYERS, 0.9F, throwPitch(level));
            if (!creative) {
                stack.shrink(1); // the spear left the hand — as a projectile, or shattered
            }
        }
        player.awardStat(Stats.ITEM_USED.get(this));
    }
}
