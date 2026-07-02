package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The wall-preview screen's payload family (WALLS_PLAN.md Phase 3). The open payload composes
 * the territory screen's base payload (camera/slabs/claims reuse) with a compact piece list
 * for the border polyline + gate slots. All client-bound payloads MUST be registered in both
 * dist branches of {@link BannerboundNetwork} (standing rule).
 */
@ApiStatus.Internal
public final class WallScreenPayloads {

    private WallScreenPayloads() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, path);
    }

    /** One wall piece: flat-preview geometry plus the vertical data the 3D refinement view
     *  needs (design bottom Y, design height, ground extremes under the footprint).
     *  {@code designIndex} = index into {@code OpenWallPreview.designs()} (-1 = unresolved),
     *  so per-piece variant overrides render their REAL design;
     *  {@code noFoundation} = the per-piece continuation suppression refinement. */
    public record PieceLite(int startX, int startZ, int length, int depth,
                            int outward2d, int kindOrdinal, boolean waterGap,
                            int baseY, int designHeight, int minGround, int maxGround,
                            int designIndex, boolean noFoundation) {
        public int topY() {
            return baseY + designHeight - 1;
        }

        /** Gate-slot identity (y = 0) — gates replace segments at the same slot on purpose. */
        public long anchor() {
            return net.minecraft.core.BlockPos.asLong(startX, 0, startZ);
        }

        /** Refinement identity: KIND-AWARE (y = kind + 1) — a corner and a segment can share
         *  a start column in some orientations, and a position-only key raised them BOTH
         *  (playtest 2026-06-12). */
        public long refineAnchor() {
            return net.minecraft.core.BlockPos.asLong(startX, kindOrdinal + 1, startZ);
        }
    }

    /** C→S: move one slot's wall top by {@code delta} courses (0 = reset to auto). */
    public record RefineWallTop(long anchor, int delta) implements CustomPacketPayload {
        public static final Type<RefineWallTop> TYPE = new Type<>(id("refine_wall_top"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RefineWallTop> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeLong(p.anchor());
                buf.writeVarInt(p.delta());
            }, buf -> new RefineWallTop(buf.readLong(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C→S: open (or refresh) the wall preview for the sender's settlement. */
    public record RequestWallPreview() implements CustomPacketPayload {
        public static final Type<RequestWallPreview> TYPE = new Type<>(id("request_wall_preview"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestWallPreview> STREAM_CODEC =
            StreamCodec.unit(new RequestWallPreview());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C→S: cycle the selected piece's design variant (refine anchor; Default → Steps ▲ →
     *  Steps ▼). Segments and corners only. */
    public record CycleWallVariant(long anchor) implements CustomPacketPayload {
        public static final Type<CycleWallVariant> TYPE = new Type<>(id("cycle_wall_variant"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CycleWallVariant> STREAM_CODEC =
            StreamCodec.of((buf, p) -> buf.writeLong(p.anchor()),
                buf -> new CycleWallVariant(buf.readLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C→S: toggle the selected piece's bottom-course continuation (refine anchor). */
    public record ToggleWallFoundation(long anchor) implements CustomPacketPayload {
        public static final Type<ToggleWallFoundation> TYPE = new Type<>(id("toggle_wall_foundation"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ToggleWallFoundation> STREAM_CODEC =
            StreamCodec.of((buf, p) -> buf.writeLong(p.anchor()),
                buf -> new ToggleWallFoundation(buf.readLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C→S: toggle a gate at a segment-slot start (packed (x, 0, z)). */
    public record ToggleWallGate(long anchor) implements CustomPacketPayload {
        public static final Type<ToggleWallGate> TYPE = new Type<>(id("toggle_wall_gate"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ToggleWallGate> STREAM_CODEC =
            StreamCodec.of((buf, p) -> buf.writeLong(p.anchor()),
                buf -> new ToggleWallGate(buf.readLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C→S: show ({@code true}) or hide the sender-only ghost preview of the CURRENT
     *  uncommitted layout — walk-around inspection of gates/towers before committing. Hide
     *  re-syncs the committed state. */
    public record PreviewWallGhosts(boolean show) implements CustomPacketPayload {
        public static final Type<PreviewWallGhosts> TYPE = new Type<>(id("preview_wall_ghosts"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PreviewWallGhosts> STREAM_CODEC =
            StreamCodec.of((buf, p) -> buf.writeBoolean(p.show()),
                buf -> new PreviewWallGhosts(buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C→S: commit the previewed layout as the settlement's wall plan. */
    public record ConstructWalls() implements CustomPacketPayload {
        public static final Type<ConstructWalls> TYPE = new Type<>(id("construct_walls"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConstructWalls> STREAM_CODEC =
            StreamCodec.unit(new ConstructWalls());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C→S: cancel the committed wall plan (standing blocks retire into the demolition queue). */
    public record CancelWallPlan() implements CustomPacketPayload {
        public static final Type<CancelWallPlan> TYPE = new Type<>(id("cancel_wall_plan"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CancelWallPlan> STREAM_CODEC =
            StreamCodec.unit(new CancelWallPlan());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ─── Wall Designer (Phase 5) ────────────────────────────────────────────────────────────

    /** Wire format for a {@link com.bannerbound.core.api.walls.WallDesign}: palette as global
     *  block-state ids ({@code Block.getId}/{@code stateById}) — no registry lookups needed. */
    public static void writeDesign(RegistryFriendlyByteBuf buf,
                                   com.bannerbound.core.api.walls.WallDesign design) {
        buf.writeUtf(design.id());
        buf.writeUtf(design.name());
        buf.writeByte(design.kind().ordinal());
        buf.writeVarInt(design.length());
        buf.writeVarInt(design.depth());
        buf.writeVarInt(design.height());
        java.util.List<net.minecraft.world.level.block.state.BlockState> palette = design.palette();
        buf.writeVarInt(palette.size());
        for (net.minecraft.world.level.block.state.BlockState state : palette) {
            buf.writeVarInt(net.minecraft.world.level.block.Block.getId(state));
        }
        buf.writeByteArray(design.voxelsCopy());
        buf.writeVarInt(net.minecraft.world.level.block.Block.getId(design.foundation()));
    }

    public static com.bannerbound.core.api.walls.WallDesign readDesign(RegistryFriendlyByteBuf buf) {
        String id = buf.readUtf();
        String name = buf.readUtf();
        com.bannerbound.core.api.walls.WallDesign.Kind kind =
            com.bannerbound.core.api.walls.WallDesign.Kind.values()[buf.readByte()];
        int length = buf.readVarInt();
        int depth = buf.readVarInt();
        int height = buf.readVarInt();
        int paletteSize = buf.readVarInt();
        java.util.List<net.minecraft.world.level.block.state.BlockState> palette =
            new ArrayList<>(paletteSize);
        for (int i = 0; i < paletteSize; i++) {
            palette.add(net.minecraft.world.level.block.Block.stateById(buf.readVarInt()));
        }
        byte[] voxels = buf.readByteArray();
        net.minecraft.world.level.block.state.BlockState foundation =
            net.minecraft.world.level.block.Block.stateById(buf.readVarInt());
        return new com.bannerbound.core.api.walls.WallDesign(
            id, name, kind, length, depth, height, palette, voxels, foundation);
    }

    /** C→S: open the wall designer (server replies with {@link OpenWallDesigner}). */
    public record RequestWallDesigner() implements CustomPacketPayload {
        public static final Type<RequestWallDesigner> TYPE = new Type<>(id("request_wall_designer"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestWallDesigner> STREAM_CODEC =
            StreamCodec.unit(new RequestWallDesigner());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S→C: the settlement's ACTIVE design set (wall, corner, gate — defaults when unedited)
     *  plus the RESEARCHED placeable blocks (item ids) for the picker, with parallel
     *  OWNED counts (stockpiles + requester's inventory) backing the "Owned only" filter.
     *  {@code drafts} = unsaved working copies persisted in world data (designer autosave on
     *  close) — they override the active set in the editor so Escape never loses work. */
    public record OpenWallDesigner(List<com.bannerbound.core.api.walls.WallDesign> activeSet,
                                   int[] knownBlockItemIds,
                                   int[] ownedCounts,
                                   List<com.bannerbound.core.api.walls.WallDesign> drafts,
                                   List<com.bannerbound.core.api.walls.WallDesign> library)
        implements CustomPacketPayload {
        public static final Type<OpenWallDesigner> TYPE = new Type<>(id("open_wall_designer"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenWallDesigner> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeVarInt(p.activeSet().size());
                for (com.bannerbound.core.api.walls.WallDesign design : p.activeSet()) {
                    writeDesign(buf, design);
                }
                buf.writeVarIntArray(p.knownBlockItemIds());
                buf.writeVarIntArray(p.ownedCounts());
                buf.writeVarInt(p.drafts().size());
                for (com.bannerbound.core.api.walls.WallDesign design : p.drafts()) {
                    writeDesign(buf, design);
                }
                buf.writeVarInt(p.library().size());
                for (com.bannerbound.core.api.walls.WallDesign design : p.library()) {
                    writeDesign(buf, design);
                }
            }, buf -> {
                int n = buf.readVarInt();
                List<com.bannerbound.core.api.walls.WallDesign> designs = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    designs.add(readDesign(buf));
                }
                int[] known = buf.readVarIntArray();
                int[] owned = buf.readVarIntArray();
                int d = buf.readVarInt();
                List<com.bannerbound.core.api.walls.WallDesign> drafts = new ArrayList<>(d);
                for (int i = 0; i < d; i++) {
                    drafts.add(readDesign(buf));
                }
                int l = buf.readVarInt();
                List<com.bannerbound.core.api.walls.WallDesign> library = new ArrayList<>(l);
                for (int i = 0; i < l; i++) {
                    library.add(readDesign(buf));
                }
                return new OpenWallDesigner(designs, known, owned, drafts, library);
            });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C→S: delete a saved design from the settlement library (the Designer's library list,
     *  Shift+click). Deleting the active design falls back to the built-in default. */
    public record DeleteWallDesign(String designId) implements CustomPacketPayload {
        public static final Type<DeleteWallDesign> TYPE = new Type<>(id("delete_wall_design"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DeleteWallDesign> STREAM_CODEC =
            StreamCodec.of((buf, p) -> buf.writeUtf(p.designId()),
                buf -> new DeleteWallDesign(buf.readUtf()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C→S: save an edited design. {@code draft} = silent autosave of the working copy
     *  (persisted in world data, no validation/activation — closing the designer never loses
     *  work); otherwise the server re-validates everything and makes it active. */
    public record SaveWallDesign(com.bannerbound.core.api.walls.WallDesign design, boolean draft)
        implements CustomPacketPayload {
        public static final Type<SaveWallDesign> TYPE = new Type<>(id("save_wall_design"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SaveWallDesign> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                writeDesign(buf, p.design());
                buf.writeBoolean(p.draft());
            }, buf -> new SaveWallDesign(readDesign(buf), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S→C: transient status line rendered INSIDE the open wall screen — gate errors, save
     *  confirmations, construct results. Replaces chat messages (chat bloat, playtest
     *  2026-06-12); falls back to the vanilla action bar when no wall screen is open. */
    public record WallStatus(String message, boolean error) implements CustomPacketPayload {
        public static final Type<WallStatus> TYPE = new Type<>(id("wall_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WallStatus> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeUtf(p.message());
                buf.writeBoolean(p.error());
            }, buf -> new WallStatus(buf.readUtf(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S→C: open/refresh the wall preview screen. {@code gateLength} = the active gate
     *  design's length, so hover can show the true span a gate would occupy before clicking.
     *  {@code planCurrent} = the committed plan still matches the layout being previewed —
     *  false means the BUILT wall is an older design and Construct would replace it.
     *  {@code designs} = the ACTIVE design set (wall, corner, gate) so the refine view can
     *  render the real blocks instead of placeholder boxes. */
    public record OpenWallPreview(OpenExpandTerritoryScreenPayload base, List<PieceLite> pieces,
                                  boolean hasPlan, int completenessPercent,
                                  int gateLength, boolean openRefine, boolean planCurrent,
                                  List<com.bannerbound.core.api.walls.WallDesign> designs)
        implements CustomPacketPayload {
        public static final Type<OpenWallPreview> TYPE = new Type<>(id("open_wall_preview"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenWallPreview> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                OpenExpandTerritoryScreenPayload.STREAM_CODEC.encode(buf, p.base());
                buf.writeVarInt(p.pieces().size());
                for (PieceLite piece : p.pieces()) {
                    buf.writeVarInt(piece.startX());
                    buf.writeVarInt(piece.startZ());
                    buf.writeVarInt(piece.length());
                    buf.writeByte(piece.depth());
                    buf.writeByte(piece.outward2d());
                    buf.writeByte(piece.kindOrdinal());
                    buf.writeBoolean(piece.waterGap());
                    buf.writeVarInt(piece.baseY());
                    buf.writeVarInt(piece.designHeight());
                    buf.writeVarInt(piece.minGround());
                    buf.writeVarInt(piece.maxGround());
                    buf.writeVarInt(piece.designIndex() + 1); // -1 → 0 (varint-safe)
                    buf.writeBoolean(piece.noFoundation());
                }
                buf.writeBoolean(p.hasPlan());
                buf.writeVarInt(p.completenessPercent());
                buf.writeVarInt(p.gateLength());
                buf.writeBoolean(p.openRefine());
                buf.writeBoolean(p.planCurrent());
                buf.writeVarInt(p.designs().size());
                for (com.bannerbound.core.api.walls.WallDesign design : p.designs()) {
                    writeDesign(buf, design);
                }
            }, buf -> {
                OpenExpandTerritoryScreenPayload base =
                    OpenExpandTerritoryScreenPayload.STREAM_CODEC.decode(buf);
                int n = buf.readVarInt();
                List<PieceLite> pieces = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    pieces.add(new PieceLite(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                        buf.readByte(), buf.readByte(), buf.readByte(), buf.readBoolean(),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt() - 1, buf.readBoolean()));
                }
                boolean hasPlan = buf.readBoolean();
                int completeness = buf.readVarInt();
                int gateLength = buf.readVarInt();
                boolean openRefine = buf.readBoolean();
                boolean planCurrent = buf.readBoolean();
                int designCount = buf.readVarInt();
                List<com.bannerbound.core.api.walls.WallDesign> designs = new ArrayList<>(designCount);
                for (int i = 0; i < designCount; i++) {
                    designs.add(readDesign(buf));
                }
                return new OpenWallPreview(base, pieces, hasPlan, completeness,
                    gateLength, openRefine, planCurrent, designs);
            });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
