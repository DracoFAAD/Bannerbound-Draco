package com.bannerbound.core.social;

import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * Transient (server-only, never persisted) record describing one in-progress procreation
 * exchange between a male and a female citizen sharing a home at night. Lives on
 * {@link BabyMakingManager}'s session list until either:
 * <ul>
 *   <li>The 5th heart-particle burst completes → woman is flagged pregnant, session dropped.</li>
 *   <li>Either citizen stops sleeping / dies / leaves the home → session cancelled silently.</li>
 * </ul>
 * Storing this as a record (not as fields on the entity) keeps the protagonists' actual state
 * untouched while the lovemaking is mid-sequence — saving the world mid-session just loses
 * the session, which is the correct failure mode.
 */
public record LovemakingSession(UUID motherId, UUID fatherId, BlockPos homePos, long startTick) {
}
