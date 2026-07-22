package com.eventchanger.stats;

import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.events.EventHandler;
import eu.darkbot.api.extensions.Behavior;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Feature;
import eu.darkbot.api.extensions.InstructionProvider;
import eu.darkbot.api.extensions.Module;
import eu.darkbot.api.managers.EventBrokerAPI;
import eu.darkbot.api.managers.GameLogAPI;
import eu.darkbot.api.managers.StarSystemAPI;
import eu.darkbot.api.managers.StatsAPI;

import eu.darkbot.api.events.Listener;
import javax.swing.JComponent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Feature(name = "Estatísticas de Coleta", description = "Monitora e exibe estatísticas em tempo real da coleta de caixas, recursos, minérios e ganhos de sessão")
public class CollectionStatsModule implements Module, Behavior, Configurable<CollectionStatsConfig>, InstructionProvider, Listener {

    private static final Pattern DIGITS_PATTERN = Pattern.compile("\\d+");

    private final StatsAPI statsApi;
    private final EventBrokerAPI eventBrokerApi;

    private CollectionStatsConfig config;
    private CollectionStatsPanel statsPanel;

    private long sessionStartTimeMs;
    private double initialUridium;
    private double initialCredits;
    private double initialExperience;
    private double initialHonor;

    private long bonusBoxesCount = 0;
    private long cargoBoxesCount = 0;
    private long bootyKeysCount = 0;
    private long palladiumCount = 0;
    private long otherOresCount = 0;

    public CollectionStatsModule(PluginAPI api) {
        this.statsApi = api.requireAPI(StatsAPI.class);
        this.eventBrokerApi = api.requireAPI(EventBrokerAPI.class);

        if (this.eventBrokerApi != null) {
            this.eventBrokerApi.registerListener(this);
        }
        resetStats();
    }

    public synchronized void resetStats() {
        this.sessionStartTimeMs = System.currentTimeMillis();
        if (statsApi != null) {
            this.initialUridium = statsApi.getTotalUridium();
            this.initialCredits = statsApi.getTotalCredits();
            this.initialExperience = statsApi.getTotalExperience();
            this.initialHonor = statsApi.getTotalHonor();
        }
        this.bonusBoxesCount = 0;
        this.cargoBoxesCount = 0;
        this.bootyKeysCount = 0;
        this.palladiumCount = 0;
        this.otherOresCount = 0;
    }

    @Override
    public void setConfig(ConfigSetting<CollectionStatsConfig> arg0) {
        this.config = arg0.getValue();
    }

    @Override
    public JComponent beforeConfig() {
        if (statsPanel == null) {
            statsPanel = new CollectionStatsPanel(e -> resetStats());
        }
        return statsPanel.getRootPanel();
    }

    @Override
    public void onTickBehavior() {
        updateStats();
    }

    @Override
    public void onTickModule() {
        updateStats();
    }

    private void updateStats() {
        long elapsedMs = Math.max(1, System.currentTimeMillis() - sessionStartTimeMs);
        double hours = elapsedMs / 3600000.0;

        double currentUridium = statsApi != null ? statsApi.getTotalUridium() : 0;
        double currentCredits = statsApi != null ? statsApi.getTotalCredits() : 0;
        double currentXP = statsApi != null ? statsApi.getTotalExperience() : 0;
        double currentHonor = statsApi != null ? statsApi.getTotalHonor() : 0;

        double earnedUridium = Math.max(0, currentUridium - initialUridium);
        double earnedCredits = Math.max(0, currentCredits - initialCredits);
        double earnedXP = Math.max(0, currentXP - initialExperience);
        double earnedHonor = Math.max(0, currentHonor - initialHonor);

        double uridiumRate = hours > 0 ? earnedUridium / hours : 0;
        double creditsRate = hours > 0 ? earnedCredits / hours : 0;
        double bonusBoxRate = hours > 0 ? bonusBoxesCount / hours : 0;
        double palladiumRate = hours > 0 ? palladiumCount / hours : 0;

        int cargoCurrent = statsApi != null ? statsApi.getCargo() : 0;
        int cargoMax = statsApi != null ? statsApi.getMaxCargo() : 0;

        if (statsPanel != null) {
            statsPanel.updateData(
                    elapsedMs,
                    earnedUridium, uridiumRate,
                    earnedCredits, creditsRate,
                    earnedXP, earnedHonor,
                    bonusBoxesCount, bonusBoxRate,
                    cargoBoxesCount, bootyKeysCount,
                    palladiumCount, palladiumRate,
                    otherOresCount, cargoCurrent, cargoMax
            );
        }
    }

    @EventHandler
    public void onLogMessage(GameLogAPI.LogMessageEvent event) {
        if (event == null || event.getMessage() == null) return;
        String msg = event.getMessage().toLowerCase();

        // 1. Detect Box types
        if (msg.contains("bonus box") || msg.contains("caixa bônus") || msg.contains("caixa bonus") || msg.contains("box_bonus")) {
            bonusBoxesCount++;
        } else if (msg.contains("cargo box") || msg.contains("caixa de carga") || msg.contains("box_cargo")) {
            cargoBoxesCount++;
        } else if (msg.contains("booty key") || msg.contains("pirate boot") || msg.contains("chave de espólio") || msg.contains("chave de espolio") || msg.contains("booty")) {
            bootyKeysCount++;
        } else if (msg.contains("coletou") || msg.contains("recolheu") || msg.contains("recebeu") || msg.contains("collected") || msg.contains("received")) {
            if (msg.contains("box") || msg.contains("caixa")) {
                bonusBoxesCount++;
            }
        }

        // 2. Detect Ores / Palladium
        if (msg.contains("palladium")) {
            long qty = parseQuantity(msg);
            palladiumCount += qty;
        } else if (msg.contains("prometium") || msg.contains("endurium") || msg.contains("terbium")
                || msg.contains("prometid") || msg.contains("duranium") || msg.contains("promerium") || msg.contains("xenomit")) {
            long qty = parseQuantity(msg);
            otherOresCount += qty;
        }
    }

    @EventHandler
    public void onMapChange(StarSystemAPI.MapChangeEvent event) {
        if (config != null && config.autoResetOnMapChange) {
            resetStats();
        }
    }

    private long parseQuantity(String msg) {
        try {
            Matcher m = DIGITS_PATTERN.matcher(msg);
            if (m.find()) {
                long val = Long.parseLong(m.group());
                return val > 0 ? val : 1;
            }
        } catch (Exception ignored) {}
        return 1;
    }
}
