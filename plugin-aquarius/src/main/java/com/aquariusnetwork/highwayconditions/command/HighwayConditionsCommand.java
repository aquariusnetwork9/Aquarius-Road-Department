package com.aquariusnetwork.highwayconditions.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.aquarius.command.api.Command;
import com.aquarius.command.api.CommandCategory;
import com.aquarius.command.api.CommandContext;
import com.aquarius.command.api.CommandUsage;
import com.aquarius.discord.Embed;

import com.aquariusnetwork.highwayconditions.module.HighwayReporterModule;

import static com.aquarius.Globals.MODULE;
import static com.aquarius.command.brigadier.ToggleArgumentType.getToggle;
import static com.aquarius.command.brigadier.ToggleArgumentType.toggle;
import static com.aquariusnetwork.highwayconditions.HighwayConditionsPlugin.PLUGIN_CONFIG;

public class HighwayConditionsCommand extends Command {

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("highwayConditions")
            .category(CommandCategory.MODULE)
            .description("""
                Report nether-highway conditions to the network.
                Only on-highway 1-D coordinates are ever sent; off-highway positions are never reported.
                """)
            .usageLines(
                "on/off",
                "presence on/off"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("highwayConditions")
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.reporter.enabled = getToggle(c, "toggle");
                MODULE.get(HighwayReporterModule.class).syncEnabledFromConfig();
                c.getSource().getEmbed()
                    .title("Highway Conditions " + toggleStrCaps(PLUGIN_CONFIG.reporter.enabled));
            }))
            .then(literal("presence").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.reporter.reportPresence = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Presence reporting " + toggleStrCaps(PLUGIN_CONFIG.reporter.reportPresence));
            })));
    }

    @Override
    public void defaultEmbed(Embed embed) {
        embed
            .primaryColor()
            .addField("Enabled", toggleStr(PLUGIN_CONFIG.reporter.enabled))
            .addField("Ingest", PLUGIN_CONFIG.reporter.ingestUrl)
            .addField("Server", PLUGIN_CONFIG.reporter.server)
            .addField("Presence", toggleStr(PLUGIN_CONFIG.reporter.reportPresence));
    }
}
