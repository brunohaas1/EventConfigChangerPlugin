package com.eventchanger.stats;

import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.Option;

@Configuration("collection_stats")
public class CollectionStatsConfig {

    @Option("collection_stats.show_stats_panel")
    public boolean showStatsPanel = true;

    @Option("collection_stats.auto_reset_on_map_change")
    public boolean autoResetOnMapChange = false;

    @Option("collection_stats.log_collections")
    public boolean logCollections = false;
}
