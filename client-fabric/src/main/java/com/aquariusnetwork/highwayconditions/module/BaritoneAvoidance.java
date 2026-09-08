package com.aquariusnetwork.highwayconditions.module;

import com.aquariusnetwork.highwayconditions.HighwayConditionsConfig;
import com.aquariusnetwork.highwayconditions.api.LocalHazard;
import com.aquariusnetwork.highwayconditions.api.LocalHazardEvents;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Optional, opt-in ({@code cfg.baritone.autoAvoidEnabled}, default OFF) Baritone auto-detour,
 * built the same way any third-party addon would build it: entirely against
 * {@link LocalHazardEvents}, ARD's own public API (see that package's javadoc) -- this class adds
 * no privileged access ARD doesn't already expose to anyone else.
 *
 * <p><b>Zero compile-time or runtime dependency on Baritone.</b> This mod's own standing rule
 * (see {@code HighwayConditionsFabricClient}'s javadoc) is "no coupling to another client's
 * internal addon API" -- Baritone is exactly such a third-party client mod, and as of writing it
 * has no released build for this project's current Minecraft target (1.21.10; Baritone's newest
 * release targets 1.21.11) anyway, so a hard dependency would be dead weight. Instead, Baritone's
 * presence and API shape are probed once via reflection at the first hazard this session, cached,
 * and every call is wrapped so a missing class, a renamed method, or a version mismatch degrades
 * to "auto-avoid stays off this session" plus one log line -- never an exception reaching Fabric's
 * event bus or a crashed tick.
 *
 * <p>The reflected call chain mirrors Baritone's own published example verbatim:
 * {@code BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess()
 * .setGoalAndPath(new GoalXZ(x, z))}. Releasing control back to the player once the hazard
 * clears is less standardized across Baritone versions, so two plausible release paths are tried
 * ({@code IBaritone#getPathingBehavior()#cancelEverything()}, then
 * {@code ICustomGoalProcess#onLostControl()}) -- if neither resolves, Baritone simply keeps
 * heading to the last detour goal it was given, which is a safe (if imperfect) failure mode.
 */
public final class BaritoneAvoidance {

    private static final Logger LOGGER = LoggerFactory.getLogger("ard");

    /** How far ahead (along the road) to place the detour goal, past the hazard itself. */
    private static final double AHEAD_BLOCKS = 6.0;
    /** Extra clearance beyond the confirmed-blocked span for a PARTIAL obstruction. */
    private static final double CLEAR_MARGIN = 2.0;
    /** Fixed sidestep for a FULL obstruction -- the classification scan only measured lanes
     *  within its own half-width window, so a real detour distance isn't known; this is a
     *  heuristic, not a guarantee the far side is actually clear. */
    private static final double FULL_BLOCK_OFFSET = 5.0;
    /** Give up and release control if the hazard never clears within this many ticks (10s @
     *  20 tick/s) -- avoids Baritone grinding forever against a detour it can't complete. */
    private static final int MAX_ENGAGED_TICKS = 200;

    private final HighwayConditionsConfig cfg;

    private boolean probed;
    private boolean available;
    private boolean engaged;
    private int engagedTicks;

    private Object customGoalProcess;
    private Method setGoalAndPath;
    private Constructor<?> goalXZCtor;
    private Runnable canceller;   // best-effort "give control back"; may be null if unresolved

    public BaritoneAvoidance(HighwayConditionsConfig cfg) {
        this.cfg = cfg;
    }

    /** Registers this as a normal listener on ARD's own public API -- exactly what a third-party
     *  addon would do. */
    public void register() {
        LocalHazardEvents.DETECTED.register(this::onDetected);
        LocalHazardEvents.CLEARED.register(this::onCleared);
    }

    /** Call from END_CLIENT_TICK every tick -- purely the bounded-engagement watchdog. Baritone
     *  itself is driven entirely from the DETECTED/CLEARED events, not from this tick. */
    public void tick() {
        if (!engaged) {
            return;
        }
        if (++engagedTicks > MAX_ENGAGED_TICKS) {
            LOGGER.debug("Highway Conditions: Baritone auto-avoid timed out -- releasing control");
            disengage();
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal(
                    "ARD: couldn't auto-route around the obstruction -- handle it manually."), false);
            }
        }
    }

    private void onDetected(LocalHazard hazard) {
        if (!cfg.baritone.autoAvoidEnabled || !ensureProbed()) {
            return;
        }
        double offset = hazard.fullyBlocked()
            ? FULL_BLOCK_OFFSET
            : pickClearOffset(hazard.laneMin(), hazard.laneMax());
        double targetX = hazard.x() + hazard.headingX() * AHEAD_BLOCKS + hazard.perpX() * offset;
        double targetZ = hazard.z() + hazard.headingZ() * AHEAD_BLOCKS + hazard.perpZ() * offset;
        if (setGoal((int) Math.round(targetX), (int) Math.round(targetZ))) {
            engaged = true;
            engagedTicks = 0;
        }
    }

    private void onCleared() {
        if (engaged) {
            disengage();
        }
    }

    private void disengage() {
        engaged = false;
        engagedTicks = 0;
        if (canceller != null) {
            try {
                canceller.run();
            } catch (Throwable t) {
                LOGGER.debug("Highway Conditions: Baritone release-control call failed: {}", t.toString());
            }
        }
    }

    /** Detours past whichever edge of the blocked span is closer to the player's own lane
     *  (offset 0) -- a shorter sidestep than always picking one fixed side. A heuristic, not a
     *  real clearance search: see the class javadoc. */
    private static double pickClearOffset(Integer laneMinBoxed, Integer laneMaxBoxed) {
        int lo = laneMinBoxed == null ? 0 : laneMinBoxed;
        int hi = laneMaxBoxed == null ? 0 : laneMaxBoxed;
        return Math.abs(hi) <= Math.abs(lo) ? hi + CLEAR_MARGIN : lo - CLEAR_MARGIN;
    }

    private boolean setGoal(int x, int z) {
        try {
            Object goal = goalXZCtor.newInstance(x, z);
            setGoalAndPath.invoke(customGoalProcess, goal);
            return true;
        } catch (Throwable t) {
            LOGGER.debug("Highway Conditions: Baritone setGoalAndPath failed ({}) -- "
                + "disabling auto-avoid for this session", t.toString());
            available = false;
            return false;
        }
    }

    /** Resolves and caches the Baritone reflection handles the first time a hazard actually needs
     *  them (not at mod init -- Baritone may load after ARD, or not at all). */
    private boolean ensureProbed() {
        if (probed) {
            return available;
        }
        probed = true;
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal");
            Class<?> goalXZClass = Class.forName("baritone.api.pathing.goals.GoalXZ");

            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Object primary = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object goalProcess = primary.getClass().getMethod("getCustomGoalProcess").invoke(primary);

            this.customGoalProcess = goalProcess;
            this.setGoalAndPath = goalProcess.getClass().getMethod("setGoalAndPath", goalClass);
            this.goalXZCtor = goalXZClass.getConstructor(int.class, int.class);
            this.canceller = resolveCanceller(primary, goalProcess);

            available = true;
            LOGGER.info("Highway Conditions: Baritone detected -- auto-avoid armed");
        } catch (Throwable t) {
            available = false;
            LOGGER.debug("Highway Conditions: Baritone not present, or its API didn't match what "
                + "auto-avoid expects ({}) -- staying disabled this session", t.toString());
        }
        return available;
    }

    /** Two plausible "give control back" shapes across Baritone versions/forks; tries each in
     *  turn and caches whichever resolves first. Returns {@code null} (not an error -- disengage
     *  simply becomes a no-op) if neither does. */
    private static Runnable resolveCanceller(Object primary, Object goalProcess) {
        try {
            Object pathingBehavior = primary.getClass().getMethod("getPathingBehavior").invoke(primary);
            Method cancelEverything = pathingBehavior.getClass().getMethod("cancelEverything");
            return () -> invokeQuietly(cancelEverything, pathingBehavior);
        } catch (Throwable ignored) {
            // fall through to the next candidate
        }
        try {
            Method onLostControl = goalProcess.getClass().getMethod("onLostControl");
            return () -> invokeQuietly(onLostControl, goalProcess);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void invokeQuietly(Method m, Object target) {
        try {
            m.invoke(target);
        } catch (Throwable ignored) {
            // best-effort release; if this fails Baritone just keeps heading to the last goal
        }
    }
}
