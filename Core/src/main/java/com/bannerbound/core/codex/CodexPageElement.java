package com.bannerbound.core.codex;

import java.util.List;

/** One authorable block in a Chronicle article. */
public record CodexPageElement(
    String type,
    String text,
    String caption,
    String entry,
    String clip,
    String image,
    String recipe,
    List<String> items
) {
    public CodexPageElement {
        type = type == null || type.isBlank() ? "text" : type;
        text = text == null ? "" : text;
        caption = caption == null ? "" : caption;
        entry = entry == null ? "" : entry;
        clip = clip == null ? "" : clip;
        image = image == null ? "" : image;
        recipe = recipe == null ? "" : recipe;
        items = items == null ? List.of() : List.copyOf(items);
    }
}
