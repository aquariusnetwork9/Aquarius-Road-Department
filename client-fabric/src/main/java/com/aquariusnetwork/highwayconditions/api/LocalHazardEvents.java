package com.aquariusnetwork.highwayconditions.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/**
 * The public extension point for ARD's live, local obstruction detection — everything in this
 * {@code api} package is the stable surface third-party mods (a Meteor addon, a Baritone add-on,
 * or anything else) are meant to depend on directly; everything outside it is internal and can
 * change without notice.
 *
 * <p>Standard Fabric API event idiom ({@link Event}/{@link EventFactory}), the same pattern
 * {@code fabric-api} itself uses (e.g. {@code PlayerBlockBreakEvents}) — a consumer just adds this
 * mod's jar as a normal Gradle dependency and registers a listener:
 *
 * <pre>{@code
 * LocalHazardEvents.DETECTED.register(hazard -> {
 *     if (hazard.fullyBlocked()) {
 *         // e.g. drive a Baritone goal, sound an alert, draw a world-space marker...
 *     }
 * });
 * LocalHazardEvents.CLEARED.register(() -> {
 *     // the hazard that last fired DETECTED is no longer active
 * });
 * }</pre>
 *
 * <p>Both events fire on the render/client thread (from {@code END_CLIENT_TICK}), never off-thread
 * — a listener can safely touch world/player state or queue its own Baritone/HUD calls directly.
 * ARD itself never registers a listener that mutates world state except the opt-in Baritone
 * add-on, which behaves exactly like any other third-party consumer of this same API.
 */
public final class LocalHazardEvents {

    private LocalHazardEvents() {}

    /** Fires once, edge-triggered, the moment a local obstruction is confirmed (never re-fires
     *  for the same ongoing hazard — mirrors {@code ObstructionWatcher}'s own edge-triggered
     *  severity escalation, so a listener sees exactly one DETECTED per hazard episode per
     *  severity increase). */
    public static final Event<Detected> DETECTED = EventFactory.createArrayBacked(Detected.class,
        listeners -> hazard -> {
            for (Detected listener : listeners) {
                listener.onLocalHazardDetected(hazard);
            }
        });

    /** Fires once the most recently detected hazard is no longer active — either the player moved
     *  past it/around it, or left the road/nether entirely. */
    public static final Event<Cleared> CLEARED = EventFactory.createArrayBacked(Cleared.class,
        listeners -> () -> {
            for (Cleared listener : listeners) {
                listener.onLocalHazardCleared();
            }
        });

    @FunctionalInterface
    public interface Detected {
        void onLocalHazardDetected(LocalHazard hazard);
    }

    @FunctionalInterface
    public interface Cleared {
        void onLocalHazardCleared();
    }
}
