package com.bannerbound.core.codex;

/** A Chronicle sidebar category loaded from data/<namespace>/codex_categories. */
public record CodexCategory(String id, String title, String icon, int order) {
    public CodexCategory {
        id = id == null ? "" : id;
        title = title == null || title.isBlank() ? id : title;
        icon = icon == null ? "" : icon;
    }
}
