package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class UnknownItemHelper {
    public static final ModelResourceLocation QUESTION_MARK_MODEL =
        ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath("bannerbound", "item/question_mark"));

    public static final ResourceLocation SCIENCE_ICON =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/science_icon.png");

    private static BakedModel cachedQuestionMark;

    /** When true, {@link ItemRendererMixin} skips the question-mark swap. Set by screens that
     *  want to display the real item icon even for unknown items (e.g. the research tooltip's
     *  "Unlocked Items:" preview, where seeing the actual icon is the point). Render thread is
     *  single-threaded; a plain boolean is enough. */
    private static boolean bypassUnknownSwap = false;

    public static void setBypassUnknownSwap(boolean bypass) {
        bypassUnknownSwap = bypass;
    }

    public static boolean isBypassActive() {
        return bypassUnknownSwap;
    }

    private UnknownItemHelper() {
    }

    public static MutableComponent unknownName() {
        return Component.translatable("bannerbound.unknown_item.name").withStyle(ChatFormatting.RED);
    }

    public static MutableComponent unknownAction() {
        return Component.translatable("bannerbound.unknown_item.action").withStyle(ChatFormatting.RED);
    }

    public static boolean isKnown(Item item) {
        // Creative players understand everything — item gating is a survival-only constraint.
        // Funnel for all client surfaces (name swap, question-mark model, tooltips, screen filters),
        // so this single check covers every one of them.
        if (localPlayerIsCreative()) {
            return true;
        }
        return ClientStartingItems.contains(item) || ClientResearchState.isItemUnlocked(item);
    }

    /**
     * Like {@link #isKnown(Item)} but reflects the SETTLEMENT's knowledge ONLY — no creative
     * bypass. The lexicon documents the words a settlement has coined for things it actually
     * knows; a creative player flying around shouldn't flood it with every registered item
     * (shulker boxes and the like). Returns false until the starting-items set has synced so the
     * lexicon never lists the whole registry during the brief pre-sync window.
     */
    public static boolean isKnownToSettlement(Item item) {
        if (!ClientStartingItems.isLoaded()) {
            return false;
        }
        return ClientStartingItems.contains(item) || ClientResearchState.isItemUnlocked(item);
    }

    private static boolean localPlayerIsCreative() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.player != null && mc.player.isCreative();
    }

    /**
     * Client mirror of {@link com.bannerbound.core.api.research.ItemKnowledge.StackGate}: an extra,
     * component-aware test an expansion registers so the question-mark/name swap reflects an item's
     * DATA (e.g. a modular arrow whose material the civ hasn't researched), not just its id.
     */
    @FunctionalInterface
    public interface ClientStackGate {
        boolean isKnown(ItemStack stack);
    }

    private static final java.util.List<ClientStackGate> STACK_GATES =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void registerStackGate(ClientStackGate gate) {
        STACK_GATES.add(gate);
    }

    public static boolean isUnknownForLocalPlayer(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (localPlayerIsCreative()) {
            return false;
        }
        if (!isKnown(stack.getItem())) {
            return true;
        }
        for (ClientStackGate gate : STACK_GATES) {
            if (!gate.isKnown(stack)) {
                return true;
            }
        }
        return false;
    }

    public static BakedModel getQuestionMarkModel() {
        if (cachedQuestionMark == null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getModelManager() == null) {
                return null;
            }
            cachedQuestionMark = mc.getModelManager().getModel(QUESTION_MARK_MODEL);
        }
        return cachedQuestionMark;
    }

    public static void invalidateCache() {
        cachedQuestionMark = null;
    }
}
