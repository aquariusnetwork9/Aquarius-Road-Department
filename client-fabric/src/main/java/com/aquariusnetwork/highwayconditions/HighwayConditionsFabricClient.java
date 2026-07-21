package com.aquariusnetwork.highwayconditions;

import com.aquariusnetwork.highwayconditions.command.HighwayConditionsCommand;
import com.aquariusnetwork.highwayconditions.hud.HazardHudElement;
import com.aquariusnetwork.highwayconditions.module.HighwayReporterModule;
import com.aquariusnetwork.highwayconditions.net.GeoCache;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.util.Identifier;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ARD ("Aquarius Road Department") -- crowdsourced nether-highway conditions. Standalone Fabric
 * mod: loads for any player regardless of which (if any) utility client they also run, only
 * stable Fabric/Fabric API hooks, no coupling to Meteor/RusherHack/LambdaClient internals.
 *
 * <p>Never transmits a raw coordinate -- see PROTOCOL.md and {@code net.Geo}/{@code net.Report}.
 */
public final class HighwayConditionsFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        HighwayConditionsConfig cfg = HighwayConditionsConfig.load();
        GeoCache geoCache = new GeoCache();
        ExecutorService executor = Executors.newFixedThreadPool(2, daemonThreadFactory());

        HighwayReporterModule reporter = new HighwayReporterModule(cfg, geoCache, executor);
        HazardHudElement hud = new HazardHudElement(cfg, geoCache, executor, reporter::currentClient);
        HighwayConditionsCommand command = new HighwayConditionsCommand(cfg, reporter, executor);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Shared geometry fetch: neither the reporter nor the HUD owns this independently,
            // so they can never race two /geometry fetches or diverge on which Geo they're using.
            geoCache.poll(cfg.reporter.server, reporter.currentClient(), executor, g -> {}, ex -> {});
            reporter.tick(client);
            hud.tick(client);
        });

        // HudElementRegistry replaced HudRenderCallback as of MC 1.21.6 -- attached just before
        // vanilla chat so it inherits chat's own render-visibility condition (e.g. hidden HUD).
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
            Identifier.of("ard", "hazard_ahead"), hud::render);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            command.register(dispatcher));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> executor.shutdown());
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "ard-network-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
