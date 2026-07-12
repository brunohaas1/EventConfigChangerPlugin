package com.eventchanger;

import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.extensions.Behavior;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Feature;
import eu.darkbot.api.extensions.InstructionProvider;
import eu.darkbot.api.managers.ConfigAPI;
import eu.darkbot.api.managers.EscortAPI;
import eu.darkbot.api.managers.FrozenLabyrinthAPI;
import eu.darkbot.api.managers.GauntletPlutusAPI;
import eu.darkbot.api.managers.NpcEventAPI;
import eu.darkbot.api.managers.NpcEventAPI.NpcEvent;
import eu.darkbot.api.managers.NpcEventAPI.EventType;
import eu.darkbot.api.managers.WorldBossOverviewAPI;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

@Feature(name = "Event Config Changer", description = "Troca o perfil do bot automaticamente quando eventos estao ativos")
public class EventConfigChanger implements Behavior, Configurable<EventConfigChangerConfig>, InstructionProvider {

    private final ConfigAPI configApi;
    private final EscortAPI escortApi;
    private final FrozenLabyrinthAPI labyrinthApi;
    private final GauntletPlutusAPI plutusApi;
    private final NpcEventAPI npcEventApi;
    private final WorldBossOverviewAPI worldBossApi;

    private EventConfigChangerConfig config;
    private long lastCheckTime = 0L;
    private String lastActiveEvent = null;
    private String activeOpenProfile = null;
    private String activeClosedProfile = null;
    private String profileBeforeEvent = null;

    private final JPanel statusPanel;
    private final JLabel lblCurrentConfig;
    private final JLabel lblEscort;
    private final JLabel lblLabyrinth;
    private final JLabel lblPlutus;
    private final JLabel lblNpcEvent;
    private final JLabel lblAgatus;
    private final JLabel lblWorldBoss;

    public EventConfigChanger(PluginAPI api) {
        this.configApi    = api.requireAPI(ConfigAPI.class);
        this.escortApi    = api.requireAPI(EscortAPI.class);
        this.labyrinthApi = api.requireAPI(FrozenLabyrinthAPI.class);
        this.plutusApi    = api.requireAPI(GauntletPlutusAPI.class);
        this.npcEventApi  = api.requireAPI(NpcEventAPI.class);
        this.worldBossApi = api.requireAPI(WorldBossOverviewAPI.class);
        ConfigSupplier.init(api);

        statusPanel = new JPanel(new java.awt.GridLayout(0, 2, 4, 3));
        statusPanel.setBorder(new EmptyBorder(6, 6, 6, 6));

        lblCurrentConfig = addRow(statusPanel, "Config atual:");
        lblEscort        = addRow(statusPanel, "Escort:");
        lblLabyrinth     = addRow(statusPanel, "Labirinto:");
        lblPlutus        = addRow(statusPanel, "Vortice (Plutus):");
        lblNpcEvent      = addRow(statusPanel, "Evento NPC:");
        lblAgatus        = addRow(statusPanel, "Agatus:");
        lblWorldBoss     = addRow(statusPanel, "World Boss:");
    }

    private JLabel addRow(JPanel panel, String title) {
        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD));
        panel.add(t);
        JLabel v = new JLabel("--");
        panel.add(v);
        return v;
    }

    @Override
    public void setConfig(ConfigSetting<EventConfigChangerConfig> arg0) {
        this.config = arg0.getValue();
    }

    @Override
    public JComponent beforeConfig() {
        return statusPanel;
    }

    @Override
    public void onTickBehavior() {
        updateStatusPanel();
        long now = System.currentTimeMillis();
        if (now - lastCheckTime < 5000L) return;
        lastCheckTime = now;
        checkEvents();
    }

    private void updateStatusPanel() {
        lblCurrentConfig.setText(configApi.getCurrentProfile());

        double escortTime = escortApi.getTime();
        double escortKeys = escortApi.getKeys();
        if (escortTime > 0 || escortKeys > 0) {
            lblEscort.setForeground(new Color(0, 160, 0));
            lblEscort.setText("Ativo (" + formatSeconds(escortTime) + " | " + (int) escortKeys + " keys)");
        } else {
            lblEscort.setForeground(Color.GRAY);
            lblEscort.setText("Inativo");
        }

        FrozenLabyrinthAPI.Status labStatus = labyrinthApi.getStatus();
        double labTime = labyrinthApi.getRemainingTime();
        switch (labStatus) {
            case OPEN:
                lblLabyrinth.setForeground(new Color(0, 160, 0));
                lblLabyrinth.setText("Aberto (" + formatSeconds(labTime) + " restantes)");
                break;
            case CLOSED:
                lblLabyrinth.setForeground(new Color(200, 100, 0));
                lblLabyrinth.setText("Fechado (" + formatSeconds(labTime) + " para abrir?)");
                break;
            default:
                lblLabyrinth.setForeground(Color.GRAY);
                lblLabyrinth.setText("Encerrado");
        }

        GauntletPlutusAPI.Status plutusStatus = plutusApi.getStatus();
        switch (plutusStatus) {
            case AVAILABLE:
                lblPlutus.setForeground(new Color(0, 160, 0));
                lblPlutus.setText("Disponivel");
                break;
            case INSIDE:
                lblPlutus.setForeground(new Color(0, 120, 200));
                lblPlutus.setText("Dentro do evento");
                break;
            case COMPLETED:
                lblPlutus.setForeground(new Color(200, 100, 0));
                lblPlutus.setText("Concluido");
                break;
            default:
                lblPlutus.setForeground(Color.GRAY);
                lblPlutus.setText("Inativo");
        }

        setNpcEventLabel(lblNpcEvent, npcEventApi.getEvent(EventType.GENERIC));
        setNpcEventLabel(lblAgatus,   npcEventApi.getEvent(EventType.AGATUS));

        WorldBossOverviewAPI.Status wbStatus = worldBossApi.getStatus();
        if (wbStatus == WorldBossOverviewAPI.Status.AVAILABLE) {
            String bossName = worldBossApi.getBossName();
            lblWorldBoss.setForeground(new Color(0, 160, 0));
            lblWorldBoss.setText("Disponivel" + (bossName != null && !bossName.isEmpty() ? " (" + bossName + ")" : ""));
        } else {
            lblWorldBoss.setForeground(Color.GRAY);
            lblWorldBoss.setText("Inativo");
        }
    }

    private void setNpcEventLabel(JLabel label, NpcEvent ev) {
        if (ev == null) { label.setForeground(Color.GRAY); label.setText("Sem dados"); return; }
        double remaining = ev.getRemainingTime();
        String name = ev.getEventName();
        String prefix = (name != null && !name.isEmpty()) ? name + " " : "";
        if (ev.getStatus() == NpcEvent.Status.ACTIVE) {
            label.setForeground(new Color(0, 160, 0));
            label.setText("Ativo " + prefix + formatSeconds(remaining) + " rest.");
        } else {
            if (remaining > 0) {
                label.setForeground(new Color(200, 100, 0));
                label.setText(prefix + formatSeconds(remaining) + " para comecar");
            } else {
                label.setForeground(Color.GRAY);
                label.setText(prefix.isEmpty() ? "Inativo" : prefix + "Inativo");
            }
        }
    }

    private static String formatSeconds(double totalSeconds) {
        if (totalSeconds <= 0) return "--";
        long secs = (long) totalSeconds;
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        if (h > 0) return String.format("%dh %02dm %02ds", h, m, s);
        if (m > 0) return String.format("%dm %02ds", m, s);
        return String.format("%ds", s);
    }

    private void checkEvents() {
        if (config == null) return;
        if (config.escort.activate && isEscortActive())              { applyOpenProfile("Escort",     config.escort);           return; }
        if (config.labyrinth.activate && isLabyrinthActive())        { applyOpenProfile("Labyrinth",  config.labyrinth);        return; }
        if (config.plutus.activate && isPlutusActive())              { applyOpenProfile("Plutus",     config.plutus);           return; }
        if (config.npc_event.activate && isGenericNpcEventActive())  { applyOpenProfile("Npc Event",  config.npc_event);        return; }
        if (config.agatus_event.activate && isAgatusEventActive())   { applyOpenProfile("Agatus",     config.agatus_event);     return; }
        if (config.world_boss_event.activate && isWorldBossActive()) { applyOpenProfile("World Boss", config.world_boss_event); return; }
        revertProfiles();
    }

    private boolean isEscortActive()          { return escortApi.getTime() > 0 || escortApi.getKeys() > 0; }
    private boolean isLabyrinthActive()       { return labyrinthApi.getStatus() == FrozenLabyrinthAPI.Status.OPEN; }
    private boolean isPlutusActive()          { GauntletPlutusAPI.Status s = plutusApi.getStatus(); return s == GauntletPlutusAPI.Status.AVAILABLE || s == GauntletPlutusAPI.Status.INSIDE; }
    private boolean isGenericNpcEventActive() { NpcEvent ev = npcEventApi.getEvent(EventType.GENERIC); return ev != null && ev.getStatus() == NpcEvent.Status.ACTIVE; }
    private boolean isAgatusEventActive()     { NpcEvent ev = npcEventApi.getEvent(EventType.AGATUS);  return ev != null && ev.getStatus() == NpcEvent.Status.ACTIVE; }
    private boolean isWorldBossActive()       { return worldBossApi.getStatus() == WorldBossOverviewAPI.Status.AVAILABLE; }

    private void applyOpenProfile(String eventName, EventConfigChangerConfig.Config eventConfig) {
        String target = eventConfig.BOT_PROFILE_OPEN != null ? eventConfig.BOT_PROFILE_OPEN.trim() : "";
        if (target.isEmpty()) return;

        String currentProfile = configApi.getCurrentProfile();
        if (lastActiveEvent == null || !eventName.equals(lastActiveEvent)) {
            lastActiveEvent = eventName;
            activeOpenProfile = target;
            activeClosedProfile = eventConfig.BOT_PROFILE_CLOSED != null ? eventConfig.BOT_PROFILE_CLOSED.trim() : "";
            profileBeforeEvent = currentProfile;
        }

        if (!target.equalsIgnoreCase(currentProfile)) {
            System.out.println("[EventConfigChanger] Event=" + eventName + " -> " + target);
            configApi.setConfigProfile(target);
        }
    }

    private void revertProfiles() {
        if (lastActiveEvent == null) return;

        String target = activeClosedProfile != null && !activeClosedProfile.isEmpty()
                ? activeClosedProfile
                : profileBeforeEvent;
        if (target != null && !target.isEmpty() && !target.equalsIgnoreCase(configApi.getCurrentProfile())) {
            System.out.println("[EventConfigChanger] Reverting to: " + target);
            configApi.setConfigProfile(target);
        }
        lastActiveEvent = null;
        activeOpenProfile = null;
        activeClosedProfile = null;
        profileBeforeEvent = null;
    }

    private EventConfigChangerConfig.Config getEventConfig(String name) {
        switch (name) {
            case "Escort":     return config.escort;
            case "Labyrinth":  return config.labyrinth;
            case "Plutus":     return config.plutus;
            case "Npc Event":  return config.npc_event;
            case "Agatus":     return config.agatus_event;
            case "World Boss": return config.world_boss_event;
            default:           return null;
        }
    }
}
