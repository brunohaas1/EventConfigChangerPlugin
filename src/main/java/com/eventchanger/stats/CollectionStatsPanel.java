package com.eventchanger.stats;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.text.DecimalFormat;
import java.text.NumberFormat;

public class CollectionStatsPanel {

    private static final NumberFormat FMT = NumberFormat.getInstance();

    private final JPanel rootPanel;

    private final JLabel lblSessionTime;

    private final JLabel lblUridium;
    private final JLabel lblUridiumRate;

    private final JLabel lblCredits;
    private final JLabel lblCreditsRate;

    private final JLabel lblExperience;
    private final JLabel lblHonor;

    private final JLabel lblBonusBoxes;
    private final JLabel lblBonusBoxRate;

    private final JLabel lblCargoBoxes;
    private final JLabel lblBootyKeys;

    private final JLabel lblPalladium;
    private final JLabel lblPalladiumRate;

    private final JLabel lblOresTotal;
    private final JLabel lblCargoSpace;

    private final JButton btnReset;

    public CollectionStatsPanel(ActionListener resetListener) {
        rootPanel = new JPanel(new BorderLayout(6, 6));
        rootPanel.setBorder(new EmptyBorder(8, 8, 8, 8));

        // Header Panel: Session Time + Reset Button
        JPanel headerPanel = new JPanel(new BorderLayout(8, 4));
        headerPanel.setOpaque(false);

        JLabel lblTitle = new JLabel("Estatísticas de Coleta");
        lblTitle.setFont(lblTitle.getFont().deriveFont(Font.BOLD, 14f));
        lblTitle.setForeground(new Color(220, 220, 220));

        lblSessionTime = new JLabel("Sessão: 00h 00m 00s");
        lblSessionTime.setFont(lblSessionTime.getFont().deriveFont(Font.PLAIN, 11f));
        lblSessionTime.setForeground(new Color(170, 170, 170));

        JPanel headerLeft = new JPanel(new GridLayout(2, 1, 2, 2));
        headerLeft.setOpaque(false);
        headerLeft.add(lblTitle);
        headerLeft.add(lblSessionTime);

        btnReset = new JButton("Resetar Estatísticas");
        btnReset.setFont(btnReset.getFont().deriveFont(Font.BOLD, 11f));
        btnReset.setFocusable(false);
        if (resetListener != null) {
            btnReset.addActionListener(resetListener);
        }

        headerPanel.add(headerLeft, BorderLayout.WEST);
        headerPanel.add(btnReset, BorderLayout.EAST);

        rootPanel.add(headerPanel, BorderLayout.NORTH);

        // Center Grid: 3 Sections (Moedas/Ganhos, Caixas, Minérios)
        JPanel gridPanel = new JPanel(new GridLayout(3, 1, 6, 6));
        gridPanel.setOpaque(false);

        // Section 1: Moedas & Ganhos
        JPanel sectionGains = createSectionPanel("Moedas & Progresso");
        sectionGains.setLayout(new GridLayout(2, 2, 8, 4));

        lblUridium = createMetricLabel("0", new Color(0, 230, 120));
        lblUridiumRate = createSubLabel("0/h");

        lblCredits = createMetricLabel("0", new Color(255, 205, 50));
        lblCreditsRate = createSubLabel("0/h");

        lblExperience = createMetricLabel("0", new Color(80, 180, 255));
        lblHonor = createMetricLabel("0", new Color(200, 130, 255));

        sectionGains.add(createTile("Uridium Obter", lblUridium, lblUridiumRate));
        sectionGains.add(createTile("Créditos Obter", lblCredits, lblCreditsRate));
        sectionGains.add(createTile("Experiência (XP)", lblExperience, createSubLabel("Ganho total")));
        sectionGains.add(createTile("Honra (Honor)", lblHonor, createSubLabel("Ganho total")));

        gridPanel.add(sectionGains);

        // Section 2: Coleta de Caixas
        JPanel sectionBoxes = createSectionPanel("Caixas & Espólios");
        sectionBoxes.setLayout(new GridLayout(2, 2, 8, 4));

        lblBonusBoxes = createMetricLabel("0", new Color(255, 230, 100));
        lblBonusBoxRate = createSubLabel("0/h");

        lblCargoBoxes = createMetricLabel("0", new Color(220, 160, 80));
        lblBootyKeys = createMetricLabel("0", new Color(130, 220, 255));

        sectionBoxes.add(createTile("Caixas de Bônus", lblBonusBoxes, lblBonusBoxRate));
        sectionBoxes.add(createTile("Caixas de Carga/Loot", lblCargoBoxes, createSubLabel("Coletadas")));
        sectionBoxes.add(createTile("Chaves/Espólios", lblBootyKeys, createSubLabel("Abertos")));
        sectionBoxes.add(createTile("Status Coleta", createMetricLabel("Ativo", new Color(120, 255, 120)), createSubLabel("Monitorando")));

        gridPanel.add(sectionBoxes);

        // Section 3: Minérios & Palladium
        JPanel sectionOres = createSectionPanel("Minérios & Carga");
        sectionOres.setLayout(new GridLayout(1, 3, 8, 4));

        lblPalladium = createMetricLabel("0", new Color(0, 210, 255));
        lblPalladiumRate = createSubLabel("0/h");

        lblOresTotal = createMetricLabel("0", new Color(180, 255, 180));
        lblCargoSpace = createMetricLabel("0 / 0", new Color(230, 230, 230));

        sectionOres.add(createTile("Palladium", lblPalladium, lblPalladiumRate));
        sectionOres.add(createTile("Outros Minérios", lblOresTotal, createSubLabel("Total recolhido")));
        sectionOres.add(createTile("Carga da Nave", lblCargoSpace, createSubLabel("Espaço do Porão")));

        gridPanel.add(sectionOres);

        rootPanel.add(gridPanel, BorderLayout.CENTER);
    }

    public JPanel getRootPanel() {
        return rootPanel;
    }

    private JPanel createSectionPanel(String title) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), title,
                TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION,
                null, new Color(200, 200, 200)));
        return p;
    }

    private JPanel createTile(String titleText, JLabel valueLabel, JLabel subLabel) {
        JPanel p = new JPanel(new GridLayout(3, 1, 1, 1));
        p.setOpaque(false);

        JLabel t = new JLabel(titleText);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 10f));
        t.setForeground(new Color(160, 160, 160));

        p.add(t);
        p.add(valueLabel);
        p.add(subLabel);
        return p;
    }

    private JLabel createMetricLabel(String initialText, Color color) {
        JLabel l = new JLabel(initialText);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
        l.setForeground(color);
        return l;
    }

    private JLabel createSubLabel(String initialText) {
        JLabel l = new JLabel(initialText);
        l.setFont(l.getFont().deriveFont(Font.PLAIN, 10f));
        l.setForeground(new Color(140, 140, 140));
        return l;
    }

    public void updateData(long elapsedMs,
                           double uridium, double uridiumRate,
                           double credits, double creditsRate,
                           double experience, double honor,
                           long bonusBoxes, double bonusBoxRate,
                           long cargoBoxes, long bootyKeys,
                           long palladium, double palladiumRate,
                           long totalOres, int cargoCurrent, int cargoMax) {
        lblSessionTime.setText("Sessão: " + formatDuration(elapsedMs));

        lblUridium.setText("+" + FMT.format((long) uridium));
        lblUridiumRate.setText(FMT.format((long) uridiumRate) + " / hora");

        lblCredits.setText("+" + FMT.format((long) credits));
        lblCreditsRate.setText(FMT.format((long) creditsRate) + " / hora");

        lblExperience.setText("+" + FMT.format((long) experience));
        lblHonor.setText("+" + FMT.format((long) honor));

        lblBonusBoxes.setText(FMT.format(bonusBoxes));
        lblBonusBoxRate.setText(FMT.format((long) bonusBoxRate) + " / hora");

        lblCargoBoxes.setText(FMT.format(cargoBoxes));
        lblBootyKeys.setText(FMT.format(bootyKeys));

        lblPalladium.setText(FMT.format(palladium));
        lblPalladiumRate.setText(FMT.format((long) palladiumRate) + " / hora");

        lblOresTotal.setText(FMT.format(totalOres));
        lblCargoSpace.setText(cargoCurrent + " / " + cargoMax);
    }

    private static String formatDuration(long millis) {
        long secs = millis / 1000;
        long h = secs / 3600;
        long m = (secs % 3600) / 60;
        long s = secs % 60;
        return String.format("%02dh %02dm %02ds", h, m, s);
    }
}
