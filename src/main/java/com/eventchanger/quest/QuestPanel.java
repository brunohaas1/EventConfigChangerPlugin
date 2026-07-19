package com.eventchanger.quest;

import eu.darkbot.api.config.types.NpcInfo;
import eu.darkbot.api.game.entities.Npc;
import eu.darkbot.api.managers.QuestAPI;
import eu.darkbot.api.managers.QuestAPI.Quest;
import eu.darkbot.api.managers.QuestAPI.Requirement;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Constrói e atualiza o painel de status do QuestModule exibido na UI do DarkBot.
 */
public class QuestPanel {

    private final QuestContext ctx;
    private final QuestLogger logger;

    private final JPanel rootPanel;
    private final JLabel lblQuestTitle;
    private final JLabel lblQuestStatus;
    private final JPanel requirementsPanel;
    private final JLabel lblCurrentAction;
    private final JLabel lblRewards;

    public QuestPanel(QuestContext ctx, QuestLogger logger) {
        this.ctx = ctx;
        this.logger = logger;

        rootPanel = new JPanel(new BorderLayout(4, 4));
        rootPanel.setBorder(new EmptyBorder(4, 4, 4, 4));

        JPanel headerPanel = new JPanel(new GridLayout(3, 1, 2, 2));
        headerPanel.setOpaque(false);
        lblQuestTitle    = makeLabel("Nenhuma quest ativa", Font.BOLD, 12);
        lblQuestTitle.setForeground(Color.WHITE);
        lblQuestStatus   = makeLabel("--", Font.PLAIN, 11);
        lblQuestStatus.setForeground(new Color(200, 200, 200));
        lblCurrentAction = makeLabel("--", Font.ITALIC, 11);
        lblCurrentAction.setForeground(new Color(100, 180, 255));
        headerPanel.add(lblQuestTitle);
        headerPanel.add(lblQuestStatus);
        headerPanel.add(lblCurrentAction);
        rootPanel.add(headerPanel, BorderLayout.NORTH);

        requirementsPanel = new JPanel();
        requirementsPanel.setLayout(new BoxLayout(requirementsPanel, BoxLayout.Y_AXIS));
        requirementsPanel.setOpaque(false);
        requirementsPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Objetivos", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION,
                null, Color.WHITE));
        JScrollPane scroll = new JScrollPane(requirementsPanel);
        scroll.setPreferredSize(new Dimension(300, 120));
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        rootPanel.add(scroll, BorderLayout.CENTER);

        lblRewards = makeLabel("", Font.PLAIN, 10);
        lblRewards.setForeground(new Color(255, 190, 60));
        lblRewards.setBorder(new EmptyBorder(2, 0, 0, 0));
        rootPanel.add(lblRewards, BorderLayout.SOUTH);
    }

    public JPanel getRootPanel() {
        return rootPanel;
    }

    private JLabel makeLabel(String text, int style, int size) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(style, (float) size));
        return l;
    }

    private static class ReqData {
        final boolean completed;
        final Requirement.RequirementType type;
        final String description;
        final double progressPercentage;
        final double progress;
        final double goal;

        ReqData(boolean completed, Requirement.RequirementType type, String description,
                double progressPercentage, double progress, double goal) {
            this.completed = completed;
            this.type = type;
            this.description = description;
            this.progressPercentage = progressPercentage;
            this.progress = progress;
            this.goal = goal;
        }
    }

    public void updatePanel() {
        Quest quest = ctx.questAPI.getDisplayedQuest();
        
        final boolean active = quest != null && quest.isActive();
        final String title = active ? quest.getTitle() : "Nenhuma quest ativa";
        final boolean completed = quest != null && quest.isCompleted();
        
        final String statusText;
        final Color statusColor;
        if (!active) {
            statusText = "--";
            statusColor = new Color(200, 200, 200);
        } else if (completed) {
            statusText = "COMPLETA!";
            statusColor = new Color(100, 220, 100);
        } else {
            String mapInfo = (ctx.targetMap != null) ? " | Navegando -> " + ctx.targetMap.getName() : "";
            statusText = "Em andamento..." + mapInfo;
            statusColor = new Color(200, 200, 200);
        }
        
        final String currentAction = ctx.currentAction;
        
        final java.util.List<ReqData> reqsData = new java.util.ArrayList<>();
        if (active && quest.getRequirements() != null) {
            for (Requirement req : quest.getRequirements()) {
                if (!req.isEnabled()) continue;
                reqsData.add(new ReqData(
                    req.isCompleted(),
                    req.getRequirementType(),
                    req.getDescription(),
                    req.getProgressPercentage(),
                    req.getProgress(),
                    req.getGoal()
                ));
            }
        }
        
        final String rewardsText;
        if (active && quest.getRewards() != null && !quest.getRewards().isEmpty()) {
            StringBuilder sb = new StringBuilder("Recomp.: ");
            for (QuestAPI.Reward r : quest.getRewards()) {
                sb.append(r.getAmount()).append("x ").append(r.getType()).append("  ");
            }
            rewardsText = sb.toString().trim();
        } else {
            rewardsText = "";
        }

        SwingUtilities.invokeLater(() -> {
            lblQuestTitle.setText(title);
            lblQuestStatus.setText(statusText);
            lblQuestStatus.setForeground(statusColor);
            lblCurrentAction.setText(currentAction);
            lblRewards.setText(rewardsText);
            
            requirementsPanel.removeAll();
            for (ReqData rd : reqsData) {
                JPanel row = new JPanel(new BorderLayout(4, 0));
                row.setOpaque(false);
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
                row.setMinimumSize(new Dimension(280, 20));

                String typePrefix = getRequirementTypePrefix(rd.type);
                String descText = (rd.completed ? "[OK] " : "") + typePrefix + " " + rd.description;
                JLabel desc = makeLabel(descText.trim(), rd.completed ? Font.BOLD : Font.PLAIN, 10);
                desc.setForeground(rd.completed ? new Color(100, 220, 100) : Color.WHITE);
                desc.setMinimumSize(new Dimension(160, 16));

                JProgressBar bar = new JProgressBar(0, 100);
                bar.setValue((int) Math.min(100, rd.progressPercentage));
                bar.setStringPainted(true);
                bar.setString(formatProgress(rd.progress, rd.goal));
                bar.setForeground(rd.completed ? new Color(100, 220, 100) : new Color(60, 120, 200));
                bar.setPreferredSize(new Dimension(90, 16));
                bar.setMinimumSize(new Dimension(90, 16));

                row.add(desc, BorderLayout.CENTER);
                row.add(bar, BorderLayout.EAST);
                requirementsPanel.add(row);
            }
            
            requirementsPanel.revalidate();
            requirementsPanel.repaint();
        });
    }

    private void refreshMarkedNpcCounts() {
        long now = System.currentTimeMillis();
        if (now - ctx.lastNpcCountRefreshTime < 1000) {
            return;
        }
        ctx.lastNpcCountRefreshTime = now;

        Map<String, NpcInfo> npcInfos = ctx.configMarker.getNpcInfos();
        ctx.cachedMarkedNpcCounts.clear();
        if (npcInfos == null || npcInfos.isEmpty()) return;

        Map<String, String> normalizedToDisplay = new LinkedHashMap<>();
        for (Map.Entry<String, NpcInfo> entry : npcInfos.entrySet()) {
            NpcInfo info = entry.getValue();
            if (info == null || !info.getShouldKill()) continue;
            String displayName = ctx.npcBoxMatcher.getNpcName(entry);
            if (displayName == null || displayName.isEmpty()) continue;
            String normalized = MissionMapLoader.normalize(displayName);
            if (normalized.isEmpty()) continue;
            normalizedToDisplay.putIfAbsent(normalized, displayName);
            ctx.cachedMarkedNpcCounts.putIfAbsent(displayName, 0);
        }
        if (normalizedToDisplay.isEmpty()) return;

        Collection<? extends Npc> liveNpcs = ctx.entitiesAPI.getNpcs();
        if (liveNpcs == null || liveNpcs.isEmpty()) return;

        for (Npc npc : liveNpcs) {
            if (npc == null || npc.getEntityInfo() == null) continue;
            String liveName = npc.getEntityInfo().getUsername();
            if (liveName == null || liveName.isEmpty()) continue;
            String normalizedLiveName = MissionMapLoader.normalize(liveName);
            if (normalizedLiveName.isEmpty()) continue;

            for (Map.Entry<String, String> target : normalizedToDisplay.entrySet()) {
                String normalizedTarget = target.getKey();
                if (normalizedLiveName.equals(normalizedTarget)
                        || normalizedLiveName.contains(normalizedTarget)
                        || normalizedTarget.contains(normalizedLiveName)) {
                    String display = target.getValue();
                    ctx.cachedMarkedNpcCounts.put(display, ctx.cachedMarkedNpcCounts.getOrDefault(display, 0) + 1);
                    break;
                }
            }
        }
    }

    private String getRequirementTypePrefix(Requirement.RequirementType type) {
        if (type == null) return "";
        switch (type) {
            case KILL_NPC:
            case KILL_NPCS:
                return "[Matar NPC]";
            case DAMAGE_NPCS:
                return "[Danificar NPC]";
            case KILL_PLAYERS:
                return "[Matar Jogador]";
            case DAMAGE_PLAYERS:
            case DAMAGE_ENEMY_PLAYERS:
                return "[Danificar Jogador]";
            case KILL_ANY:
                return "[Matar]";
            case COLLECT_LOOT:
            case COLLECT:
                return "[Coletar]";
            case COLLECT_BONUS_BOX:
            case COLLECT_BONUS_BOX_TYPE:
                return "[Caixa Bonus]";
            case CARGO:
                return "[Cargo]";
            case SELL_ORE:
                return "[Vender]";
            case SPEND_AMMUNITION:
            case AMMUNITION:
                return "[Gastar Municao]";
            case DAMAGE:
                return "[Danificar]";
            case TRAVEL:
            case VISIT_MAP:
            case VISIT_MULTIPLE_MAPS:
                return "[Visitar]";
            case TIMER:
            case COUNTDOWN:
            case REAL_TIME_HASTE:
            case REAL_TIME_DATE_HASTE:
                return "[Tempo]";
            default:
                return "";
        }
    }

    private String formatProgress(double progress, double goal) {
        if (goal <= 0) return String.valueOf((long) progress);
        if (goal < 10) return String.format("%.1f / %.1f", progress, goal);
        return String.format("%d / %d", (long) progress, (long) goal);
    }
}
