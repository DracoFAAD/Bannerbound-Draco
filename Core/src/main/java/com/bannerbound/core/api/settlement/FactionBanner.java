package com.bannerbound.core.api.settlement;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.network.CloseSettlementScreensPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The FACTION BANNER — the block a settlement is bound to (it's the mod's name). One main
 * banner per settlement, auto-raised beside the campfire at founding in the faction's color.
 * While it is down (taken down by a member, or struck down by an enemy/explosion) the town
 * hall menu refuses to open, every open settlement menu is force-closed, and ALL citizen
 * labor halts — no banner, no command. Members may relocate it freely (break → re-place);
 * war-time relocation locks and the bedwars-style capture flow come with the war system.
 *
 * <p>The banner is a plain vanilla banner block (color from {@link SettlementColor}), so it
 * needs no custom block/BE — and the Heraldry editor can later layer vanilla banner patterns
 * onto the same block. See FACTION_BANNER_PLAN.md.
 */
public final class FactionBanner {

    private FactionBanner() {}

    /** Settlement color → the vanilla dye whose banner best matches the faction's chat color. */
    public static DyeColor dyeFor(SettlementColor color) {
        return switch (color) {
            case WHITE -> DyeColor.WHITE;
            case RED -> DyeColor.RED;
            case GOLD -> DyeColor.ORANGE;
            case YELLOW -> DyeColor.YELLOW;
            case GREEN -> DyeColor.LIME;
            case AQUA -> DyeColor.LIGHT_BLUE;
            case BLUE -> DyeColor.BLUE;
            case LIGHT_PURPLE -> DyeColor.MAGENTA;
        };
    }

    /** The faction's standing banner block (vanilla, per-color). */
    public static BannerBlock standingBlockFor(SettlementColor color) {
        return (BannerBlock) BannerBlock.byColor(dyeFor(color));
    }

    /** The faction's banner item (vanilla, per-color). */
    public static Item itemFor(SettlementColor color) {
        return standingBlockFor(color).asItem();
    }

    /** The faction's WALL banner block — vanilla has no {@code byColor} for wall banners. */
    public static net.minecraft.world.level.block.Block wallBlockFor(SettlementColor color) {
        return switch (dyeFor(color)) {
            case WHITE -> net.minecraft.world.level.block.Blocks.WHITE_WALL_BANNER;
            case RED -> net.minecraft.world.level.block.Blocks.RED_WALL_BANNER;
            case ORANGE -> net.minecraft.world.level.block.Blocks.ORANGE_WALL_BANNER;
            case YELLOW -> net.minecraft.world.level.block.Blocks.YELLOW_WALL_BANNER;
            case LIME -> net.minecraft.world.level.block.Blocks.LIME_WALL_BANNER;
            case LIGHT_BLUE -> net.minecraft.world.level.block.Blocks.LIGHT_BLUE_WALL_BANNER;
            case BLUE -> net.minecraft.world.level.block.Blocks.BLUE_WALL_BANNER;
            case MAGENTA -> net.minecraft.world.level.block.Blocks.MAGENTA_WALL_BANNER;
            // Unreachable today — dyeFor only returns the eight above — but the switch must
            // be exhaustive, and a future color addition should fail soft, not crash.
            default -> net.minecraft.world.level.block.Blocks.WHITE_WALL_BANNER;
        };
    }

    /**
     * THE faction banner always shows the faction's design: whatever banner was planted —
     * command-given white, a looted foreign color — converts to the faction-color block on
     * registration, keeping its rotation (standing) or facing (wall). Property checks, not
     * instanceof, so it survives block-class swaps. Heraldry patterns layer on here later.
     */
    private static void convertToFactionDesign(ServerLevel level, Settlement settlement, BlockPos pos) {
        BlockState current = level.getBlockState(pos);
        if (!isBanner(current)) return;
        BlockState converted = null;
        if (current.hasProperty(BannerBlock.ROTATION)) {
            converted = standingBlockFor(settlement.color()).defaultBlockState()
                .setValue(BannerBlock.ROTATION, current.getValue(BannerBlock.ROTATION));
        } else if (current.hasProperty(net.minecraft.world.level.block.WallBannerBlock.FACING)) {
            converted = wallBlockFor(settlement.color()).defaultBlockState()
                .setValue(net.minecraft.world.level.block.WallBannerBlock.FACING,
                    current.getValue(net.minecraft.world.level.block.WallBannerBlock.FACING));
        }
        if (converted != null && !current.is(converted.getBlock())) {
            level.setBlock(pos, converted, 3);
        }
    }

    /**
     * Founding generation: raises the faction banner on clear ground beside the campfire,
     * front face turned toward it. Tries the four cardinal spots two blocks out first (one
     * block out, the banner's hitbox shadows campfire clicks), then the diagonals, then flush
     * cardinals, each with ±1 block of Y flex for uneven ground. Returns the banner position,
     * or null if no spot took — the founder then places one by hand (starting items include
     * banners precisely so this can't soft-lock).
     */
    @Nullable
    public static BlockPos placeFoundingBanner(ServerLevel level, Settlement settlement, BlockPos campfire) {
        BlockState banner = standingBlockFor(settlement.color()).defaultBlockState();
        int[][] offsets = {
            {2, 0}, {-2, 0}, {0, 2}, {0, -2},
            {2, 2}, {2, -2}, {-2, 2}, {-2, -2},
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        };
        for (int[] off : offsets) {
            for (int dy : new int[] {0, 1, -1}) {
                BlockPos pos = campfire.offset(off[0], dy, off[1]);
                if (!level.getBlockState(pos).canBeReplaced()) continue;
                if (!banner.canSurvive(level, pos)) continue;
                int rot = RotationSegment.convertToSegment(yawToward(pos, campfire));
                level.setBlock(pos, banner.setValue(BannerBlock.ROTATION, rot), 3);
                settlement.setBannerPos(pos.immutable());
                return pos;
            }
        }
        return null;
    }

    // ─── Heraldry design (phase 2) ─────────────────────────────────────────────────────────

    /** Resolves the settlement's stored Heraldry design into vanilla banner pattern layers.
     *  Unresolvable pattern ids (datapack removed) are skipped, not fatal — the rest of the
     *  design survives. */
    public static net.minecraft.world.level.block.entity.BannerPatternLayers patternsFor(
            Settlement settlement, net.minecraft.core.RegistryAccess registries) {
        if (settlement.bannerDesign().isEmpty()) {
            return net.minecraft.world.level.block.entity.BannerPatternLayers.EMPTY;
        }
        net.minecraft.core.Registry<net.minecraft.world.level.block.entity.BannerPattern> reg =
            registries.registryOrThrow(net.minecraft.core.registries.Registries.BANNER_PATTERN);
        java.util.List<net.minecraft.world.level.block.entity.BannerPatternLayers.Layer> layers =
            new java.util.ArrayList<>();
        for (Settlement.BannerLayer layer : settlement.bannerDesign()) {
            net.minecraft.resources.ResourceLocation rl =
                net.minecraft.resources.ResourceLocation.tryParse(layer.patternId());
            if (rl == null) continue;
            var holder = reg.getHolder(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.BANNER_PATTERN, rl));
            if (holder.isEmpty()) continue;
            layers.add(new net.minecraft.world.level.block.entity.BannerPatternLayers.Layer(
                holder.get(), DyeColor.byId(layer.colorId())));
        }
        return layers.isEmpty()
            ? net.minecraft.world.level.block.entity.BannerPatternLayers.EMPTY
            : new net.minecraft.world.level.block.entity.BannerPatternLayers(java.util.List.copyOf(layers));
    }

    /** The full faction banner as an item: faction base color + Heraldry pattern layers. */
    public static net.minecraft.world.item.ItemStack designedItem(
            Settlement settlement, net.minecraft.core.RegistryAccess registries, int count) {
        net.minecraft.world.item.ItemStack stack =
            new net.minecraft.world.item.ItemStack(itemFor(settlement.color()), count);
        var patterns = patternsFor(settlement, registries);
        if (!patterns.layers().isEmpty()) {
            stack.set(net.minecraft.core.component.DataComponents.BANNER_PATTERNS, patterns);
        }
        return stack;
    }

    /** Pushes the current Heraldry design onto the standing main banner's block entity —
     *  called on raise and whenever the design is edited, so the flag in the plaza always
     *  shows the live design. No-op while the banner chunk is unloaded (the design is data;
     *  the next raise/edit in a loaded chunk catches it up). */
    public static void applyDesignToBlock(ServerLevel level, Settlement settlement) {
        BlockPos pos = settlement.bannerPos();
        if (pos == null || !level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (!isBanner(state)) return;
        if (level.getBlockEntity(pos)
                instanceof net.minecraft.world.level.block.entity.BannerBlockEntity be) {
            be.fromItem(designedItem(settlement, level.registryAccess(), 1),
                dyeFor(settlement.color()));
            be.setChanged();
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    // ─── Banner-driven identity colors ─────────────────────────────────────────────────────

    /** Approximate fraction of the cloth each vanilla pattern paints. Keys are pattern PATHS
     *  (namespace stripped). Hand-estimated from the textures — the model only needs to rank
     *  colors, not be pixel-exact. Unknown/modded patterns default to 0.25. */
    private static final java.util.Map<String, Float> PATTERN_COVERAGE = java.util.Map.ofEntries(
        java.util.Map.entry("base", 1.0f),
        java.util.Map.entry("half_vertical", 0.5f),
        java.util.Map.entry("half_vertical_right", 0.5f),
        java.util.Map.entry("half_horizontal", 0.5f),
        java.util.Map.entry("half_horizontal_bottom", 0.5f),
        java.util.Map.entry("diagonal_left", 0.5f),
        java.util.Map.entry("diagonal_right", 0.5f),
        java.util.Map.entry("diagonal_up_left", 0.5f),
        java.util.Map.entry("diagonal_up_right", 0.5f),
        java.util.Map.entry("gradient", 0.4f),
        java.util.Map.entry("gradient_up", 0.4f),
        java.util.Map.entry("bricks", 0.35f),
        java.util.Map.entry("small_stripes", 0.4f),
        java.util.Map.entry("cross", 0.35f),
        java.util.Map.entry("straight_cross", 0.3f),
        java.util.Map.entry("curly_border", 0.3f),
        java.util.Map.entry("border", 0.25f),
        java.util.Map.entry("triangles_bottom", 0.25f),
        java.util.Map.entry("triangles_top", 0.25f),
        java.util.Map.entry("triangle_bottom", 0.2f),
        java.util.Map.entry("triangle_top", 0.2f),
        java.util.Map.entry("rhombus", 0.2f),
        java.util.Map.entry("stripe_center", 0.15f),
        java.util.Map.entry("stripe_middle", 0.15f),
        java.util.Map.entry("circle", 0.15f),
        java.util.Map.entry("stripe_bottom", 0.12f),
        java.util.Map.entry("stripe_top", 0.12f),
        java.util.Map.entry("stripe_left", 0.12f),
        java.util.Map.entry("stripe_right", 0.12f),
        java.util.Map.entry("stripe_downleft", 0.18f),
        java.util.Map.entry("stripe_downright", 0.18f),
        java.util.Map.entry("square_bottom_left", 0.1f),
        java.util.Map.entry("square_bottom_right", 0.1f),
        java.util.Map.entry("square_top_left", 0.1f),
        java.util.Map.entry("square_top_right", 0.1f));

    private static float coverageOf(String patternId) {
        int colon = patternId.indexOf(':');
        String path = colon >= 0 ? patternId.substring(colon + 1) : patternId;
        Float known = PATTERN_COVERAGE.get(path);
        return known != null ? known : 0.25f;
    }

    /** A color must hold at least this share of the cloth to count toward the identity. */
    private static final float IDENTITY_SHARE_FLOOR = 0.05f;

    /**
     * The identity colors a banner design EARNS: paint the design layer by layer (each layer's
     * coverage claims its share and proportionally hides what's underneath), then rank every
     * dye holding ≥5% of the cloth, most-present first. The list is AS LONG AS THE DESIGN IS
     * COLORFUL — a one-color banner yields one identity color, a two-color banner two, a wild
     * five-color banner five. {@code [0]} = the settlement's primary color; the rest are its
     * accents in order, used for gradients and trim across the settlement GUI.
     * This is the "don't fight it" rule: dye your banner mostly magenta and you ARE a magenta
     * settlement now. Pure data math — safe on both sides, shared by server (save) and the
     * editor's live identity preview.
     */
    public static java.util.List<DyeColor> identityDyes(DyeColor base,
            java.util.List<Settlement.BannerLayer> layers) {
        float[] share = new float[16];
        share[base.getId()] = 1f;
        for (Settlement.BannerLayer layer : layers) {
            float coverage = coverageOf(layer.patternId());
            for (int i = 0; i < share.length; i++) share[i] *= (1f - coverage);
            share[layer.colorId() & 15] += coverage;
        }
        java.util.List<Integer> ranked = new java.util.ArrayList<>();
        for (int i = 0; i < share.length; i++) {
            if (share[i] >= IDENTITY_SHARE_FLOOR) ranked.add(i);
        }
        final float[] shares = share;
        ranked.sort((a, b) -> Float.compare(shares[b], shares[a]));
        java.util.List<DyeColor> out = new java.util.ArrayList<>(ranked.size());
        for (int id : ranked) out.add(DyeColor.byId(id));
        if (out.isEmpty()) out.add(base); // floor ate everything (can't really happen) — base
        return out;
    }

    /** Nearest chat color for an identity dye — chat/team colors are limited to the 16
     *  {@code ChatFormatting} entries, so a couple of dyes share (pink→light_purple,
     *  brown→gold) and the unreadable ones go grey (black→dark_gray). */
    public static ChatFormatting formattingFor(DyeColor dye) {
        return switch (dye) {
            case WHITE -> ChatFormatting.WHITE;
            case ORANGE -> ChatFormatting.GOLD;
            case MAGENTA -> ChatFormatting.LIGHT_PURPLE;
            case LIGHT_BLUE -> ChatFormatting.AQUA;
            case YELLOW -> ChatFormatting.YELLOW;
            case LIME -> ChatFormatting.GREEN;
            case PINK -> ChatFormatting.LIGHT_PURPLE;
            case GRAY -> ChatFormatting.DARK_GRAY;
            case LIGHT_GRAY -> ChatFormatting.GRAY;
            case CYAN -> ChatFormatting.DARK_AQUA;
            case PURPLE -> ChatFormatting.DARK_PURPLE;
            case BLUE -> ChatFormatting.BLUE;
            case BROWN -> ChatFormatting.GOLD;
            case GREEN -> ChatFormatting.DARK_GREEN;
            case RED -> ChatFormatting.RED;
            case BLACK -> ChatFormatting.DARK_GRAY;
        };
    }

    /** Minecraft yaw (0 = south) pointing from {@code from} toward {@code to}. */
    private static float yawToward(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
    }

    /** True when this state is any vanilla banner (standing or wall). Tag check, never instanceof. */
    public static boolean isBanner(BlockState state) {
        return state.is(BlockTags.BANNERS);
    }

    /**
     * Registers a freshly placed banner as THE faction banner and tells the whole faction the
     * settlement is back under command. Caller has already verified the placement is in the
     * settlement's own territory and that no live banner is registered.
     */
    public static void raise(ServerLevel level, Settlement settlement, BlockPos pos) {
        convertToFactionDesign(level, settlement, pos);
        settlement.setBannerPos(pos.immutable());
        applyDesignToBlock(level, settlement);
        // Clear the "banner lost" alert — the banner is back up, the warning is stale. Broadcast
        // so the open Statuses tab drops the entry instead of holding it until the 1h timeout.
        if (settlement.removeStatusEffectsByKey("bannerbound.status.banner_lost")) {
            SettlementManager.broadcastStatusEffectsToMembers(level.getServer(), settlement);
        }
        SettlementData.get(level.getServer().overworld()).setDirty();
        // A bright, short chime on site — the inverse of the loss toll.
        level.playSound(null, pos, net.minecraft.sounds.SoundEvents.BELL_BLOCK,
            net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.4f);
        for (UUID memberId : settlement.members()) {
            ServerPlayer member = level.getServer().getPlayerList().getPlayer(memberId);
            if (member == null) continue;
            member.sendSystemMessage(Component.translatable("bannerbound.banner.raised",
                settlement.factionName()).withStyle(settlement.identityFormatting()));
        }
    }

    /**
     * The faction banner is down. Clears the registration, force-closes every open settlement
     * menu faction-wide (no banner, no command — and no cheesing menus through a war), and
     * announces it. A MEMBER taking it down (relocation) gets the quiet yellow note; anything
     * else — enemy hand, creeper, piston — is an attack: on-site toll, faction-wide toll, red
     * broadcast, and an ALERT status entry so offline members still learn of it.
     */
    public static void lose(ServerLevel level, Settlement settlement, BlockPos pos,
                            boolean memberBreak, String breakerName) {
        MinecraftServer server = level.getServer();
        settlement.setBannerPos(null);
        SettlementData.get(server.overworld()).setDirty();

        if (!memberBreak) {
            level.playSound(null, pos, net.minecraft.sounds.SoundEvents.BELL_BLOCK,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.5f, 0.6f);
            settlement.addStatusEffect(new StatusEffect(
                UUID.randomUUID(), "bannerbound.status.banner_lost",
                java.util.List.of(), StatusEffectIcon.ALERT, 0, 72_000));
            SettlementManager.broadcastStatusEffectsToMembers(server, settlement);
        }
        for (UUID memberId : settlement.members()) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member == null) continue;
            PacketDistributor.sendToPlayer(member, CloseSettlementScreensPayload.INSTANCE);
            if (memberBreak) {
                member.sendSystemMessage(Component.translatable("bannerbound.banner.taken_down",
                    breakerName).withStyle(ChatFormatting.YELLOW));
            } else {
                member.sendSystemMessage(Component.translatable("bannerbound.banner.fallen")
                    .withStyle(ChatFormatting.RED));
                boolean heardOnSite = member.level() == level
                    && member.blockPosition().closerThan(pos, 48);
                if (!heardOnSite) {
                    member.playNotifySound(net.minecraft.sounds.SoundEvents.BELL_BLOCK,
                        net.minecraft.sounds.SoundSource.AMBIENT, 1.0f, 0.6f);
                }
            }
        }
    }

    /**
     * Stale-registration sweep: if the registered banner block no longer exists (explosion,
     * piston — removals that fire no player break event), treat it as struck down. Only checks
     * when the chunk is loaded; an unloaded banner is presumed standing. Called from the gates
     * (town hall open, banner re-placement) — cheap, and exactly where staleness matters.
     */
    public static void validate(ServerLevel level, Settlement settlement) {
        BlockPos pos = settlement.bannerPos();
        if (pos == null || !level.isLoaded(pos)) return;
        if (!isBanner(level.getBlockState(pos))) {
            if (DiplomacyManager.consumeSupportLossAsTheft(level, settlement, pos)) {
                return;
            }
            lose(level, settlement, pos, false, "");
            return;
        }
        if (!DiplomacyManager.isPublicStandardValid(level, settlement)) {
            lose(level, settlement, pos, false, "");
        }
    }

    /**
     * Town-hall gate: validates, then refuses with the red "raise your banner" line when the
     * faction banner is down. Returns true when the banner stands and menus may open.
     */
    public static boolean requireRaised(ServerLevel level, ServerPlayer player, Settlement settlement) {
        validate(level, settlement);
        if (settlement.hasFactionBanner()
                && DiplomacyManager.isPublicStandardValid(level, settlement)) {
            return true;
        }
        player.sendSystemMessage(Component.translatable("bannerbound.banner.required")
            .withStyle(ChatFormatting.RED));
        return false;
    }
}
