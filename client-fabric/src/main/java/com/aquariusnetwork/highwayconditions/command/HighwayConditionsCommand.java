package com.aquariusnetwork.highwayconditions.command;

import com.aquariusnetwork.highwayconditions.HighwayConditionsConfig;
import com.aquariusnetwork.highwayconditions.module.HighwayReporterModule;
import com.aquariusnetwork.highwayconditions.net.IngestClient;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * {@code /ard reporting on|off}, {@code /ard presence on|off}, {@code /ard status},
 * {@code /ard link}, {@code /ard token <value>} -- a client-only Brigadier command tree
 * (registered via {@code ClientCommandRegistrationCallback} in the entrypoint), the closest
 * Fabric equivalent to the AquariusProxy plugin's own {@code highwayConditions} command.
 */
public final class HighwayConditionsCommand {

    private static final String WEBSITE_BASE = "https://map.aquariusconnect.org";

    private final HighwayConditionsConfig cfg;
    private final HighwayReporterModule reporter;
    private final ExecutorService executor;

    public HighwayConditionsCommand(HighwayConditionsConfig cfg, HighwayReporterModule reporter,
                                    ExecutorService executor) {
        this.cfg = cfg;
        this.reporter = reporter;
        this.executor = executor;
    }

    public void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("ard")
            .then(literal("reporting")
                .then(argument("toggle", bool()).executes(this::setReporting)))
            .then(literal("presence")
                .then(argument("toggle", bool()).executes(this::setPresence)))
            .then(literal("status").executes(this::status))
            .then(literal("link").executes(this::link))
            .then(literal("token")
                .then(argument("value", greedyString()).executes(this::setToken))));
    }

    private int setReporting(CommandContext<FabricClientCommandSource> ctx) {
        boolean on = BoolArgumentType.getBool(ctx, "toggle");
        cfg.reporter.enabled = on;
        cfg.save();
        if (!on) {
            reporter.flushNow();  // don't lose a queued batch just from toggling off
        }
        ctx.getSource().sendFeedback(Text.literal("Highway Conditions reporting " + onOff(on)));
        return 1;
    }

    private int setPresence(CommandContext<FabricClientCommandSource> ctx) {
        boolean on = BoolArgumentType.getBool(ctx, "toggle");
        cfg.reporter.reportPresence = on;
        cfg.save();
        ctx.getSource().sendFeedback(Text.literal("Presence reporting " + onOff(on)));
        return 1;
    }

    private int status(CommandContext<FabricClientCommandSource> ctx) {
        HighwayConditionsConfig.Reporter r = cfg.reporter;
        FabricClientCommandSource src = ctx.getSource();
        src.sendFeedback(Text.literal("Highway Conditions"));
        src.sendFeedback(Text.literal("  Reporting: " + onOff(r.enabled)));
        src.sendFeedback(Text.literal("  Presence:  " + onOff(r.reportPresence)));
        src.sendFeedback(Text.literal("  Hazard HUD:" + onOff(cfg.hud.enabled)));
        src.sendFeedback(Text.literal("  Server:    " + r.server));
        src.sendFeedback(Text.literal("  Ingest:    " + r.ingestUrl));
        src.sendFeedback(Text.literal("  Token set: " + (r.token != null && !r.token.isBlank())));
        return 1;
    }

    /** Requests a device-code-style link code (PROTOCOL.md SS6.2), proves ownership of this
     *  Minecraft account to Mojang directly (SS6.2 step 1.5 -- this client's OWN
     *  {@code session/minecraft/join} call using its live session's access token and the
     *  server-issued {@code verifyServerId} nonce; ARD itself never sees the access token),
     *  then posts the link code to chat with a clickable link that pre-fills it on the website
     *  -- confirmed against link.js's own {@code ?linkCode=} query-param handling, a genuine
     *  one-click flow rather than a bare URL the player has to retype an 8-character code into.
     *  There's no server-side callback that hands the finished token back to this client, so the
     *  loop closes with {@code /ard token} once the player copies it from the website.
     *
     *  <p>If the ownership-proof step itself fails (Mojang rejects the join, or this account is
     *  offline/cracked and has no real session to prove), the link code is still handed to the
     *  player -- {@code /link/complete} is the one that ultimately enforces the gate (and only
     *  when the deployment has it enabled), not this client. */
    private int link(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        MinecraftClient mc = MinecraftClient.getInstance();
        UUID uid = mc.getSession() == null ? null : mc.getSession().getUuidOrNull();
        if (uid == null) {
            src.sendError(Text.literal("Could not determine your Minecraft account UUID."));
            return 0;
        }
        IngestClient client = reporter.currentClient();
        if (client == null) {
            src.sendError(Text.literal("Not connected to the ingest service yet."));
            return 0;
        }
        String server = cfg.reporter.server;
        src.sendFeedback(Text.literal("Requesting a link code..."));
        executor.execute(() -> {
            try {
                IngestClient.LinkInit init = client.initLink(server, uid);
                String code = init.code;
                if (init.verifyServerId != null && !init.verifyServerId.isBlank()) {
                    proveOwnership(mc, uid, init.verifyServerId, client, code, src);
                }
                String url = WEBSITE_BASE + "/link.html?linkCode=" + code;
                mc.execute(() -> {
                    Text clickable = Text.literal("[click to finish linking]")
                        .styled(style -> style
                            .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                            .withFormatting(Formatting.UNDERLINE, Formatting.AQUA));
                    src.sendFeedback(Text.literal("Your link code: " + code + "  ").append(clickable));
                    src.sendFeedback(Text.literal("After you finish on the website, copy your token "
                        + "and run /ard token <value>."));
                });
            } catch (Exception ex) {
                mc.execute(() -> src.sendError(Text.literal(
                    "Failed to request a link code: " + ex.getMessage())));
            }
        });
        return 1;
    }

    /** The ownership-proof handshake itself: a real {@code session/minecraft/join} call this
     *  client makes directly to Mojang (via the game's own session service, reached through
     *  {@code MinecraftClient.getApiServices().sessionService()} -- the identical call vanilla
     *  makes joining any online-mode server) using {@code verifyServerId} as the "server ID",
     *  then tells ARD to confirm it via {@code /link/verify-ownership}. Deliberately best-effort:
     *  a failure here (Mojang rejects the join, this is an offline/cracked account with no real
     *  session, or ARD's confirmation call itself fails) is reported to chat but does not abort
     *  the link flow -- see {@link #link}'s own note on why. */
    private void proveOwnership(MinecraftClient mc, UUID uid, String verifyServerId,
                                IngestClient client, String code, FabricClientCommandSource src) {
        try {
            mc.getApiServices().sessionService()
                .joinServer(uid, mc.getSession().getAccessToken(), verifyServerId);
            client.verifyOwnership(code);
        } catch (Exception ex) {
            mc.execute(() -> src.sendError(Text.literal(
                "Could not prove account ownership to Mojang (" + ex.getMessage() + ") -- "
                + "linking may fail on the website if this server requires it.")));
        }
    }

    private int setToken(CommandContext<FabricClientCommandSource> ctx) {
        // .trim(): a token pasted from a browser selection commonly picks up a stray leading/
        // trailing space, which would otherwise silently break auth in a way that's very hard
        // to diagnose from a chat message alone.
        String value = StringArgumentType.getString(ctx, "value").trim();
        cfg.reporter.token = value;
        cfg.save();
        ctx.getSource().sendFeedback(Text.literal("Highway Conditions token updated."));
        return 1;
    }

    private static String onOff(boolean b) {
        return b ? "ON" : "OFF";
    }
}
