package com.bannerbound.core.codex;

import java.util.List;

/**
 * One data-authored tutorial popup: the AAA-style interrupt modal shown at a teachable moment
 * (TUTORIAL_POPUP_PLAN.md). trigger reuses the Chronicle unlock-rule schema, so popups fire off
 * the exact same event stream as entry unlocks; an absent trigger means "fires at first login"
 * via the reconcile pass. priority "interrupt" opens the modal, "toast" downgrades to a normal
 * Chronicle toast on the linked entry. entry names the Chronicle entry unlocked alongside the
 * popup so the content stays re-readable; pages carry per-page title/text plus at most one media
 * reference (clip id in the codex_clips registry, or an image texture). Records null-normalize so
 * partial JSON stays safe.
 */
public record TutorialPopup(
    String id,
    String priority,
    boolean once,
    String entry,
    int order,
    CodexUnlockRule trigger,
    List<Page> pages
) {
    public TutorialPopup {
        id = id == null ? "" : id;
        priority = priority == null || priority.isBlank() ? "interrupt" : priority;
        entry = entry == null ? "" : entry;
        trigger = trigger == null ? CodexUnlockRule.unlockedByDefault() : trigger;
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    public boolean isInterrupt() {
        return !"toast".equalsIgnoreCase(priority);
    }

    public record Page(String title, String text, String clip, String image) {
        public Page {
            title = title == null ? "" : title;
            text = text == null ? "" : text;
            clip = clip == null ? "" : clip;
            image = image == null ? "" : image;
        }
    }
}
