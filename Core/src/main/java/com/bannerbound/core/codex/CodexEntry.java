package com.bannerbound.core.codex;

import java.util.List;

/** A data-authored Chronicle article loaded from data/<namespace>/codex_entries. */
public record CodexEntry(
    String id,
    String category,
    String title,
    String subtitle,
    String icon,
    int order,
    boolean secret,
    CodexUnlockRule unlock,
    String ponder,
    CodexTutorial tutorial,
    List<CodexPageElement> pages
) {
    public CodexEntry {
        id = id == null ? "" : id;
        category = category == null || category.isBlank() ? "bannerbound:getting_started" : category;
        title = title == null || title.isBlank() ? id : title;
        subtitle = subtitle == null ? "" : subtitle;
        icon = icon == null ? "" : icon;
        unlock = unlock == null ? CodexUnlockRule.unlockedByDefault() : unlock;
        ponder = ponder == null ? "" : ponder;
        tutorial = tutorial == null ? new CodexTutorial("", "", 10, List.of()) : tutorial;
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    public String searchableText() {
        StringBuilder builder = new StringBuilder(title).append(' ').append(subtitle);
        for (CodexPageElement page : pages) {
            builder.append(' ').append(page.text()).append(' ').append(page.caption());
        }
        return builder.toString().toLowerCase(java.util.Locale.ROOT);
    }
}
