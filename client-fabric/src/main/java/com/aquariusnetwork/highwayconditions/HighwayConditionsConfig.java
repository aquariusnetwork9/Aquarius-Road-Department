package com.aquariusnetwork.highwayconditions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Mod configuration, persisted as JSON at {@code <configDir>/ard.json}.
 *
 * <p>Reporting is OFF by default -- like the AquariusProxy plugin's equivalent config, a
 * contributor must opt in. Even when on, this mod can only ever emit an on-highway 1-D
 * coordinate -- see PROTOCOL.md. The hazard-ahead HUD is ON by default and independent of
 * {@code reporter.enabled}: it's a pure read with zero privacy cost, and the whole reason this
 * mod exists (report submission alone would just be "the proxy plugin, again, in Fabric").
 */
public class HighwayConditionsConfig {

    private static final String FILE_NAME = "ard.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public final Reporter reporter = new Reporter();
    public final Hud hud = new Hud();

    public static class Reporter {
        /** Master switch. Default OFF (opt-in). */
        public boolean enabled = false;

        /** Ingest service base URL. Defaults to the live public deployment. */
        public String ingestUrl = "https://map.aquariusconnect.org";

        /** Bearer token (Tier A/B). Blank by default -- a normal player reports as Tier C
         *  (anonymous) until they link an account via /ard link + /ard token. Kept out of logs. */
        public String token = "";

        /** Server id this client is playing on; must match a server the ingest knows. */
        public String server = "2b2t.org";

        /** Per-contributor privacy cap: never report when max(|x|,|z|) exceeds this. */
        public int maxReportRadius = 500_000;

        /** Camper/player-presence reporting. Opt-in, default OFF (count only, no identity). */
        public boolean reportPresence = false;

        /** Also report CLEAR (road-traversed) samples, not just hazards. */
        public boolean reportClear = true;

        /** How often to flush batched reports, in ticks (~20/s). Floored at 20 (1s) regardless
         *  of what's configured -- protects the ingest service from a fat-fingered value turning
         *  this into a per-tick flood. */
        public int flushIntervalTicks = 100;

        /** Don't resend the same (road,seg,along,cond) within this many seconds. Floored at 10s
         *  regardless of what's configured, same reasoning as flushIntervalTicks. */
        public int resendSeconds = 120;

        /** Radius (blocks) to count nearby players for PRESENCE. */
        public int presenceRadius = 24;
    }

    public static class Hud {
        /** Master switch for the hazard-ahead overlay. Default ON -- pure read, no privacy cost,
         *  independent of reporter.enabled. */
        public boolean enabled = true;

        /** How often (seconds) to poll GET /conditions/&lt;server&gt;. Floored at 2s -- the
         *  server's own read-rate budget is 120 req/min/IP shared across every read route, so
         *  even the floor is comfortably inside it. */
        public int pollSeconds = 5;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("ard");

    /** Loads from the Fabric config dir, tolerating a missing or corrupt file by falling back to
     *  defaults and immediately re-writing -- never crashes mod init over a bad config file.
     *  A corrupt/unparseable file is backed up to {@code ard.json.bad} rather than silently
     *  overwritten -- it may hold a token or other setting worth recovering by hand, and a
     *  silent reset would otherwise look like the mod "forgot" it. */
    public static HighwayConditionsConfig load() {
        Path path = configPath();
        if (Files.exists(path)) {
            boolean broken = false;
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                HighwayConditionsConfig cfg = GSON.fromJson(json, HighwayConditionsConfig.class);
                if (cfg != null) {
                    return cfg;
                }
                LOGGER.warn("{} parsed to nothing (empty/null JSON) -- resetting to defaults", FILE_NAME);
                broken = true;
            } catch (Exception ex) {
                LOGGER.warn("Failed to parse {} -- resetting to defaults", FILE_NAME, ex);
                broken = true;
            }
            if (broken) {
                backupBrokenFile(path);
            }
        }
        HighwayConditionsConfig cfg = new HighwayConditionsConfig();
        cfg.save();
        return cfg;
    }

    private static void backupBrokenFile(Path path) {
        Path backup = path.resolveSibling(FILE_NAME + ".bad");
        try {
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.warn("Backed up the broken config to {}", backup);
        } catch (IOException ex) {
            LOGGER.warn("Could not back up the broken config to {}", backup, ex);
        }
    }

    /** Writes to a sibling temp file then atomically moves it into place, so a crash mid-write
     *  can't leave a truncated file that fails to load next launch. */
    public void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new RuntimeException("failed to save " + FILE_NAME, ex);
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
