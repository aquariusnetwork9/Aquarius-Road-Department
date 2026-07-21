package com.aquariusnetwork.highwayconditions;

import com.aquarius.plugin.api.AquariusProxyPlugin;
import com.aquarius.plugin.api.Plugin;
import com.aquarius.plugin.api.PluginAPI;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import com.aquariusnetwork.highwayconditions.command.HighwayConditionsCommand;
import com.aquariusnetwork.highwayconditions.module.HighwayReporterModule;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Crowdsourced nether-highway conditions reporter — privacy-preserving, "
        + "on-highway 1-D coordinates only (off-highway coords are unrepresentable).",
    url = "https://github.com/aquariusnetwork9/highway-conditions",
    authors = {"aquariusnetwork9"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class HighwayConditionsPlugin implements AquariusProxyPlugin {

    public static HighwayConditionsConfig PLUGIN_CONFIG;
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        PLUGIN_CONFIG = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, HighwayConditionsConfig.class);
        pluginAPI.registerModule(new HighwayReporterModule());
        pluginAPI.registerCommand(new HighwayConditionsCommand());
        LOG.info("HighwayConditions plugin loaded");
    }
}
