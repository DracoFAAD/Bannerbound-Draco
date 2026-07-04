package com.bannerbound.antiquity;

import java.util.*;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.network.KnappingActionPayload;
import com.bannerbound.antiquity.network.OpenKnappingPayload;
import com.bannerbound.antiquity.recipe.KnappingShape;
import com.bannerbound.antiquity.recipe.KnappingShapeManager;
import com.bannerbound.core.api.quality.QualityMath;
import com.bannerbound.core.api.quality.QualityTier;
import com.bannerbound.core.event.UnknownItemBlocker;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-authoritative driver for hand-knapping (the two-rocks gesture → {@code KnappingScreen}). No
 * station and no pos — a pure hand-craft — so the session is keyed on the player alone: it tracks
 * only whether the rock has been committed (consumed). One rock is consumed at the first chip
 * (COMMIT); a successful knap yields a head stamped with the rolled {@link QualityTier}, while
 * chipping the whole stone away ("broke the stone") just forfeits the already-consumed rock.
 *
 * <p>The knapped head is the skill output; its quality later rides onto the finished tool when the
 * head is hafted at the Crafting Stone (see {@code CraftingStoneBlockEntity#craft} +
 * {@code transfer_quality}). See {@code KNAPPING_PLAN.md}.
 */
@ApiStatus.Internal
public final class Knapping {
    private Knapping() {
    }

    /** Items that count as a knappable rock (held in BOTH hands to start a session). */
    public static final TagKey<Item> KNAPPING_ROCKS = TagKey.create(Registries.ITEM,
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "knapping_rocks"));

    private static final class Session {
        final long startTime;
        boolean committed;

        Session(long startTime) {
            this.startTime = startTime;
        }
    }

    /** Active sessions keyed by player UUID (server thread only). */
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    /** True if {@code player} is holding a knappable rock in each hand. */
    public static boolean holdingTwoRocks(ServerPlayer player) {
        return player.getMainHandItem().is(KNAPPING_ROCKS) && player.getOffhandItem().is(KNAPPING_ROCKS);
    }

    /** Validates research + two rocks, then opens the knapping screen carrying every loaded shape. */
    public static void tryOpen(ServerPlayer player) {
        if (!holdingTwoRocks(player)) return;
        // Research gate: knapping is unlocked by the Knapping research (which unlocks stone tools).
        // Gate on the PLAYER'S OWN settlement knowing stone tools — not their position — so this
        // portable hand-craft works wherever they stand, exactly like the vanilla crafting grid
        // (CraftingMenuMixin). Creative players are exempt inside isUnknownForPlayer.
        if (UnknownItemBlocker.isUnknownForPlayer(player, Items.STONE_PICKAXE)) {
            player.displayClientMessage(Component.translatable("bannerbound.knapping.no_research")
                .withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        List<KnappingShape> shapes = KnappingShapeManager.all();
        if (shapes.isEmpty()) return;
        List<OpenKnappingPayload.ShapeView> views = new ArrayList<>();
        for (KnappingShape s : shapes) {
            if (UnknownItemBlocker.isUnknownForPlayer(player, s.head())) continue;
            views.add(new OpenKnappingPayload.ShapeView(
                BuiltInRegistries.ITEM.getKey(s.head()), s.keepMask(), s.percentage_standard(), s.percentage_fine()));
        }
        if (views.isEmpty()) return;
        SESSIONS.put(player.getUUID(), new Session(player.serverLevel().getGameTime()));
        PacketDistributor.sendToPlayer(player, new OpenKnappingPayload(views));
    }

    /** Handles a client knapping step (COMMIT / COMPLETE / BROKE / CANCEL). */
    public static void handleAction(ServerPlayer player, KnappingActionPayload payload) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) return;
        switch (payload.action()) {
            case KnappingActionPayload.COMMIT -> {
                if (!session.committed) {
                    if (consumeOneRock(player)) {
                        session.committed = true;
                    } else {
                        SESSIONS.remove(player.getUUID()); // rocks vanished from hand → abort
                    }
                }
            }
            case KnappingActionPayload.COMPLETE -> {
                if (session.committed) complete(player, session, payload);
                SESSIONS.remove(player.getUUID());
            }
            case KnappingActionPayload.BROKE, KnappingActionPayload.CANCEL ->
                SESSIONS.remove(player.getUUID());
            default -> { }
        }
    }

    private static boolean consumeOneRock(ServerPlayer player) {
        if (player.hasInfiniteMaterials()) return true; // creative: don't eat the rock
        ItemStack off = player.getOffhandItem();
        if (off.is(KNAPPING_ROCKS)) {
            off.shrink(1);
            return true;
        }
        ItemStack main = player.getMainHandItem();
        if (main.is(KNAPPING_ROCKS)) {
            main.shrink(1);
            return true;
        }
        return false;
    }

    /** The tier a set of per-tap scores earns. Caps at FINE (hand-craft), and additionally REQUIRES
     *  at least one perfect (100) tap to reach FINE — a clean run of merely-good taps tops out at
     *  Standard. Shared by the server roll and the client's live estimate so they never disagree. */
    public static QualityTier rollTier(int percentage_standard, int percentage_fine, List<Integer> scores, int reps) {
        int total_score = scores.stream().mapToInt(Integer::intValue).sum();
        double percentage = (total_score / (double) (reps * 100)) * 100;

        QualityTier tier;

        if (percentage >= percentage_fine) {
            tier = QualityTier.FINE;
        } else if (percentage >= percentage_standard) {
            tier = QualityTier.STANDARD;
        } else {
            tier = QualityTier.CRUDE;
        }
        return tier;
    }

    private static void complete(ServerPlayer player, Session session, KnappingActionPayload payload) {
        Item headItem = BuiltInRegistries.ITEM.get(payload.head());
        KnappingShape shape = KnappingShapeManager.byHead(headItem);
        if (shape == null) return; // unknown/forged head id — ignore
        if (UnknownItemBlocker.isUnknownForPlayer(player, headItem)) return; // shape not unlocked

        QualityTier tier = rollTier(shape.percentage_standard(), shape.percentage_fine(), payload.scores(), 9 - shape.keep().size());

        ItemStack head = Fletching.applyQuality(new ItemStack(headItem), tier);
        giveOrDrop(player, head);
        player.serverLevel().playSound(null, player.blockPosition(),
            BannerboundAntiquity.KNAPPING_SOUND.get(), SoundSource.PLAYERS, 0.9F, 1.0F);
    }

    private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }
}
