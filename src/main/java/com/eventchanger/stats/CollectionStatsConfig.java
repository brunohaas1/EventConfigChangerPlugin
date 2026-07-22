package com.eventchanger.stats;

import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.Option;

@Configuration(value = "collectionStats.config")
public class CollectionStatsConfig {

    @Option("Mostrar Painel de Estatísticas")
    public boolean showStatsPanel = true;

    @Option("Reset Automático na Troca de Mapa")
    public boolean autoResetOnMapChange = false;

    @Option("Notificar no Log ao Coletar Caixas")
    public boolean logCollections = false;
}
