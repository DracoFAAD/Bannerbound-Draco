package com.bannerbound.core.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Citizen;
import com.bannerbound.core.api.settlement.CitizenGender;
import com.bannerbound.core.api.settlement.Home;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.data.CitizenNameLoader;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;

/**
 * Server-side singleton that runs the procreation loop for Antiquity. Two responsibilities:
 *
 * <ol>
 *   <li><b>Once per night-start</b>, scan every settlement's homes for opposite-gender resident
 *       pairs that are both asleep, adult, and like each other above STRANGERS. Roll the
 *       per-tier chance ({@link #chanceFor}); each successful roll opens a
 *       {@link LovemakingSession} that pulses heart particles 5 times over ~12 s and ends with
 *       the woman flagged pregnant.</li>
 *   <li><b>Every tick</b>, advance active sessions, cancel any whose participants stopped
 *       sleeping / died / lost the home, and deliver babies for mothers whose pregnancy timer
 *       elapsed (this entry point — {@link #deliver} — is invoked from
 *       {@code CitizenEntity.aiStep}'s 20-tick poll).</li>
 * </ol>
 *
 * <p><b>Pair-selection invariants</b>: a woman can conceive at most once per night (first
 * successful roll locks her out of further pairs), and a man can father at most once per night
 * (locks the same way). Iteration order is randomised so the same (M, F) doesn't always win.
 * Pairs that share a home cross-bucket the same way the user intended: a 2M+2F home can
 * produce up to 2 pregnancies per night.
 */
@ApiStatus.Internal
public final class BabyMakingManager {
    /** Matches {@code SleepGoal}'s night window so the mate-scan fires while citizens are
     *  actually in bed. {@code 12500} ticks past midnight is when monsters spawn / beds become
     *  usable; {@code 23460} is when natural wake happens. */
    private static final long NIGHT_START = 12_500L;
    private static final long NIGHT_END = 23_460L;
    /** How often we re-scan during the night for newly-eligible pairs. 100 ticks = 5 s. The
     *  one-shot-at-night-start design missed pairs that hadn't reached bed yet at NIGHT_START
     *  (or hadn't been homed yet, or just got their relationship bumped past STRANGERS). */
    private static final int SCAN_INTERVAL_TICKS = 100;
    /** Ticks between heart-particle bursts. 60 = 3 s. */
    private static final int LOVEMAKING_BURST_INTERVAL = 60;
    /** Total bursts in a session. After the 5th burst the woman becomes pregnant. */
    private static final int LOVEMAKING_BURSTS = 5;

    /** Day index ({@code dayTime / 24000}) of the current night. {@code -1} when we're in
     *  daytime (no night in progress). Detecting a transition from -1 → real day means "new
     *  night just started" and we clear {@link #ROLLED_TONIGHT}. */
    private static long currentNightDay = -1L;
    /** Women who have already been rolled for during the current night — both successes (now
     *  pregnant) and failures (skipped from re-rolling). Cleared on each new night so a
     *  citizen who narrowly missed last night gets another chance tomorrow. */
    private static final java.util.Set<UUID> ROLLED_TONIGHT = new java.util.HashSet<>();
    /** Active sessions. Server-only, never persisted; a save mid-session just drops it. */
    private static final List<LovemakingSession> SESSIONS = new ArrayList<>();

    private BabyMakingManager() {}

    /** Wired in {@code ResearchEvents.onServerTick} alongside the other per-tick managers.
     *  Cheap on a no-pregnancy server: re-scans every 5 s during night only, session list is
     *  usually empty.
     *
     *  <p><b>Why periodic instead of one-shot</b>: the original one-shot-at-NIGHT_START version
     *  fired before citizens actually reached their beds — the scan found everyone awake and
     *  walking, no eligible pairs, then never re-scanned. Periodic scanning catches every pair
     *  the moment both partners are in bed, and the {@link #ROLLED_TONIGHT} set ensures each
     *  woman only gets one chance per night (success → pregnant, fail → skipped till tomorrow). */
    public static void tickAll(MinecraftServer server) {
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        long now = overworld.getGameTime();
        long t = overworld.getDayTime() % 24_000L;
        long day = overworld.getDayTime() / 24_000L;
        boolean isNight = t >= NIGHT_START && t < NIGHT_END;

        if (!isNight) {
            // Daytime: reset state for the next night.
            if (currentNightDay != -1L) {
                currentNightDay = -1L;
                ROLLED_TONIGHT.clear();
            }
        } else {
            // Night → first tick after the transition (or first tick of a brand-new night via
            // /time set night) clears the "already rolled" set so every eligible woman gets a
            // fresh chance.
            if (currentNightDay != day) {
                currentNightDay = day;
                ROLLED_TONIGHT.clear();
            }
            // Re-scan every SCAN_INTERVAL_TICKS for newly-eligible pairs. Cheap — outer loop
            // over loaded settlements, inner loop over their valid homes only.
            if (now % SCAN_INTERVAL_TICKS == 0) {
                startSessions(overworld, now);
            }
        }

        if (!SESSIONS.isEmpty()) {
            tickSessions(overworld, now);
        }
    }

    private static void startSessions(ServerLevel overworld, long now) {
        SettlementData sd = SettlementData.get(overworld);
        RandomSource rng = overworld.random;
        for (Settlement s : sd.all()) {
            // Population gate: pop maximum bounds total citizens. If we're already at the cap,
            // no new lovemaking sessions can fire — even if homes have eligible pairs sleeping
            // in them. The cap grows when the player adds true spare beds (see
            // Settlement.populationMaximum), which is the only way past the immigration floor
            // (Antiquity = 7 per Era.immigrationFloor).
            if (s.population() >= s.populationMaximum()) continue;
            for (Home home : s.homes().values()) {
                if (!home.valid()) continue;
                List<CitizenEntity> men = new ArrayList<>();
                List<CitizenEntity> women = new ArrayList<>();
                for (UUID rid : home.residents()) {
                    Entity raw = overworld.getEntity(rid);
                    if (!(raw instanceof CitizenEntity ce)) continue;
                    if (!ce.isAlive() || ce.isChild() || !ce.isSleeping()) continue;
                    if (ce.getGender() == CitizenGender.MALE) {
                        // Men can also only father once per night — same set is used to skip them.
                        if (!ROLLED_TONIGHT.contains(ce.getUUID())) men.add(ce);
                    } else if (!ce.isPregnant() && !ROLLED_TONIGHT.contains(ce.getUUID())) {
                        women.add(ce);
                    }
                }
                if (men.isEmpty() || women.isEmpty()) continue;
                // Shuffle so the same M/F pair doesn't always win when multiple are eligible.
                Random javaRng = new Random(rng.nextLong());
                Collections.shuffle(men, javaRng);
                Collections.shuffle(women, javaRng);
                Set<UUID> takenWomen = new HashSet<>();
                Set<UUID> takenMen = new HashSet<>();
                for (CitizenEntity m : men) {
                    if (takenMen.contains(m.getUUID())) continue;
                    for (CitizenEntity w : women) {
                        if (takenWomen.contains(w.getUUID())) continue;
                        Relationship rel = m.getRelationships().get(w.getUUID());
                        if (rel.isFamily()) continue;
                        // Relationship tier sets the baseline; the home's HAPPINESS (appeal + met
                        // demands) nudges it. A delightful home can lift even strangers (0 baseline)
                        // above zero, so procreation can fire regardless of relationship status; a
                        // miserable home drags the combined chance down (possibly below zero → no roll).
                        double chance = (chanceFor(rel.tier())
                            + com.bannerbound.core.api.settlement.HomeDemand.reproductionBonus(
                                home.cachedHomeHappiness()))
                            * com.bannerbound.core.Config.BIRTH_RATE_MULTIPLIER.get();
                        if (chance <= 0.0) continue;
                        // Roll the moment we see this pair as eligible — independent of how
                        // many ticks they've been sleeping. Whether the roll succeeds or fails,
                        // both citizens get tagged so they don't re-roll later this same night
                        // (one chance per pair-member per night).
                        boolean success = rng.nextDouble() < chance;
                        ROLLED_TONIGHT.add(w.getUUID());
                        ROLLED_TONIGHT.add(m.getUUID());
                        takenWomen.add(w.getUUID());
                        takenMen.add(m.getUUID());
                        if (success) {
                            SESSIONS.add(new LovemakingSession(
                                w.getUUID(), m.getUUID(), home.pos(), now));
                        }
                        break; // this man's done for tonight; on to the next man
                    }
                }
            }
        }
    }

    private static void tickSessions(ServerLevel overworld, long now) {
        Iterator<LovemakingSession> it = SESSIONS.iterator();
        while (it.hasNext()) {
            LovemakingSession sess = it.next();
            Entity mEnt = overworld.getEntity(sess.motherId());
            Entity fEnt = overworld.getEntity(sess.fatherId());
            if (!(mEnt instanceof CitizenEntity mother) || !(fEnt instanceof CitizenEntity father)) {
                it.remove(); continue;
            }
            if (!mother.isAlive() || !father.isAlive()
                || !mother.isSleeping() || !father.isSleeping()) {
                it.remove(); continue;
            }
            long elapsed = now - sess.startTick();
            // Bursts fire on elapsed = 0, 60, 120, 180, 240 — 5 bursts total. Negative elapsed
            // shouldn't happen (sessions are made with now as startTick) but guard anyway.
            if (elapsed < 0 || elapsed % LOVEMAKING_BURST_INTERVAL != 0) continue;
            int burstIdx = (int) (elapsed / LOVEMAKING_BURST_INTERVAL);
            if (burstIdx >= LOVEMAKING_BURSTS) {
                it.remove(); continue;
            }
            SocialEvents.spawnHearts(overworld, mother);
            SocialEvents.spawnHearts(overworld, father);
            if (burstIdx == LOVEMAKING_BURSTS - 1) {
                // 5th and final burst — pregnancy starts on this same tick.
                mother.setPregnant(true, now, father.getUUID());
                it.remove();
            }
        }
    }

    /** Tier → per-night pregnancy probability for one (M, F) pair. STRANGERS and the negative
     *  tiers return 0; FRIENDS_FOR_LIFE is the proxy for the eventual Lover overflow until that
     *  ships — 100% is intentional so admins can test the birth path quickly via
     *  {@code /bannerbound set_relationship} without waiting on probability rolls. */
    private static double chanceFor(RelationshipTier tier) {
        return switch (tier) {
            case ACQUAINTANCES    -> 0.15;
            case FRIENDS          -> 0.30;
            case CLOSE_FRIENDS    -> 0.65;
            case FRIENDS_FOR_LIFE -> 1.0;
            default               -> 0.0;
        };
    }

    /** Called from {@code CitizenEntity.aiStep}'s 20-tick poll when a pregnant mother's timer
     *  has elapsed. Spawns the child at her position, installs the FAMILY bond on both parents,
     *  fires the birth thoughts + chat broadcast, and clears the pregnancy state. */
    public static void deliver(CitizenEntity mother, ServerLevel sl, long now) {
        UUID fatherId = mother.getPregnancyFatherId();
        Settlement s = mother.getSettlement();
        if (s == null) {
            // Orphan mother — clear pregnancy quietly, no birth.
            mother.setPregnant(false, -1L, null);
            return;
        }
        // Population gate: babies don't deliver while the settlement is at its population cap.
        // Pregnancy is held open (no setPregnant(false) call) so the next 20-tick poll retries
        // — birth will happen the moment the cap rises (player builds a bed) or pop drops
        // (someone dies / is exiled). This is intentionally a soft gate: pregnancies aren't
        // cancelled, just postponed.
        if (s.population() >= s.populationMaximum()) return;
        CitizenEntity child = BannerboundCore.CITIZEN.get().create(sl);
        if (child == null) {
            mother.setPregnant(false, -1L, null);
            return;
        }
        CitizenGender gender = sl.random.nextBoolean() ? CitizenGender.MALE : CitizenGender.FEMALE;
        String name = CitizenNameLoader.randomName(sl.random, s.age(), gender);
        child.initializeCitizen(s.id(), name, gender, s.age(), s.identityFormatting());
        // Use the language-baked name (set by initializeCitizen) for the roster so chat / recall /
        // workshop surfaces read the same in-language name as the name tag.
        name = child.getCitizenName();
        child.setIsChild(true);
        child.setBornAtTick(now);
        child.setMotherId(mother.getUUID());
        child.moveTo(mother.getX(), mother.getY(), mother.getZ(), 0f, 0f);
        child.finalizeSpawn(sl, sl.getCurrentDifficultyAt(mother.blockPosition()),
            MobSpawnType.MOB_SUMMONED, null);
        if (!sl.addFreshEntity(child)) {
            // Spawn failed (rare — usually chunk loading) — clear pregnancy without effects.
            mother.setPregnant(false, -1L, null);
            return;
        }
        s.addCitizen(new Citizen(child.getUUID(), name));

        // Family bonds: child ↔ mother always; child ↔ father if he's still loaded + alive.
        SocialEvents.linkMutualFamily(child, mother);
        CitizenEntity father = null;
        if (fatherId != null && sl.getEntity(fatherId) instanceof CitizenEntity fe && fe.isAlive()) {
            father = fe;
            SocialEvents.linkMutualFamily(child, father);
        }

        // Sibling bonds: every other living citizen born to this same mother is the new child's
        // sibling, so install the FAMILY bond between them. Resolves through the roster (tolerates
        // unloaded chunks — those siblings just miss this pass; the bond is one-directional-safe
        // since linkMutualFamily writes both sides). Pre-feature children with no recorded mother
        // (motherId == null) are skipped, which correctly avoids matching other motherless citizens.
        for (Citizen c : s.citizens()) {
            if (c.entityId().equals(child.getUUID())) continue;
            if (!(sl.getEntity(c.entityId()) instanceof CitizenEntity sibling)) continue;
            if (!sibling.isAlive() || !mother.getUUID().equals(sibling.getMotherId())) continue;
            SocialEvents.linkMutualFamily(child, sibling);
        }

        // Mother + father get the per-partner MY_CHILD_BORN thought, keyed to the new child.
        mother.getThoughts().add(ThoughtKind.MY_CHILD_BORN, child.getUUID(), now, sl.random);
        mother.recomputeHappiness();
        if (father != null) {
            father.getThoughts().add(ThoughtKind.MY_CHILD_BORN, child.getUUID(), now, sl.random);
            father.recomputeHappiness();
        }

        // Every other settlement citizen gets the single-instance NEW_CHILD_IN_SETTLEMENT.
        // Iterating the roster + resolving the entity tolerates unloaded chunks (those citizens
        // just miss this one — same forgiving model as the weather thought broadcast).
        for (Citizen c : s.citizens()) {
            if (c.entityId().equals(mother.getUUID()) || c.entityId().equals(child.getUUID())) continue;
            if (father != null && c.entityId().equals(father.getUUID())) continue;
            Entity raw = sl.getEntity(c.entityId());
            if (!(raw instanceof CitizenEntity ce)) continue;
            ce.getThoughts().add(ThoughtKind.NEW_CHILD_IN_SETTLEMENT, null, now, sl.random);
            ce.recomputeHappiness();
        }

        // Chat broadcast — uses display-name components so settlement colour + gender glyphs
        // come through styled.
        MinecraftServer server = sl.getServer();
        if (server != null) {
            Component msg = Component.translatable("bannerbound.birth.broadcast",
                    mother.getDisplayName(), child.getDisplayName())
                .withStyle(ChatFormatting.LIGHT_PURPLE);
            for (UUID memberId : s.members()) {
                ServerPlayer p = server.getPlayerList().getPlayer(memberId);
                if (p != null) p.sendSystemMessage(msg);
            }
        }

        // Heart particle above the mother to mark the moment visually.
        SocialEvents.spawnHearts(sl, mother);

        // Clear pregnancy LAST so the refreshDisplayName fires after the chat message captured
        // the still-pregnant display name. Cosmetic but reads better in chat.
        mother.setPregnant(false, -1L, null);

        // Persist the settlement so the new citizen, family bonds, and roster entry survive a
        // reload without waiting for the next dirty-batch.
        SettlementData.get(sl).setDirty();
    }

    /** Chat broadcast for the child → adult transition. Called from {@code CitizenEntity.aiStep}
     *  when the aging timer elapses; the flag flip itself happens at the call site. */
    public static void broadcastGrewUp(ServerLevel sl, CitizenEntity child) {
        MinecraftServer server = sl.getServer();
        if (server == null) return;
        Settlement s = child.getSettlement();
        if (s == null) return;
        Component msg = Component.translatable("bannerbound.grewup.broadcast",
                child.getDisplayName())
            .withStyle(ChatFormatting.GRAY);
        for (UUID memberId : s.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(memberId);
            if (p != null) p.sendSystemMessage(msg);
        }
    }
}
