package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.codex.CodexCategory;
import com.bannerbound.core.codex.CodexEntry;
import com.bannerbound.core.codex.CodexPageElement;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** S->C Chronicle catalog plus the viewer's unlocked/seen state. The nested Category, PageElement
 *  and Entry records mirror the server-side codex model (built via their {@code from(...)} factories)
 *  and each carries its own StreamCodec; handled client-side by ClientChronicleState.replace. */
public record CodexSyncPayload(
    List<Category> categories,
    List<Entry> entries,
    List<String> unlocked,
    List<String> seen,
    boolean autoPinTutorial,
    boolean tutorialPopupsEnabled
) implements CustomPacketPayload {
    public record Category(String id, String title, String icon, int order) {
        public static final StreamCodec<ByteBuf, Category> STREAM_CODEC = StreamCodec.of(
            (buf, c) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, c.id());
                ByteBufCodecs.STRING_UTF8.encode(buf, c.title());
                ByteBufCodecs.STRING_UTF8.encode(buf, c.icon());
                ByteBufCodecs.VAR_INT.encode(buf, c.order());
            },
            buf -> new Category(
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf)
            )
        );

        public static Category from(CodexCategory category) {
            return new Category(category.id(), category.title(), category.icon(), category.order());
        }
    }

    public record PageElement(
        String type,
        String text,
        String caption,
        String entry,
        String clip,
        String image,
        String recipe,
        List<String> items
    ) {
        public static final StreamCodec<ByteBuf, PageElement> STREAM_CODEC = StreamCodec.of(
            (buf, e) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, e.type());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.text());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.caption());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.entry());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.clip());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.image());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.recipe());
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, e.items());
            },
            buf -> new PageElement(
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf)
            )
        );

        public static PageElement from(CodexPageElement element) {
            return new PageElement(element.type(), element.text(), element.caption(), element.entry(),
                element.clip(), element.image(), element.recipe(), element.items());
        }
    }

    public record Entry(
        String id,
        String category,
        String title,
        String subtitle,
        String icon,
        int order,
        boolean secret,
        String ponder,
        List<PageElement> pages,
        String searchableText,
        /** Linked tutorial popup id ("" = none) - drives the View Tutorial button. An entry whose
         *  tutorial equals its own id is a server-synthesized Tutorials archive of that popup. */
        String tutorial
    ) {
        public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.of(
            (buf, e) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, e.id());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.category());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.title());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.subtitle());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.icon());
                ByteBufCodecs.VAR_INT.encode(buf, e.order());
                ByteBufCodecs.BOOL.encode(buf, e.secret());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.ponder());
                PageElement.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, e.pages());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.searchableText());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.tutorial());
            },
            buf -> new Entry(
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.BOOL.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                PageElement.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf)
            )
        );

        public static Entry from(CodexEntry entry) {
            List<PageElement> pages = new ArrayList<>(entry.pages().size());
            for (CodexPageElement page : entry.pages()) pages.add(PageElement.from(page));
            return new Entry(entry.id(), entry.category(), entry.title(), entry.subtitle(), entry.icon(),
                entry.order(), entry.secret(), entry.ponder(), pages, entry.searchableText(), "");
        }
    }

    public static final CustomPacketPayload.Type<CodexSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "codex_sync"));

    public static final StreamCodec<ByteBuf, CodexSyncPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            Category.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, p.categories());
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, p.entries());
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, p.unlocked());
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, p.seen());
            ByteBufCodecs.BOOL.encode(buf, p.autoPinTutorial());
            ByteBufCodecs.BOOL.encode(buf, p.tutorialPopupsEnabled());
        },
        buf -> new CodexSyncPayload(
            Category.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf),
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.BOOL.decode(buf)
        )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
