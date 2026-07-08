package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.codex.TutorialPopup;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client order to queue a tutorial popup (TUTORIAL_POPUP_PLAN.md). Carries the FULL
 * page content, not just an id: tutorial JSON lives in data/ which never reaches the client
 * (same trap as the custom recipe managers), so shipping the pages in the payload keeps the
 * client dependency-free. The client controller (ClientTutorialPopups) queues by order and
 * presents when safe; entry is the Chronicle entry unlocked alongside for re-reading.
 */
@ApiStatus.Internal
public record ShowTutorialPopupPayload(
    String popupId,
    String entry,
    int order,
    List<TutorialPopup.Page> pages,
    /** True for replays (View Tutorial button, /bannerbound popup show): the client opens the
     *  modal immediately over the current screen instead of queueing behind the safety rules. */
    boolean immediate
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ShowTutorialPopupPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "show_tutorial_popup"));

    public static final StreamCodec<ByteBuf, ShowTutorialPopupPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, p.popupId());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.entry());
            ByteBufCodecs.VAR_INT.encode(buf, p.order());
            ByteBufCodecs.VAR_INT.encode(buf, p.pages().size());
            for (TutorialPopup.Page page : p.pages()) {
                ByteBufCodecs.STRING_UTF8.encode(buf, page.title());
                ByteBufCodecs.STRING_UTF8.encode(buf, page.text());
                ByteBufCodecs.STRING_UTF8.encode(buf, page.clip());
                ByteBufCodecs.STRING_UTF8.encode(buf, page.image());
            }
            ByteBufCodecs.BOOL.encode(buf, p.immediate());
        },
        buf -> {
            String popupId = ByteBufCodecs.STRING_UTF8.decode(buf);
            String entry = ByteBufCodecs.STRING_UTF8.decode(buf);
            int order = ByteBufCodecs.VAR_INT.decode(buf);
            int pageCount = ByteBufCodecs.VAR_INT.decode(buf);
            List<TutorialPopup.Page> pages = new ArrayList<>(pageCount);
            for (int i = 0; i < pageCount; i++) {
                pages.add(new TutorialPopup.Page(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf)
                ));
            }
            boolean immediate = ByteBufCodecs.BOOL.decode(buf);
            return new ShowTutorialPopupPayload(popupId, entry, order, pages, immediate);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
