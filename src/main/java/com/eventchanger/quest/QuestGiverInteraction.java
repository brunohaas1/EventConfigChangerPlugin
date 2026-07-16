package com.eventchanger.quest;

import eu.darkbot.api.game.entities.Station;
import eu.darkbot.api.game.other.Area;
import eu.darkbot.api.game.other.GameMap;
import eu.darkbot.api.game.other.Gui;
import eu.darkbot.api.game.other.Point;
import eu.darkbot.api.managers.GameScreenAPI;
import eu.darkbot.api.managers.QuestAPI;
import eu.darkbot.api.managers.QuestAPI.QuestListItem;

import com.github.manolo8.darkbot.Main;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * Encapsula toda a interação com o Quest Giver: inicialização do cache de quests
 * (abrindo a janela na base), aceite automático de novas quests, clique relativo
 * na GUI e persistência do cache de quests aceitas em arquivo.
 */
public class QuestGiverInteraction {
    private static final long ACCEPT_RETRY_DELAY_MS = 400L;

    // --- Constantes e estado para prioridade de missões diárias ---
    /**
     * Índice da aba "Missões Diárias" no QuestGiver (0-indexed, da esquerda).
     * Layout padrão: 0=Normal, 1=Diária, 2=Evento, 3=Clã, 4=Urgente.
     */
    private static final int DAILY_TAB_INDEX = 2;
    /** Índice da aba normal (missões regulares). */
    private static final int NORMAL_TAB_INDEX = 0;
    /** Total de abas no QuestGiver. */
    private static final int TOTAL_TABS = 5;

    // Estados da máquina de processamento de diárias
    private static final int DAILY_STATE_CHECK_TAB = 0;
    private static final int DAILY_STATE_WAIT_TAB = 1;
    private static final int DAILY_STATE_WAIT_LIST = 2;
    private static final int DAILY_STATE_ACCEPT = 3;
    private static final int DAILY_STATE_VERIFY_ACCEPT = 4;
    private static final int DAILY_STATE_RETURN_TAB = 5;
    private static final int DAILY_STATE_WAIT_RETURN = 6;
    private static final int DAILY_STATE_DONE = 7;

    /** Indica se as diárias já foram processadas neste ciclo de aceite. */
    private boolean dailyMissionsProcessed = false;
    /** Estado atual da máquina de processamento de diárias. */
    private int dailyState = DAILY_STATE_CHECK_TAB;
    /** Timestamp de quando mudamos de aba (para aguardar carregamento). */
    private long dailyTabSwitchTime = 0L;
    /** Linha atual da lista de diárias (para aceite sequencial). */
    private int dailyAcceptRow = 0;
    /** Quantidade de diárias aceitas neste ciclo. */
    private int dailyAcceptedCount = 0;
    /** ID da quest diária pendente de verificação de aceite. */
    private int dailyPendingAcceptId = -1;
    /** Título da quest diária pendente de verificação. */
    private String dailyPendingAcceptTitle = "";
    /** Timestamp de quando a verificação de aceite da diária deve ocorrer. */
    private long dailyPendingVerifyTime = 0L;
    /** Se o próximo passo na diária é "selecionar a linha" (true) ou "clicar aceitar" (false). */
    private boolean dailyNeedSelect = false;
    /** Contagem de falhas consecutivas ao aceitar diárias. */
    private int dailyAcceptFailStreak = 0;
    /** Máximo de falhas antes de desistir das diárias. */
    private static final int MAX_DAILY_ACCEPT_FAILS = 6;

    private final QuestContext ctx;
    private final QuestLogger logger;
    private final MapResolver mapResolver;

    public QuestGiverInteraction(QuestContext ctx, QuestLogger logger, MapResolver mapResolver) {
        this.ctx = ctx;
        this.logger = logger;
        this.mapResolver = mapResolver;
    }

    // ---------------------------------------------------------------------
    // Prioridade de missões diárias
    // ---------------------------------------------------------------------

    /**
     * Reseta o estado de processamento de diárias. Deve ser chamado quando:
     * - A janela do QuestGiver fecha
     * - O módulo é parado/reiniciado
     * - O bot sai da base
     */
    public void resetDailyMissionsState() {
        dailyMissionsProcessed = false;
        dailyState = DAILY_STATE_CHECK_TAB;
        dailyTabSwitchTime = 0L;
        dailyAcceptRow = 0;
        dailyAcceptedCount = 0;
        dailyPendingAcceptId = -1;
        dailyPendingAcceptTitle = "";
        dailyPendingVerifyTime = 0L;
        dailyNeedSelect = false;
        dailyAcceptFailStreak = 0;
    }

    /** @return true se as diárias já foram processadas neste ciclo. */
    public boolean isDailyMissionsProcessed() {
        return dailyMissionsProcessed;
    }

    /**
     * Clica numa aba específica do QuestGiver, usando as coordenadas exatas
     * do DmPlugin nativo (QuestGiverMediator.changeTab):
     *   getTabs() → DivContainer(initPosX+8, initPosY+35, width=813, height=21)
     *   segmentWidth = 813 / 5 = 162.6
     *   clickX = initPosX + 8 + (tabIndex * segmentWidth) - (segmentWidth / 2)
     *   clickY = centerY do DivContainer = initPosY + 35 + 21/2
     *
     * Em coordenadas relativas à janela 840x550:
     *   relX = (8 + tabIndex * 162.6 - 81.3) / 840
     *   relY = (35 + 10.5) / 550 ≈ 0.0827
     */
    private void clickTab(int tabIndex) {
        if (tabIndex < 0 || tabIndex >= TOTAL_TABS) return;
        double segmentWidth = 813.0 / TOTAL_TABS; // 162.6
        double tabCenterX = 8.0 + (tabIndex * segmentWidth) + (segmentWidth / 2.0);
        double tabCenterY = 35.0 + (21.0 / 2.0); // 45.5
        double relX = tabCenterX / 840.0;
        double relY = tabCenterY / 550.0;
        logger.logDiagnostic("[DailyQuest] Clicando na aba " + tabIndex
                + " relX=" + String.format("%.3f", relX)
                + " relY=" + String.format("%.3f", relY));
        clickQuestGuiRelative(relX, relY);
    }

    /**
     * Máquina de estados não-bloqueante para processar missões diárias.
     * Executa UMA ação por tick. Retorna true quando o processamento
     * está em andamento (o chamador deve retornar sem executar o fluxo normal).
     * Retorna false quando dailyMissionsProcessed = true (pronto para normais).
     */
    public boolean processDailyMissions(long now) {
        if (dailyMissionsProcessed) {
            return false; // Já processou, deixar o fluxo normal rodar
        }

        // Garante que o QuestGiver esteja aberto
        if (!ctx.questAPI.isQuestGiverOpen()) {
            // QuestGiver fechou durante o processamento: reseta tudo
            logger.logDiagnostic("[DailyQuest] QuestGiver fechou durante processamento de diarias. Resetando.");
            resetDailyMissionsState();
            return true; // Ainda não processou; o fluxo normal vai reabrir
        }

        switch (dailyState) {
            case DAILY_STATE_CHECK_TAB: {
                // Verifica se já estamos na aba de diárias
                int currentTab = ctx.questAPI.getSelectedTab();
                if (currentTab == DAILY_TAB_INDEX) {
                    logger.logDiagnostic("[DailyQuest] Ja na aba de diarias (tab=" + currentTab + "). Aguardando lista.");
                    dailyState = DAILY_STATE_WAIT_LIST;
                    dailyTabSwitchTime = now;
                } else {
                    logger.logDiagnostic("[DailyQuest] Aba atual=" + currentTab + ", trocando para aba de diarias (tab=" + DAILY_TAB_INDEX + ").");
                    clickTab(DAILY_TAB_INDEX);
                    dailyTabSwitchTime = now;
                    dailyState = DAILY_STATE_WAIT_TAB;
                }
                ctx.currentAction = "[Quest] Abrindo aba de missoes diarias...";
                return true;
            }

            case DAILY_STATE_WAIT_TAB: {
                // Aguarda a aba trocar (verifica via getSelectedTab)
                int currentTab = ctx.questAPI.getSelectedTab();
                if (currentTab == DAILY_TAB_INDEX) {
                    logger.logDiagnostic("[DailyQuest] Aba de diarias selecionada com sucesso. Aguardando lista carregar.");
                    dailyState = DAILY_STATE_WAIT_LIST;
                    dailyTabSwitchTime = now; // Reset para aguardar o carregamento da lista
                    return true;
                }
                // Timeout: se não trocou em 3s, tenta clicar de novo
                if (now - dailyTabSwitchTime > 3000) {
                    logger.logDiagnostic("[DailyQuest] Timeout esperando troca de aba. Tentando novamente.");
                    clickTab(DAILY_TAB_INDEX);
                    dailyTabSwitchTime = now;
                }
                ctx.currentAction = "[Quest] Aguardando troca para aba de diarias...";
                return true;
            }

            case DAILY_STATE_WAIT_LIST: {
                // Aguarda 1.5s para a lista de quests carregar após troca de aba
                if (now - dailyTabSwitchTime < 1500) {
                    ctx.currentAction = "[Quest] Aguardando lista de diarias carregar...";
                    return true;
                }
                dailyState = DAILY_STATE_ACCEPT;
                dailyAcceptRow = 0;
                dailyNeedSelect = false; // Primeira linha já vem selecionada
                return true;
            }

            case DAILY_STATE_ACCEPT: {
                // Cooldown entre cliques
                if (now - ctx.lastAcceptAttemptTime < ACCEPT_RETRY_DELAY_MS) {
                    ctx.currentAction = "[Quest] Aceitando missoes diarias... (" + dailyAcceptedCount + " aceitas)";
                    return true;
                }

                // Lê a lista de quests da aba atual
                List<? extends QuestListItem> quests = ctx.questAPI.getCurrestQuests();
                if (quests == null || quests.isEmpty()) {
                    logger.logDiagnostic("[DailyQuest] Lista de diarias vazia. Considerando diarias processadas.");
                    dailyState = DAILY_STATE_RETURN_TAB;
                    return true;
                }

                // Filtra candidatos aceitáveis (activable e não completed e não R-Zone)
                List<? extends QuestListItem> candidates = quests.stream()
                        .filter(q -> q != null && !q.isCompleted() && q.isActivable() && !isRZoneQuest(q))
                        .collect(java.util.stream.Collectors.toList());

                if (candidates.isEmpty()) {
                    logger.logDiagnostic("[DailyQuest] Nenhuma diaria aceitavel encontrada. Total na lista=" + quests.size()
                            + ", aceitas neste ciclo=" + dailyAcceptedCount);
                    dailyState = DAILY_STATE_RETURN_TAB;
                    return true;
                }

                // Limite de falhas atingido: desiste das diárias
                if (dailyAcceptFailStreak >= MAX_DAILY_ACCEPT_FAILS) {
                    logger.logDiagnostic("[DailyQuest] Limite de falhas atingido (" + dailyAcceptFailStreak
                            + "). Desistindo das diarias e voltando para normais.");
                    dailyState = DAILY_STATE_RETURN_TAB;
                    return true;
                }

                WindowRect diagWin = computeQuestGiverWindow();

                // Se precisamos selecionar a linha (linhas > 0)
                if (dailyNeedSelect) {
                    dailyNeedSelect = false;
                    int row = dailyAcceptRow;
                    double relX = QuestConfig.QuestFlowConfig.LIST_ITEM_X;
                    double relY = QuestConfig.QuestFlowConfig.LIST_ITEM_Y + (row * 0.0624);
                    if (relY > 0.93) {
                        // Esgotamos as linhas visíveis
                        logger.logDiagnostic("[DailyQuest] Linhas visiveis esgotadas na aba de diarias.");
                        dailyState = DAILY_STATE_RETURN_TAB;
                        return true;
                    }
                    logger.logDiagnostic("[DailyQuest] SELECT: clicando linha " + (row + 1)
                            + " rel=(" + String.format("%.2f", relX) + "," + String.format("%.2f", relY) + ")");
                    clickQuestGuiRelative(relX, relY);
                    ctx.lastAcceptAttemptTime = now;
                    // Próximo tick: etapa ACCEPT (clicar no botão)
                    ctx.currentAction = "[Quest] Aceitando missoes diarias... selecionando linha " + (row + 1);
                    return true;
                }

                // Clica no botão Aceitar
                QuestListItem selected = ctx.questAPI.getSelectedQuestInfo();
                boolean selectedIsCandidate = selected != null && !selected.isCompleted() && selected.isActivable() && !isRZoneQuest(selected);

                if (selectedIsCandidate) {
                    double ax = QuestConfig.QuestFlowConfig.ACCEPT_BUTTON_X;
                    double ay = QuestConfig.QuestFlowConfig.ACCEPT_BUTTON_Y;
                    logger.logDiagnostic("[DailyQuest] ACCEPT: clicando Aceitar para diaria '"
                            + selected.getTitle() + "' (id=" + selected.getId() + ")");
                    clickQuestGuiRelative(ax, ay);
                    ctx.lastAcceptAttemptTime = now;

                    // Agenda verificação
                    dailyPendingAcceptId = selected.getId();
                    dailyPendingAcceptTitle = selected.getTitle() != null ? selected.getTitle() : "";
                    dailyPendingVerifyTime = now + 700;
                    dailyState = DAILY_STATE_VERIFY_ACCEPT;
                    ctx.currentAction = "[Quest] Aceitando missoes diarias... clicando Aceitar";
                    return true;
                }

                // Missão selecionada não é candidata: avança para a próxima linha
                logger.logDiagnostic("[DailyQuest] Missao selecionada nao e candidata ("
                        + (selected != null ? selected.getTitle() : "null") + "); avancando linha.");
                dailyAcceptRow++;
                dailyNeedSelect = true;
                ctx.currentAction = "[Quest] Aceitando missoes diarias... (" + dailyAcceptedCount + " aceitas)";
                return true;
            }

            case DAILY_STATE_VERIFY_ACCEPT: {
                // Aguarda o tempo de verificação
                if (now < dailyPendingVerifyTime) {
                    ctx.currentAction = "[Quest] Aceitando missoes diarias... verificando aceite";
                    return true;
                }

                // Verifica se a quest foi aceita
                QuestListItem pending = new QuestListItem() {
                    @Override public int getId() { return dailyPendingAcceptId; }
                    @Override public int getLevelRequired() { return 0; }
                    @Override public String getTitle() { return dailyPendingAcceptTitle; }
                    @Override public String getType() { return ""; }
                    @Override public boolean isSelected() { return false; }
                    @Override public boolean isCompleted() { return false; }
                    @Override public boolean isActivable() { return false; }
                };

                dailyPendingVerifyTime = 0L;

                if (wasQuestAccepted(pending)) {
                    dailyAcceptedCount++;
                    dailyAcceptFailStreak = 0;
                    logger.logDiagnostic("[DailyQuest] DIARIA ACEITA: " + dailyPendingAcceptId
                            + ":" + dailyPendingAcceptTitle + " (" + dailyAcceptedCount + " aceitas)");
                    logger.appendPluginLog("[DailyQuest] DIARIA ACEITA: " + dailyPendingAcceptId
                            + ":" + dailyPendingAcceptTitle);

                    // Adiciona ao cache de quests aceitas
                    ctx.acceptedQuestTitleCache.put(dailyPendingAcceptId, dailyPendingAcceptTitle);
                    saveAcceptedQuestCacheToFile();

                    // Avança para a próxima linha
                    dailyAcceptRow++;
                    dailyNeedSelect = true;
                    dailyPendingAcceptId = -1;
                    dailyPendingAcceptTitle = "";
                    dailyState = DAILY_STATE_ACCEPT; // Volta para aceitar mais
                    ctx.currentAction = "[Quest] Diaria aceita! (" + dailyAcceptedCount + " aceitas). Verificando mais...";
                    return true;
                }

                // Aceite não confirmado
                dailyAcceptFailStreak++;
                logger.logDiagnostic("[DailyQuest] Aceite NAO confirmado (falha " + dailyAcceptFailStreak
                        + "/" + MAX_DAILY_ACCEPT_FAILS + ")");
                dailyPendingAcceptId = -1;
                dailyPendingAcceptTitle = "";

                if (dailyAcceptFailStreak >= MAX_DAILY_ACCEPT_FAILS) {
                    logger.logDiagnostic("[DailyQuest] Limite de falhas atingido. Finalizando diarias.");
                    dailyState = DAILY_STATE_RETURN_TAB;
                } else {
                    // Tenta de novo (mesma linha)
                    dailyState = DAILY_STATE_ACCEPT;
                }
                return true;
            }

            case DAILY_STATE_RETURN_TAB: {
                // Volta para a aba normal
                int currentTab = ctx.questAPI.getSelectedTab();
                if (currentTab == NORMAL_TAB_INDEX) {
                    logger.logDiagnostic("[DailyQuest] Ja na aba normal. Diarias finalizadas ("
                            + dailyAcceptedCount + " aceitas).");
                    dailyState = DAILY_STATE_DONE;
                    dailyMissionsProcessed = true;
                    ctx.currentAction = "[Quest] Missoes diarias concluidas (" + dailyAcceptedCount + " aceitas)";
                    return false; // Libera para o fluxo normal
                }
                logger.logDiagnostic("[DailyQuest] Voltando para aba normal (tab=0). Aba atual=" + currentTab);
                clickTab(NORMAL_TAB_INDEX);
                dailyTabSwitchTime = now;
                dailyState = DAILY_STATE_WAIT_RETURN;
                ctx.currentAction = "[Quest] Voltando para aba de missoes normais...";
                return true;
            }

            case DAILY_STATE_WAIT_RETURN: {
                // Aguarda a aba voltar para normal
                int currentTab = ctx.questAPI.getSelectedTab();
                if (currentTab == NORMAL_TAB_INDEX) {
                    logger.logDiagnostic("[DailyQuest] Aba normal selecionada. Diarias finalizadas ("
                            + dailyAcceptedCount + " aceitas).");
                    dailyState = DAILY_STATE_DONE;
                    dailyMissionsProcessed = true;
                    ctx.currentAction = "[Quest] Missoes diarias concluidas (" + dailyAcceptedCount + " aceitas)";
                    return false; // Libera para o fluxo normal
                }
                if (now - dailyTabSwitchTime > 3000) {
                    logger.logDiagnostic("[DailyQuest] Timeout voltando para aba normal. Tentando novamente.");
                    clickTab(NORMAL_TAB_INDEX);
                    dailyTabSwitchTime = now;
                }
                ctx.currentAction = "[Quest] Aguardando troca para aba de missoes normais...";
                return true;
            }

            case DAILY_STATE_DONE:
            default: {
                dailyMissionsProcessed = true;
                return false; // Libera
            }
        }
    }

    // ---------------------------------------------------------------------
    // Persistência do cache de quests aceitas (para missões secundárias)
    // ---------------------------------------------------------------------

    public void loadAcceptedQuestCacheFromFile() {
        try {
            Path base = new MissionMapLoader(ctx, logger).resolvePluginDataDir();
            Path file = base.resolve(QuestContext.ACCEPTED_QUESTS_CACHE_FILE);
            if (!Files.exists(file)) return;
            int loaded = 0;
            for (String line : Files.readAllLines(file)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 1) continue;
                try {
                    int questId = Integer.parseInt(line.substring(0, eq).trim());
                    String title = line.substring(eq + 1).trim();
                    if (!title.isEmpty()) {
                        ctx.acceptedQuestTitleCache.put(questId, title);
                        loaded++;
                    }
                } catch (NumberFormatException ignored) {}
            }
            if (loaded > 0) {
                System.out.println("[QuestModule] Cache de quests aceitas carregado do arquivo: " + loaded + " quests");
            }
        } catch (Exception e) {
            System.err.println("[QuestModule] Erro ao carregar cache de quests aceitas: " + e.getMessage());
        }
    }

    public void saveAcceptedQuestCacheToFile() {
        long now = System.currentTimeMillis();
        if (now - ctx.lastCacheSaveTime < QuestContext.CACHE_SAVE_INTERVAL_MS) return;
        ctx.lastCacheSaveTime = now;
        try {
            Path base = new MissionMapLoader(ctx, logger).resolvePluginDataDir();
            Path file = base.resolve(QuestContext.ACCEPTED_QUESTS_CACHE_FILE);
            StringBuilder sb = new StringBuilder();
            sb.append("# Cache de quests aceitas - gerado automaticamente pelo QuestModule\n");
            sb.append("# Formato: questId=questTitle\n");
            for (java.util.Map.Entry<Integer, String> entry : ctx.acceptedQuestTitleCache.entrySet()) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
            }
            Files.writeString(file, sb.toString());
        } catch (Exception e) {
            System.err.println("[QuestModule] Erro ao salvar cache de quests aceitas: " + e.getMessage());
        }
    }

    public void cacheAcceptedQuestTitles(List<? extends QuestListItem> quests) {
        if (quests == null || quests.isEmpty()) return;

        boolean changed = false;
        for (QuestListItem q : quests) {
            if (q == null) continue;
            // Remove completed or activable quests from cache
            if (q.isCompleted() || q.isActivable()) {
                if (ctx.acceptedQuestTitleCache.remove(q.getId()) != null) changed = true;
            }
            // Add active (accepted but not completed) quests to cache
            else if (q.getTitle() != null && !q.getTitle().isEmpty()) {
                if (!q.getTitle().equals(ctx.acceptedQuestTitleCache.get(q.getId()))) {
                    ctx.acceptedQuestTitleCache.put(q.getId(), q.getTitle());
                    changed = true;
                }
            }
        }
        if (changed) {
            saveAcceptedQuestCacheToFile();
        }
    }

    // ---------------------------------------------------------------------
    // Clique na GUI do QuestGiver
    // ---------------------------------------------------------------------

    /**
     * Retângulo simples (ponto + tamanho) da janela do QuestGiver, calculado a
     * partir de getViewBounds(). NÃO implementa Area.Rectangle: essa interface
     * estende Area, que exige métodos abstratos (toSide/getPoints/intersectsLine)
     * que não usamos. Declarar "implements Area.Rectangle" sem implementá-los
     * tornava a classe ABSTRATA e QUEBRAVA A COMPILAÇÃO do plugin — fazendo o bot
     * rodar um JAR antigo (ainda com o bug de "spam"). Esta classe enxuta compila
     * e fornece exatamente o que precisamos para os cliques absolutos.
     */
    private static class WindowRect {
        final double x, y, w, h;
        WindowRect(double x, double y, double w, double h) {
            this.x = x; this.y = y; this.w = w; this.h = h;
        }
        double getX() { return x; }
        double getY() { return y; }
        double getWidth() { return w; }
        double getHeight() { return h; }
    }

    /**
     * Tamanho REAL da janela do QuestGiver no espaço de coordenadas do mouse.
     *
     * DESCOBERTA DO BUG: a GUI "quests" do DarkBot core (eu.darkbot.core.objects.Gui)
     * reporta getWidth()/getHeight() INCORRETOS para a janela do QuestGiver — nos logs
     * ela aparece como 200x200, enquanto a janela real é 813x550. O DmPlugin nativo
     * (QuestGiverMediator.getWindowQuestGiver) contorna isso usando um tamanho FIXO de
     * 813x550 para posicionar o botão Accept (getEndPosX()-10 / getEndPosY()-35).
     *
     * Como os cliques de aceite/select eram calculados com base em getWidth()/getHeight()
     * (200x200), eles caíam FORA dos botões -> as quests NUNCA eram aceitas -> a janela
     * fechava (GuiCloser) e o bot reiniciava o ciclo, parecendo "ficar andando" sem nunca
     * aceitar nada. Usar o tamanho fixo corrige os cliques.
     */
    private static final double QUEST_GIVER_WINDOW_WIDTH = 813.0;
    private static final double QUEST_GIVER_WINDOW_HEIGHT = 550.0;

    /**
     * Retângulo da janela do QuestGiver usado SÓ para log (as coordenadas de clique
     * reais vêm de getViewBounds() em clickQuestGuiRelative).
     *
     * IMPORTANTE: NÃO usamos ctx.questGui (a GUI "quests" do core) para nada além de
     * log. Essa GUI é a janela HUD de missões, NÃO o QuestGiver, e seus getX/getY/
     * getWidth/getHeight são inconsistentes (já vimos 200x200). O clique real usa
     * getViewBounds() + janela fixa 840x550 (igual ao DmPlugin nativo), que é
     * independente de ctx.questGui. Aqui retornamos sempre o retângulo centralizado
     * em getViewBounds() para fins de log.
     */
    private WindowRect computeQuestGiverWindow() {
        GameScreenAPI gsa = ctx.pluginAPI.requireAPI(GameScreenAPI.class);
        Area.Rectangle vb = gsa.getViewBounds();
        if (vb == null) return null;
        double w = QUEST_GIVER_WINDOW_WIDTH + 27.0; // 840 (igual clickQuestGuiRelative)
        double h = QUEST_GIVER_WINDOW_HEIGHT;       // 550
        double x = vb.getWidth() / 2.0 - w / 2.0;
        double y = vb.getHeight() / 2.0 - h / 2.0;
        return new WindowRect(x, y, w, h);
    }

    /**
     * Clica em uma posição relativa à janela do QuestGiver (0..1 em ambos os eixos).
     *
     * CAMINHO CORRETO: usa {@link Gui#click(int, int)} quando a GUI "quests" está
     * visível. O {@code Gui.click} é RELATIVO ao canto da própria janela
     * (internamente faz {@code Main.API.mouseClick(this.x + plusX, this.y + plusY)}),
     * então é imune a zoom/resolução — ao contrário de cliques absolutos de tela.
     *
     * FALLBACK: se a GUI não estiver disponível, cai no clique absoluto via
     * {@code DarkInput.mouseClick} calculado a partir de getViewBounds() (mantido
     * apenas por segurança; o caso normal sempre tem a GUI "quests").
     */

    /**
     * Garante que o QuestGiver esteja aberto antes de clicar.
     *
     * IMPORTANTE: o sinal de "aberto" é {@code isQuestGiverOpen()} (flag de memória
     * do core), NÃO {@code questGui.isVisible()}. O GuiCloser do core fecha a GUI
     * "quests" automaticamente após ~5s sem atualização, mas o flag isQuestGiverOpen
     * permanece TRUE. Usar isVisible() como portão causava o "Caso (B)": o bot via
     * coreSaysOpen=true + guiVisible=false, chamava setVisible(true) (que mexia na
     * janela HUD de missões errada) e trySelect (que o core ignorava pois já achava
     * aberto) -> loop de abrir/fechar a janela errada sem aceitar nada.
     *
     * Aqui usamos SÓ isQuestGiverOpen() como critério. Se estiver aberto, retorna
     * true. Se não, dispara trySelect(true) (throttled) para abrir e retorna false.
     */
    public boolean ensureQuestGuiVisible(long now) {
        if (ctx.questAPI.isQuestGiverOpen()) {
            return true;
        }
        // QuestGiver fechado: reseta o estado de aceite para recomeçar do topo quando
        // a janela reabrir, e dispara a abertura via trySelect no QuestGiver.
        ctx.acceptRowIndex = 0;
        ctx.acceptNeedSelect = false;
        ctx.acceptNeedAccept = false;
        ctx.pendingAcceptVerifyTime = 0L;
        ctx.pendingAcceptQuestId = -1;
        ctx.pendingAcceptQuestTitle = "";
        ctx.nextAcceptRetryTime = 0L;
        ctx.acceptFailStreak = 0;
        // Reseta diárias: ao fechar a janela, precisamos reprocessar diárias na reabertura
        resetDailyMissionsState();
        if (ctx.acceptOpenAttemptTime == 0L) {
            ctx.acceptOpenAttemptTime = now;
        }
        Station qg = findQuestGiver();
        if (qg != null && qg.isSelectable() && now - ctx.lastQuestGiverTrySelectTime > 1000) {
            logger.logDiagnostic("[AcceptQuest] ensureQuestGuiVisible: QuestGiver fechado, chamando trySelect(true) para abrir (coreSaysOpen=" + ctx.questAPI.isQuestGiverOpen() + ").");
            qg.trySelect(true);
            ctx.lastQuestGiverTrySelectTime = now;
        }
        // Timeout: se nunca abre, reseta para tentar do zero.
        if (now - ctx.acceptOpenAttemptTime > 8000) {
            logger.logDiagnostic("[AcceptQuest] Timeout garantindo abertura do QuestGiver; resetando.");
            ctx.acceptOpenAttemptTime = 0L;
        }
        return false;
    }

    /**
     * Reabre a janela do QuestGiver de forma AGRESSIVA após uma falha de clique:
     * dispara trySelect(true) no QuestGiver (throttled) e reseta o estado de aceite
     * para que o ciclo recomece do zero, aguardando a janela ficar disponível
     * (isQuestGiverOpen()) antes de retomar os cliques. NÃO usamos setVisible() na
     * GUI "quests" (isso mexia na janela HUD de missões errada e causava o loop).
     */
    private void reopenQuestGiverWindowAggressively(long now) {
        // Fecha o estado de abertura para forçar o core a reabrir via trySelect.
        // NÃO usamos setVisible(false) na GUI "quests": isso mexia na janela HUD de
        // missões errada e causava o loop de abrir/fechar. O trySelect(true) é o
        // mecanismo correto de (re)abertura do QuestGiver.
        ctx.acceptOpenAttemptTime = 0L;
        ctx.acceptRowIndex = 0;
        ctx.acceptNeedSelect = false;
        ctx.acceptNeedAccept = false;
        ctx.pendingAcceptVerifyTime = 0L;
        ctx.pendingAcceptQuestId = -1;
        ctx.pendingAcceptQuestTitle = "";
        ctx.nextAcceptRetryTime = now + ACCEPT_RETRY_DELAY_MS;
        Station qg = findQuestGiver();
        if (qg != null && qg.isSelectable() && now - ctx.lastQuestGiverTrySelectTime > 1000) {
            logger.logDiagnostic("[AcceptQuest] Reabrindo janela agressivamente via trySelect(true) apos falha de clique.");
            qg.trySelect(true);
            ctx.lastQuestGiverTrySelectTime = now;
        }
    }

    public void clickQuestGuiRelative(double relX, double relY) {
        // CORREÇÃO DO CLIQUE: o DmPlugin nativo (que funciona) clica em coordenadas
        // ABSOLUTAS de tela calculadas a partir de getViewBounds() (mapManager.screenBound)
        // + tamanho fixo da janela (840x550), usando Main.API.mouseClick direto.
        //
        // O Gui.click(plusX, plusY) do core soma this.x/this.y (canto da janela Flash).
        // Porém o Gui "quests" do DarkBot reporta getX()/getY()/getWidth()/getHeight()
        // INCONSISTENTES (já vimos 200x200 e posições defasadas nos logs), então o
        // clique relativo caía fora do botão. Para garantir que o clique chegue exatamente
        // onde o DmPlugin acerta, usamos getViewBounds() (espaço de tela do canvas) + o
        // tamanho fixo real da janela, e enviamos coordenadas ABSOLUTAS via Main.API.
        GameScreenAPI gsa = ctx.pluginAPI.requireAPI(GameScreenAPI.class);
        Area.Rectangle vb = gsa.getViewBounds();
        if (vb == null) {
            logger.logDebug("[QuestModule] clickQuestGuiRelative IGNORADO: getViewBounds nulo.");
            return;
        }
        // Janela do QuestGiver: centralizada no canvas, tamanho fixo 840x550 (igual DmPlugin).
        double w = QUEST_GIVER_WINDOW_WIDTH + 27.0; // 840 (DmPlugin usa 840 de largura)
        double h = QUEST_GIVER_WINDOW_HEIGHT;       // 550
        double x = vb.getWidth() / 2.0 - w / 2.0;
        double y = vb.getHeight() / 2.0 - h / 2.0;
        int absX = (int) (x + relX * w);
        int absY = (int) (y + relY * h);
        logger.logDebug("[QuestModule] Clique ABSOLUTO QuestGiver (Main.API) abs=(" + absX + "," + absY
                + ") rel=(" + relX + "," + relY + ") janela=(" + (int) x + "," + (int) y
                + " " + (int) w + "x" + (int) h + ") viewBounds=" + (int) vb.getWidth() + "x" + (int) vb.getHeight() + ")");
        // Hover real sobre o ponto (o Flash do DarkOrbit many vezes só "arma" o botão
        // após um evento de mouseMove), depois o clique. Isso cobre a hipótese de
        // roll-over/foco do componente.
        // IMPORTANTE: NÃO usar Thread.sleep() aqui. O DarkBot executa os plugins dentro
        // de um PluginClassLoader que BLOQUEIA java.lang.Thread (lista PROTECTED em
        // PluginClassLoader), então qualquer referência a Thread causa
        // NoClassDefFoundError/ClassNotFoundException. O bot já roda em tick; o mouseMove
        // e o mouseClick são comandos enviados ao jogo no mesmo tick (igual ao DmPlugin
        // nativo, que não espera entre mover e clicar). Se for preciso um intervalo
        // antes do próximo clique, use System.currentTimeMillis() + cooldown (padrão
        // oficial: clickDelay, waitUntil, nextCheck), nunca Thread.sleep.
        int darkInputHash = (ctx.darkInput != null) ? System.identityHashCode(ctx.darkInput) : -1;
        int coreApiHash = (ctx.coreApi != null) ? System.identityHashCode(ctx.coreApi) : -1;
        logger.logDiagnostic("[CLICK-CHAIN] ANTES mouseMove/mouseClick | abs=(" + absX + "," + absY + ")"
                + " | darkInput hash=" + darkInputHash
                + " | coreApi(Main.API) hash=" + coreApiHash
                + " | coreApi!=null=" + (ctx.coreApi != null));
        // --- Caminho: Main.API (mesma cadeia do DmPlugin oficial, acoplada ao pid) ---
        Main.API.mouseMove(absX, absY);
        Main.API.mouseClick(absX, absY);
        // --- Caminho B (só loga/executa se habilitado no teste): coreApi (Main.API) ---
        // O teste de comparação (clickTestMode) decide se também dispara pelo coreApi.
        logger.logDiagnostic("[CLICK-CHAIN] DEPOIS mouseMove/mouseClick (via ctx.darkInput) | abs=(" + absX + "," + absY + ")");
    }

    /**
     * TESTE ISOLADO DA CADEIA DE CLIQUE (janela do QuestGiver FECHADA).
     *
     * Clica no CENTRO do mapa (getViewBounds) usando o caminho selecionado em
     * QuestConfig.QuestFlowConfig.CLICK_TEST_MODE:
     *   - VIA_DARKINPUT: usa ctx.darkInput.mouseClick (caminho atual do plugin)
     *   - VIA_CORE_API:  usa ctx.coreApi.mouseClick (Main.API, caminho do DmPlugin)
     *
     * Se a nave começar a andar no VIA_CORE_API e NÃO andar no VIA_DARKINPUT,
     * confirma que ctx.darkInput é uma instância DarkInput ÓRFÃ (nunca acoplada
     * ao pid do cliente via openWindow), e por isso o clique nunca chega ao jogo.
     *
     * Throttled para 1 clique por segundo (não flooda nem trava o bot).
     */
    public void runClickTest(long now) {
        if (ctx.config == null) return;
        QuestConfig.ClickTestMode mode = QuestConfig.QuestFlowConfig.CLICK_TEST_MODE;
        if (mode == null || mode == QuestConfig.ClickTestMode.OFF) return;

        // Throttle: 1 clique por segundo.
        if (now - ctx.lastClickTestTime < 1000) return;
        ctx.lastClickTestTime = now;

        GameScreenAPI gsa = ctx.pluginAPI.requireAPI(GameScreenAPI.class);
        Area.Rectangle vb = gsa.getViewBounds();
        if (vb == null) {
            logger.logDiagnostic("[CLICK-TEST] IGNORADO: getViewBounds nulo.");
            return;
        }
        int cx = (int) (vb.getWidth() / 2.0);
        int cy = (int) (vb.getHeight() / 2.0);

        int darkInputHash = (ctx.darkInput != null) ? System.identityHashCode(ctx.darkInput) : -1;
        int coreApiHash = (ctx.coreApi != null) ? System.identityHashCode(ctx.coreApi) : -1;

        if (mode == QuestConfig.ClickTestMode.VIA_DARKINPUT) {
            logger.logDiagnostic("[CLICK-TEST] VIA_DARKINPUT click no centro (" + cx + "," + cy + ")"
                    + " | darkInput hash=" + darkInputHash);
            Main.API.mouseMove(cx, cy);
            Main.API.mouseClick(cx, cy);
        } else if (mode == QuestConfig.ClickTestMode.VIA_CORE_API) {
            if (ctx.coreApi == null) {
                logger.logDiagnostic("[CLICK-TEST] VIA_CORE_API IGNORADO: coreApi(Main.API) é null.");
                return;
            }
            logger.logDiagnostic("[CLICK-TEST] VIA_CORE_API click no centro (" + cx + "," + cy + ")"
                    + " | coreApi hash=" + coreApiHash);
            ctx.coreApi.mouseMove(cx, cy);
            ctx.coreApi.mouseClick(cx, cy);
        }
    }

    /**
     * Grade ABSOLUTA de posições relativas (0..1) cobrindo o canto INFERIOR-DIREITO
     * da janela do QuestGiver, onde o botão "Aceitar" REAL fica. O DmPlugin nativo
     * (QuestGiverMediator) clica em getEndPosX()-10 / getEndPosY()-35, ou seja
     * ~relX 0.975 / relY 0.936 numa janela 813x550. A grade abaixo varre
     * x=0.82..0.98 e y=0.82..0.98, garantindo que o botão seja atingido mesmo se
     * acceptButtonX/Y estiverem dessintonizados (ex.: usuário configurou 0.72, que
     * deixava a varredura presa à esquerda e ela nunca alcançava o canto).
     * Índice linear -> (x, y) usado pela varredura NÃO-BLOQUEANTE (1 célula/tick).
     */
    private static final double[] SCAN_DXS = {0.82, 0.87, 0.91, 0.95, 0.98};
    private static final double[] SCAN_DYS = {0.82, 0.87, 0.91, 0.95, 0.98};
    private static final int SCAN_CELLS = SCAN_DXS.length * SCAN_DYS.length;

    private double scanCellRx(int index) {
        return SCAN_DXS[index % SCAN_DXS.length];
    }
    private double scanCellRy(int index) {
        return SCAN_DYS[index / SCAN_DXS.length];
    }

    /**
     * Avança a varredura NÃO-BLOQUEANTE do botão Aceitar em UMA célula por tick.
     * Clica na célula atual (via Gui.click relativo) e agenda a verificação de
     * aceite para o tick seguinte (ctx.pendingAcceptVerifyTime). Não usa
     * Thread.sleep — assim o bot nunca trava durante a varredura.
     *
     * @return true se a varredura inteira foi esgotada sem aceitar (chamador decide
     *         reabrir a janela); false enquanto ainda há células a testar.
     */
    public boolean scanAndClickAcceptTick(QuestListItem toAccept, long now) {
        if (!ctx.questAPI.isQuestGiverOpen()) {
            logger.logDebug("[QuestModule] scanAndClickAcceptTick IGNORADO: QuestGiver nao aberto.");
            ctx.acceptScanIndex = -1;
            ctx.acceptScanning = false;
            return true; // esgotado (não conseguimos clicar)
        }
        if (ctx.acceptScanIndex < 0 || ctx.acceptScanIndex >= SCAN_CELLS) {
            ctx.acceptScanIndex = 0;
        }
        // Não sair da janela (cobre o canto inferior-direito real do botão)
        double rx = scanCellRx(ctx.acceptScanIndex);
        double ry = scanCellRy(ctx.acceptScanIndex);
        if (rx < 0.50 || rx > 0.995 || ry < 0.50 || ry > 0.995) {
            // Célula fora da janela: pula para a próxima sem clicar
            ctx.acceptScanIndex++;
            if (ctx.acceptScanIndex >= SCAN_CELLS) {
                ctx.acceptScanning = false;
                return true; // esgotado
            }
            return false;
        }

        logger.logDiagnostic("[AcceptQuest][Scan] tentando rel=(" + String.format("%.2f", rx) + ","
                + String.format("%.2f", ry) + ") cell=" + ctx.acceptScanIndex + "/" + SCAN_CELLS);
        clickQuestGuiRelative(rx, ry);
        ctx.lastAcceptAttemptTime = now;
        // Agenda verificação para o próximo tick (sem bloquear o bot)
        ctx.pendingAcceptVerifyTime = now + 250;
        ctx.pendingAcceptQuestId = toAccept.getId();
        ctx.pendingAcceptQuestTitle = toAccept.getTitle() != null ? toAccept.getTitle() : "";
        ctx.acceptScanPendingCheck = true;
        ctx.acceptScanLastRx = rx;
        ctx.acceptScanLastRy = ry;
        return false;
    }

    /**
     * Verifica se a quest alvo foi aceita: ela some da lista de quests aceitáveis
     * (getCurrestQuests) ou deixa de ser "activable".
     */
    public boolean wasQuestAccepted(QuestListItem toAccept) {
        List<? extends QuestListItem> quests = ctx.questAPI.getCurrestQuests();
        if (quests == null || quests.isEmpty()) return false;
        for (QuestListItem q : quests) {
            if (q == null) continue;
            if (q.getId() == toAccept.getId()) {
                // Ainda está na lista: foi aceita se não for mais activable
                return !q.isActivable();
            }
        }
        // Não está mais na lista => foi aceita
        return true;
    }

    /**
     * Helper de diagnóstico: dumpe todas as GUIs visíveis com suas bounds reais
     * (getX/getY/getWidth/getHeight) para ajudar a calibrar os offsets relativos
     * (listItemX/Y, acceptButtonX/Y) no client. Throttled para não floodar o log.
     */
    public void dumpVisibleGuis(long now) {
        if (now - ctx.lastGuiDumpTime < 5000) return;
        ctx.lastGuiDumpTime = now;
        try {
            GameScreenAPI gsa = ctx.pluginAPI.requireAPI(GameScreenAPI.class);
            java.util.Collection<? extends Gui> guis = gsa.getGuis();
            if (guis == null || guis.isEmpty()) {
                System.out.println("[QuestModule][GuiDump] Nenhuma GUI visivel.");
                return;
            }
            StringBuilder sb = new StringBuilder("[QuestModule][GuiDump] GUIs visiveis:\n");
            for (Gui g : guis) {
                if (g == null || !g.isVisible()) continue;
                sb.append("  - x=").append((int) g.getX())
                  .append(" y=").append((int) g.getY())
                  .append(" w=").append((int) g.getWidth())
                  .append(" h=").append((int) g.getHeight())
                  .append("\n");
            }
            System.out.println(sb.toString().trim());
        } catch (Exception e) {
            System.err.println("[QuestModule][GuiDump] Erro: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Inicialização do cache de quests (abre a janela na base)
    // ---------------------------------------------------------------------

    public void initializeQuestCache(long now) {
        if (ctx.questGui == null) {
            ctx.questCacheInitialized = true;
            return;
        }

        // Safety timeout: never freeze forever at the station if the window never reports open.
        if (ctx.questCacheOpenAttemptTime != 0L && now - ctx.questCacheOpenAttemptTime > 8000) {
            System.err.println("[QuestModule] Timeout ao inicializar cache de quests. Forcando continuacao.");
            // NÃO fechamos via setVisible() (janela HUD de missões errada). O core fecha
            // sozinho e o loop de aceite reabre via trySelect quando isQuestGiverOpen()=false.
            ctx.questCacheInitialized = true;
            ctx.questCacheOpenAttemptTime = 0L;
            return;
        }

        // Open the quest window
        Station questGiver = findQuestGiver();
        if (questGiver == null) {
            GameMap nextMap = mapResolver.resolveHomeMap();
            if (nextMap != null) {
                ctx.currentAction = "[QuestCache] Voando para " + nextMap.getName() + " para ler cache...";
                mapResolver.navigateToMap(nextMap, now);
            } else {
                ctx.questCacheInitialized = true; // Fallback
            }
            return;
        }

        // Open the Quest Giver window at the base
        double dist = questGiver.distanceTo(ctx.heroAPI);
        // CORREÇÃO DO "INDO E VOLTANDO": o trySelect do core JÁ navega o bot até a
        // station e abre a janela. O stop manual causava conflito: cancelava a
        // aproximação que o trySelect iniciou -> loop de vai-e-volta. Mantemos só um
        // moveTo inicial se MUITO longe (acelera a viagem), mas NUNCA chamamos stop.
        if (dist > 750) {
            ctx.setShipMode("roam");
            ctx.movementAPI.moveTo(questGiver);
            ctx.currentAction = "[QuestCache] Voando ate o Quest Giver... (dist: " + (int) dist + ")";
            return;
        }
        // CORREÇÃO: o sinal de "aberto" é SÓ isQuestGiverOpen() (flag de memória do
        // core), NÃO questGui.isVisible(). O GuiCloser do core fecha a GUI "quests"
        // sozinho após ~5s, mas isQuestGiverOpen() continua true. Usar isVisible()
        // como portão causava o "Caso (B)" (loop de abrir/fechar a janela HUD errada).
        if (!ctx.questAPI.isQuestGiverOpen()) {
            if (ctx.questCacheOpenAttemptTime == 0L) {
                ctx.questCacheOpenAttemptTime = now;
            }
            // CORREÇÃO DO SPAM: o trySelect a cada tick CANCELAVA a abertura da janela
            // do core (cada chamada reiniciava o processo de seleção), então a GUI nunca
            // ficava visível -> loop de spam até o timeout. Throttle de 1s (igual ao
            // autoAcceptNewQuest) deixa o core abrir a janela em paz.
            if (now - ctx.lastQuestGiverTrySelectTime > 1000) {
                logger.logDebug("[QuestCache] Chamando trySelect(true) no QuestGiver (dist=" + (int) dist
                        + ", isQuestGiverOpen=false)");
                ctx.currentAction = "[QuestCache] Abrindo menu do Quest Giver... (dist: " + (int) dist + ")";
                questGiver.trySelect(true);
                ctx.lastQuestGiverTrySelectTime = now;
            }
            return;
        }

        // Window is open: wait a fixed window for memory to update, then close and finish.
        // Use a dedicated timestamp (set when we first tried to open) so the wait is stable
        // and not reset every tick while the window is still opening.
        if (now - ctx.questCacheOpenAttemptTime > 2000) {
            // Mantém a janela aberta: o GuiCloser do core a fecha sozinho após ~5s e o
            // loop de aceite a reabre via trySelect quando isQuestGiverOpen() ficar false.
            ctx.questCacheInitialized = true;
            ctx.questCacheOpenAttemptTime = 0L;
            System.out.println("[QuestModule] Cache de quests inicializado via base com sucesso!");
        }
    }

    // ---------------------------------------------------------------------
    // Aceite automático de novas quests
    // ---------------------------------------------------------------------

    public void autoAcceptNewQuest(long now) {
        if (ctx.acceptRowIndex == 0 && ctx.pendingAcceptVerifyTime == 0L) {
            ctx.scrollClicksInCycle = 0;
        }

        // --- Dispatch da verificação de aceite agendada (não-bloqueante) ---
        // Se um clique de aceite foi enviado em tick anterior, verificamos agora se
        // a quest foi aceita. Isso evita Thread.sleep no tick principal.
        if (ctx.pendingAcceptVerifyTime != 0L) {
            if (now < ctx.pendingAcceptVerifyTime) {
                ctx.currentAction = "[AcceptQuest] Aguardando confirmacao do aceite...";
                return;
            }
            if (verifyPendingAccept(now)) {
                return; // aceite confirmado; encerra o ciclo
            }
            // Não aceitou: aguarda um pequeno intervalo antes de qualquer novo clique.
            ctx.nextAcceptRetryTime = Math.max(ctx.nextAcceptRetryTime, now + ACCEPT_RETRY_DELAY_MS);
            return;
        }

        // CORREÇÃO DO BUG "ACEITOU 5 E CONTINUA ACEITANDO MAIS":
        // Quando o ciclo de aceite foi completado (atingiu maxQuestsPerMap), NÃO
        // aceitamos mais nada. Sem esta guarda, o contador acceptedThisCycle é
        // zerado ao atingir o limite (em verifyPendingAccept) e o makeDecision
        // re-chamava autoAcceptNewQuest achando que ainda não havia aceitado nada,
        // fazendo o bot ir para a próxima linha e tentar aceitar missões a mais.
        if (ctx.acceptCycleComplete) {
            ctx.currentAction = "[AcceptQuest] Ciclo de aceite completo; nao aceitando mais quests.";
            return;
        }

        if (ctx.nextAcceptRetryTime != 0L) {
            if (now < ctx.nextAcceptRetryTime) {
                ctx.currentAction = "[AcceptQuest] Aguardando intervalo para nova tentativa...";
                return;
            }
            ctx.nextAcceptRetryTime = 0L;
        }

        // --- Loop da varredura não-bloqueante (1 célula por tick) ---
        // Se estamos no meio de uma varredura do botão Aceitar, avançamos uma célula
        // por tick (sem Thread.sleep). O retorno true indica varredura esgotada.
        if (ctx.acceptScanning && ctx.pendingAcceptVerifyTime == 0L) {
            // Precisamos do toAccept para a varredura; reconstruímos pelo id pendente
            // salvo antes de iniciar a varredura (acceptScanPendingCheck guarda o título).
            QuestListItem scanTarget = new QuestListItem() {
                @Override public int getId() { return ctx.pendingAcceptQuestId >= 0 ? ctx.pendingAcceptQuestId : -1; }
                @Override public int getLevelRequired() { return 0; }
                @Override public String getTitle() { return ctx.pendingAcceptQuestTitle; }
                @Override public String getType() { return ""; }
                @Override public boolean isSelected() { return false; }
                @Override public boolean isCompleted() { return false; }
                @Override public boolean isActivable() { return false; }
            };
            // Se o id ainda não foi salvo (caso raro), usa o candidato atual.
            if (ctx.pendingAcceptQuestId < 0) {
                List<? extends QuestListItem> q = ctx.questAPI.getCurrestQuests();
                if (q != null) {
                    for (QuestListItem item : q) {
                        if (item != null && !item.isCompleted() && item.isActivable() && matchesQuestTypeFilter(item)) {
                            scanTarget = item;
                            break;
                        }
                    }
                }
            }
            if (scanAndClickAcceptTick(scanTarget, now)) {
                // Varredura esgotada: reabre a janela agressivamente e tenta de novo do zero.
                logger.logDiagnostic("[AcceptQuest] Varredura esgotada; reabrindo janela agressivamente.");
                reopenQuestGiverWindowAggressively(now);
                ctx.acceptScanning = false;
                ctx.acceptScanIndex = -1;
            }
            return;
        }

        // Step 1: Must be at the base map before accepting quests
        GameMap homeMap = mapResolver.resolveHomeMap();
        if (homeMap == null) {
            ctx.currentAction = "[AcceptQuest] Nao foi possivel determinar o mapa base.";
            return;
        }

        GameMap currentMap = ctx.heroAPI.getMap();
        if (currentMap == null || currentMap.getId() != homeMap.getId()) {
            ctx.currentAction = "[AcceptQuest] Indo para a base para aceitar quest...";
            // NÃO fechamos a janela via setVisible(): isso mexia na janela HUD de
            // missões errada (causa do loop). O core fecha sozinho via GuiCloser e o
            // loop de aceite reabre via trySelect quando isQuestGiverOpen() ficar false.
            ctx.acceptOpenAttemptTime = 0L;
            // Cancela qualquer estado de aceite pendente ao sair da base.
            ctx.pendingAcceptVerifyTime = 0L;
            ctx.pendingAcceptQuestId = -1;
            ctx.pendingAcceptQuestTitle = "";
            ctx.nextAcceptRetryTime = 0L;
            ctx.acceptScanning = false;
            ctx.acceptScanIndex = -1;
            ctx.acceptRowIndex = 0;
            ctx.acceptNeedSelect = false;
            ctx.acceptNeedAccept = false;
            // Reseta diárias: ao sair da base, precisamos reprocessar diárias na próxima visita
            resetDailyMissionsState();
            mapResolver.navigateToMap(homeMap, now);
            return;
        }

        // Step 2: At base - find quest giver station and approach it
        Collection<? extends Station> stations = ctx.entitiesAPI.getStations();
        Station questStation = null;
        if (stations != null) {
            for (Station s : stations) {
                if (s instanceof Station.QuestGiver) {
                    questStation = s;
                    break;
                }
            }
        }

        if (questStation == null) {
            ctx.currentAction = "[AcceptQuest] QuestGiver nao encontrado no mapa base. Verificando novamente...";
            GameMap curMap = ctx.heroAPI.getMap();
            StringBuilder stationTypes = new StringBuilder();
            if (stations != null) {
                for (Station s : stations) {
                    stationTypes.append(s.getClass().getSimpleName()).append(", ");
                }
            }
            String typesStr = stationTypes.length() > 0
                    ? stationTypes.substring(0, stationTypes.length() - 2)
                    : "nenhum";
            logger.logDebug("[AcceptQuest] Nenhuma station do tipo QuestGiver em getStations() (total="
                    + (stations != null ? stations.size() : 0)
                    + ", mapa=" + (curMap != null ? curMap.getName() : "null")
                    + ", tipos=" + typesStr + ")");
            // Tenta voar um pouco para o centro para descobrir entidades
            ctx.setShipMode("roam");
            ctx.movementAPI.moveTo(10000, 6200);
            return;
        }

        double dist = questStation.distanceTo(ctx.heroAPI);
        // CORREÇÃO DO "INDO E VOLTANDO": o trySelect do core já navega até a station
        // e abre a janela. O stop manual em 650 cancelava a aproximação do trySelect
        // (que precisa chegar ao raio de interação, menor que 650) -> loop de vai-e-volta.
        // Deixamos o core cuidar: só fazemos moveTo se MUITO longe para acelerar a
        // viagem, e NUNCA chamamos stop (o trySelect para o bot no raio sozinho).
        if (dist > 750) {
            ctx.setShipMode("roam");
            ctx.movementAPI.moveTo(questStation);
            ctx.currentAction = "[AcceptQuest] Aproximando do quest giver... (dist: " + (int) dist + ")";
            return;
        }

        // Step 3: Open quest giver if not already open.
        //
        // O sinal de "aberto" é SÓ isQuestGiverOpen() (flag de memória do core), NÃO
        // questGui.isVisible(). O GuiCloser do core fecha a GUI "quests" sozinho após
        // ~5s, mas isQuestGiverOpen() continua true. Usar isVisible() causava o "Caso
        // (B)" (loop de abrir/fechar a janela HUD de missões errada). Aqui, se o core
        // diz aberto, CLICAMOS DIRETO (a janela real está lá, mesmo que o core a tenha
        // escondido); se diz fechado, abrimos via trySelect (throttled).
        boolean coreSaysOpen = ctx.questAPI.isQuestGiverOpen();

        if (!coreSaysOpen) {
            // Core diz fechado -> tentamos abrir via trySelect.
            if (ctx.acceptOpenAttemptTime == 0L) {
                ctx.acceptOpenAttemptTime = now;
                logger.logDiagnostic("[AcceptQuest] Iniciando tentativa de abrir QuestGiver (dist=" + (int) dist
                        + ", selectable=" + questStation.isSelectable()
                        + ", isQuestGiverOpen=false)");
            }
            // NÃO chamamos stop() aqui: o trySelect do core já navega até a station e
            // para no raio de interação. Parar manualmente causava o "ping-pong".
            // Só chama trySelect se a station estiver "selectable". Se não estiver,
            // aproxima (moveTo) até ficar no raio e ficar selecionável.
            if (!questStation.isSelectable()) {
                ctx.setShipMode("roam");
                ctx.movementAPI.moveTo(questStation);
                ctx.currentAction = "[AcceptQuest] QuestGiver ainda nao selecionavel, aproximando... (dist: " + (int) dist + ")";
                if (now - ctx.acceptOpenAttemptTime > 8000) {
                    logger.logDiagnostic("[AcceptQuest] Timeout: QuestGiver nunca ficou selecionavel apos 8s. Reiniciando.");
                    ctx.acceptOpenAttemptTime = 0L;
                }
                return;
            }
            // Throttle: no máximo 1 trySelect por segundo, para NÃO floodar o log nem
            // travar o core com chamadas repetidas.
            if (now - ctx.lastQuestGiverTrySelectTime > 1000) {
                logger.logDiagnostic("[AcceptQuest] Chamando trySelect(true) no QuestGiver (dist=" + (int) dist
                        + ", selectable=" + questStation.isSelectable() + ")");
                questStation.trySelect(true);
                ctx.lastQuestGiverTrySelectTime = now;
                ctx.currentAction = "[AcceptQuest] Abrindo menu do Quest Giver... (dist: " + (int) dist + ")";
            }
            // Timeout: se passou muito tempo e nada abriu, reseta para reavaliar.
            if (now - ctx.acceptOpenAttemptTime > 8000) {
                logger.logDiagnostic("[AcceptQuest] Timeout abrindo QuestGiver apos 8s, reiniciando tentativa.");
                ctx.acceptOpenAttemptTime = 0L;
            }
            return;
        }

        // Core diz ABERTO: a janela do QuestGiver está disponível (mesmo que o core a
        // tenha escondido via GuiCloser). NÃO chamamos setVisible() (isso mexia na
        // janela HUD de missões errada). Seguimos direto para os cliques, que usam
        // getViewBounds() + janela fixa 840x550 (independentes de questGui).

        // Step 4: Wait a fixed window after opening so getCurrestQuests() is populated
        if (now - ctx.acceptOpenAttemptTime < 1500) {
            ctx.currentAction = "[AcceptQuest] Aguardando lista de quests carregar...";
            return;
        }

        // Step 4.5: PRIORIDADE DIÁRIAS - processa TODAS as missões diárias antes
        // de aceitar qualquer missão normal. A máquina de estados processDailyMissions
        // retorna true enquanto estiver trabalhando; quando terminar (ou não houver
        // diárias), retorna false e liberamos o fluxo para as normais.
        if (processDailyMissions(now)) {
            return; // Diárias ainda em processamento; bloqueia o fluxo normal
        }

        // Step 5: Cooldown check before accepting
        if (now - ctx.lastAcceptAttemptTime < QuestConfig.QuestFlowConfig.ACCEPT_INTERVAL_MS) {
            ctx.currentAction = "[AcceptQuest] Aguardando intervalo de aceitacao...";
            return;
        }

        // Step 6: Get quest list and pick one to accept
        List<? extends QuestListItem> quests = ctx.questAPI.getCurrestQuests();
        if (quests == null || quests.isEmpty()) {
            ctx.currentAction = "[AcceptQuest] Buscando lista de quests...";
            logger.logDiagnostic("[AcceptQuest] getCurrestQuests() VAZIO ou nulo (isQuestGiverOpen="
                    + ctx.questAPI.isQuestGiverOpen()
                    + "). QuestGiver pode ter sido fechado pelo GuiCloser; o loop reabre via trySelect.");
            // Back-off: if we keep finding nothing, reset the open attempt so the bot
            // reopens via trySelect (sem mexer na janela HUD de missões errada).
            if (now - ctx.lastAcceptSuccessTime > 30000) {
                ctx.acceptOpenAttemptTime = 0L;
            }
            return;
        }
        cacheAcceptedQuestTitles(quests);

        // Log enxuto: apenas a quantidade de itens (evita poluir o log com a
        // lista completa de missões, que não ajuda em nada no diagnóstico).
        logger.logDiagnostic("[AcceptQuest] getCurrestQuests() retornou " + quests.size() + " itens.");

        List<? extends QuestListItem> candidates = quests.stream()
            .filter(q -> !q.isCompleted() && q.isActivable())
            .filter(this::matchesQuestTypeFilter)
                .collect(java.util.stream.Collectors.toList());

        if (candidates.isEmpty()) {
            ctx.currentAction = "[AcceptQuest] Nenhuma quest disponivel para aceitar.";
            logger.logDiagnostic("[AcceptQuest] Nenhum candidato apos filtro (questTypesToAccept="
                    + (ctx.config != null ? ctx.config.questTypesToAccept : "ALL") + ", questTypeConfig=" + ctx.config.questType + ")");
            // Back-off: if we keep finding nothing, reset the open attempt so the bot
            // reopens via trySelect (sem mexer na janela HUD de missões errada).
            if (now - ctx.lastAcceptSuccessTime > 30000) {
                ctx.acceptOpenAttemptTime = 0L;
            }
            return;
        }

        // Step 7: Aceitar as quests VISÍVEIS na GUI, uma por uma.
        //
        // FLUXO SIMPLES (igual a um humano, conforme desejado):
        //   - Ao abrir o QuestGiver, a 1ª missão JÁ está selecionada. Basta clicar
        //     em "Aceitar".
        //   - Depois, clica na PRÓXIMA missão da lista (a de baixo) para selecioná-la,
        //     e clica em "Aceitar". Repete até aceitar maxQuestsPerMap (5) missões ou
        //     não haver mais missões disponíveis na lista.
        //
        // Máquina de estados (em ctx):
        //   acceptRowIndex  : linha atual (0 = 1ª missão, já selecionada)
        //   acceptNeedSelect: precisa clicar na linha para selecioná-la (linhas > 0)
        //   acceptNeedAccept: precisa clicar no botão Aceitar
        ctx.currentAction = "[AcceptQuest] Aceitando quests visiveis via QuestGiver...";

        // GARANTE que o QuestGiver esteja aberto (via isQuestGiverOpen()) antes de
        // clicar. O sinal de "aberto" é a flag de memória do core, NÃO a visibilidade
        // da GUI "quests" do HUD (que o GuiCloser fecha após ~5s). O
        // ensureQuestGuiVisible reabre via trySelect quando isQuestGiverOpen() for
        // false e só retorna true quando o QuestGiver está disponível. Isso vale para
        // CADA tentativa de clique, não só no início do ciclo, pois o GuiCloser pode
        // fechar a janela entre um tick e outro.
        if (!ensureQuestGuiVisible(now)) {
            ctx.currentAction = "[AcceptQuest] Aguardando janela do QuestGiver ficar visivel antes de clicar...";
            return;
        }

        // Retângulo real da janela usado para os cliques
        WindowRect diagWin = computeQuestGiverWindow();

        // Inicializa o estado de aceite quando acabamos de abrir a janela: a 1ª
        // missão já vem selecionada, então só precisamos clicar em Aceitar.
        if (!ctx.acceptNeedAccept && !ctx.acceptNeedSelect && !ctx.acceptScanning
                && ctx.pendingAcceptVerifyTime == 0L) {
            ctx.acceptNeedAccept = true;
        }

        // Limite de quests por mapa atingido: encerra o ciclo de aceite.
        // CORREÇÃO: além de comparar acceptedThisCycle com o limite, também
        // respeitamos a flag acceptCycleComplete. O contador acceptedThisCycle é
        // zerado dentro de verifyPendingAccept() no momento em que o limite é
        // atingido (junto com acceptCycleComplete=true), então a comparação
        // "acceptedThisCycle >= max" sozinha ficava 0 >= 5 (falsa) e o bot
        // continuava aceitando missões além do limite (ia para a próxima linha e
        // tentava aceitar uma 6ª, 7ª...). A flag acceptCycleComplete é o sinal
        // autoritativo de "ciclo de aceite encerrado" e deve ser a porta de saída.
        if (ctx.acceptedThisCycle >= QuestConfig.QuestFlowConfig.MAX_QUESTS_PER_MAP || ctx.acceptCycleComplete) {
            logger.logDiagnostic("[AcceptQuest] Limite de quests atingido (" + ctx.acceptedThisCycle
                    + "/" + QuestConfig.QuestFlowConfig.MAX_QUESTS_PER_MAP + "). Encerrando ciclo de aceite.");
            // NÃO fechamos via setVisible() (janela HUD errada). O core fecha via
            // GuiCloser e o loop reabre via trySelect quando necessário.
            ctx.acceptOpenAttemptTime = 0L;
            ctx.acceptNeedAccept = false;
            ctx.acceptNeedSelect = false;
            ctx.acceptRowIndex = 0;
            ctx.acceptCycleComplete = true;
            ctx.acceptedThisCycle = 0;
            return;
        }

        // --- Etapa SELECT: clica na linha da lista para selecioná-la (linhas > 0). ---
        if (ctx.acceptNeedSelect) {
            ctx.acceptNeedSelect = false;
            int row = ctx.acceptRowIndex;
            double relX = QuestConfig.QuestFlowConfig.LIST_ITEM_X;
            // Espaçamento entre linhas = altura da linha / altura da janela.
            // DmPlugin: getQuestList().getHeight()=206, dividido por 6 linhas = 34,33px.
            // Relativo: 34,33/550 = 0,0624 (era 0,064, o que deslocava ~0,9px/linha e
            // acumulava erro para linhas de baixo). Usamos o valor exato do DmPlugin.
            double relY = QuestConfig.QuestFlowConfig.LIST_ITEM_Y + (row * 0.0624);
            if (relY > 0.93) {
                // Se a linha requer rolagem, clica na seta de rolar para baixo da scrollbar
                // para descer a lista e trazer as missões ocultas para a última linha visível (linha 5).
                if (ctx.scrollClicksInCycle < 25) {
                    ctx.scrollClicksInCycle++;
                    logger.logDiagnostic("[AcceptQuest] Linha " + (row + 1) + " fora de alcance (relY=" 
                            + String.format("%.2f", relY) + "). Clicando na seta para descer scroll da lista...");
                    
                    // Coordenadas relativas do botão de seta para baixo da scrollbar da lista (X=~0.284, Y=~0.911)
                    clickQuestGuiRelative(0.284, 0.911);
                    ctx.lastAcceptAttemptTime = now;
                    
                    // Aponta para a linha 5 (a última visível) para o próximo tick, pois a rolagem
                    // deslocou a lista para cima e trouxe os novos itens para a área de clique.
                    ctx.acceptRowIndex = 5;
                    ctx.acceptNeedSelect = true;
                    return;
                } else {
                    logger.logDiagnostic("[AcceptQuest] Limite de rolagens de scroll excedido (25). Encerrando ciclo de aceite.");
                    ctx.acceptOpenAttemptTime = 0L;
                    ctx.acceptNeedAccept = false;
                    ctx.acceptRowIndex = 0;
                    ctx.acceptCycleComplete = true;
                    ctx.acceptedThisCycle = 0;
                    ctx.scrollClicksInCycle = 0;
                    return;
                }
            }
            int absX = diagWin != null ? (int) (diagWin.getX() + relX * diagWin.getWidth()) : -1;
            int absY = diagWin != null ? (int) (diagWin.getY() + relY * diagWin.getHeight()) : -1;
            logger.logDiagnostic("[AcceptQuest] SELECT: clicando linha " + (row + 1) + ": rel=("
                    + String.format("%.2f", relX) + "," + String.format("%.2f", relY) + ") abs=(" + absX + "," + absY + ")");
            dumpVisibleGuis(now);
            // DIAGNÓSTICO DE SELEÇÃO: registra a quest que ESPERAMOS que fique
            // selecionada após este clique, para conferir no tick seguinte (etapa
            // ACCEPT) se o clique na lista realmente trocou a missão no core.
            // A "linha row" corresponde ao (row+1)-ésimo item da lista de candidatos
            // (candidatos.get(row)), na mesma ordem de getCurrestQuests().
            QuestListItem expected = null;
            try {
                if (candidates != null && row >= 0 && row < candidates.size()) {
                    expected = candidates.get(row);
                }
            } catch (Exception ignored) {}
            ctx.expectedSelectQuestId = (expected != null) ? expected.getId() : -1;
            ctx.expectedSelectQuestTitle = (expected != null && expected.getTitle() != null) ? expected.getTitle() : "";
            ctx.selectClickTime = now;
            // Log imediato: o que esperamos vs o que o core diz que está selecionado
            // ANTES do clique (para ver se já estava selecionado ou se o clique muda).
            QuestListItem selBefore = ctx.questAPI.getSelectedQuestInfo();
            logger.logDiagnostic("[AcceptQuest][SELECT-DIAG] ANTES do clique | esperada id="
                    + ctx.expectedSelectQuestId + " title='" + ctx.expectedSelectQuestTitle + "'"
                    + " | selecionada-AGORA id=" + (selBefore != null ? selBefore.getId() : -1)
                    + " title='" + (selBefore != null ? selBefore.getTitle() : "") + "'");
            clickQuestGuiRelative(relX, relY);
            ctx.lastAcceptAttemptTime = now;
            ctx.acceptNeedAccept = true; // próximo tick entra na etapa ACCEPT
            ctx.currentAction = "[AcceptQuest] Selecionando quest (linha " + (row + 1) + ")...";
            return;
        }

        // --- Etapa ACCEPT: lê a missão selecionada e clica em Aceitar. ---
        if (ctx.acceptNeedAccept) {
            ctx.acceptNeedAccept = false;
            QuestListItem selected = ctx.questAPI.getSelectedQuestInfo();
            // DIAGNÓSTICO DE SELEÇÃO (tick seguinte ao clique na lista): confere se o
            // clique na linha realmente trocou a missão selecionada no core. Se a
            // seleção real for diferente da esperada, o clique na lista NÃO está
            // funcionando (coordenada errada / camada / scrollbar) e o problema está
            // ANTES do botão Accept.
            if (ctx.expectedSelectQuestId >= 0) {
                int realId = (selected != null) ? selected.getId() : -1;
                String realTitle = (selected != null) ? selected.getTitle() : "";
                boolean match = (realId == ctx.expectedSelectQuestId)
                        || (realTitle != null && ctx.expectedSelectQuestTitle != null
                            && !ctx.expectedSelectQuestTitle.isEmpty()
                            && realTitle.equals(ctx.expectedSelectQuestTitle));
                logger.logDiagnostic("[AcceptQuest][SELECT-VERIFY] depois do clique na lista | esperada id="
                        + ctx.expectedSelectQuestId + " title='" + ctx.expectedSelectQuestTitle + "'"
                        + " | SELECIONADA-AGORA id=" + realId + " title='" + realTitle + "'"
                        + " | TROCOU_SELECAO=" + match);
                ctx.expectedSelectQuestId = -1;
                ctx.expectedSelectQuestTitle = "";
            }
            boolean selectedIsCandidate = selected != null
                    && !selected.isCompleted()
                    && selected.isActivable()
                    && matchesQuestTypeFilter(selected);
            if (selectedIsCandidate) {
                if (diagWin != null) {
                    logger.logDiagnostic("[AcceptQuest] Quest SELECIONADA na GUI e valida: id=" + selected.getId()
                            + " title=" + selected.getTitle() + " | janela x=" + (int) diagWin.getX()
                            + " y=" + (int) diagWin.getY() + " " + (int) diagWin.getWidth() + "x" + (int) diagWin.getHeight());
                }
                double ax = QuestConfig.QuestFlowConfig.ACCEPT_BUTTON_X;
                double ay = QuestConfig.QuestFlowConfig.ACCEPT_BUTTON_Y;
                int absAx = diagWin != null ? (int) (diagWin.getX() + ax * diagWin.getWidth()) : -1;
                int absAy = diagWin != null ? (int) (diagWin.getY() + ay * diagWin.getHeight()) : -1;
                logger.logDiagnostic("[AcceptQuest] Clique no botao Accept: rel=(" + ax + "," + ay
                        + ") abs=(" + absAx + "," + absAy + ")");
                dumpVisibleGuis(now);
                clickQuestGuiRelative(ax, ay);
                ctx.lastAcceptAttemptTime = now;
                System.out.println("[QuestModule] Clique de aceite enviado para: " + selected.getTitle()
                        + " (id=" + selected.getId() + ") em (" + ax + ", " + ay + ")");
                // Agenda verificação de aceite para o próximo tick (sem bloquear o bot).
                ctx.pendingAcceptVerifyTime = now + 700;
                ctx.pendingAcceptQuestId = selected.getId();
                ctx.pendingAcceptQuestTitle = selected.getTitle() != null ? selected.getTitle() : "";
                ctx.acceptScanning = false; // ainda não entramos na varredura
                return;
            }
            // A missão selecionada não é candidata (já aceita / filtro de tipo).
            // Avança para a próxima linha de baixo.
            logger.logDiagnostic("[AcceptQuest] Missao selecionada nao era candidata ("
                    + (selected != null ? selected.getTitle() : "null") + "); avancando para proxima linha.");
            ctx.acceptRowIndex++;
            ctx.acceptNeedSelect = true;
            return;
        }
    }

    /**
     * Verifica (no tick seguinte ao clique) se a quest pendente foi aceita.
     * Chamado por {@code autoAcceptNewQuest} quando ctx.pendingAcceptVerifyTime
     * chegou. Trata tanto o clique fixo quanto a varredura não-bloqueante.
     *
     * @return true se o aceite foi confirmado (e o fluxo deve encerrar este ciclo).
     */
    private boolean verifyPendingAccept(long now) {
        int pendingId = ctx.pendingAcceptQuestId;
        String pendingTitle = ctx.pendingAcceptQuestTitle;
        ctx.pendingAcceptVerifyTime = 0L;
        ctx.pendingAcceptQuestId = -1;
        ctx.pendingAcceptQuestTitle = "";

        // Reconstrói um proxy mínimo para wasQuestAccepted (só precisa do id).
        QuestListItem pending = new QuestListItem() {
            @Override public int getId() { return pendingId; }
            @Override public int getLevelRequired() { return 0; }
            @Override public String getTitle() { return pendingTitle; }
            @Override public String getType() { return ""; }
            @Override public boolean isSelected() { return false; }
            @Override public boolean isCompleted() { return false; }
            @Override public boolean isActivable() { return false; }
        };

        if (wasQuestAccepted(pending)) {
            ctx.lastAcceptSuccessTime = now;
            ctx.nextAcceptRetryTime = 0L;
            ctx.acceptedQuestTitleCache.put(pendingId, pendingTitle);
            saveAcceptedQuestCacheToFile();
            logger.logDiagnostic("[QuestModule] QUEST ACEITA via QuestGiver: " + pendingId + ":" + pendingTitle);
            logger.appendPluginLog("[QuestModule] QUEST ACEITA via QuestGiver: " + pendingId + ":" + pendingTitle);
            // NÃO fechamos via setVisible() (janela HUD errada). Mantemos a janela
            // aberta para seguir aceitando a próxima quest (acceptRowIndex++ abaixo);
            // o core a fecha via GuiCloser e o loop reabre via trySelect se preciso.
            ctx.acceptOpenAttemptTime = 0L;
            ctx.lastQuestId = -1; // force reset so the new active quest is detected
            ctx.acceptScanning = false;
            ctx.acceptScanIndex = -1;
            ctx.acceptScanPendingCheck = false;
            ctx.acceptedThisCycle++; // conta esta quest aceita neste ciclo
            // Aceite confirmado: zera a sequência de falhas (o jogo ainda aceita missões).
            ctx.acceptFailStreak = 0;
            // Avança para a próxima linha da lista (a de baixo) para a próxima aceitação.
            ctx.acceptRowIndex++;
            ctx.acceptNeedSelect = true;
            ctx.acceptNeedAccept = false;
            ctx.currentAction = "Quest aceita: " + pendingTitle + " (" + ctx.acceptedThisCycle + "/"
                    + QuestConfig.QuestFlowConfig.MAX_QUESTS_PER_MAP + ")";
            // Se atingiu o limite de quests por mapa, encerra o ciclo de aceite.
            if (ctx.acceptedThisCycle >= QuestConfig.QuestFlowConfig.MAX_QUESTS_PER_MAP) {
                ctx.acceptNeedSelect = false;
                ctx.acceptNeedAccept = false;
                ctx.acceptRowIndex = 0;
                ctx.acceptCycleComplete = true;
                ctx.acceptedThisCycle = 0;
                ctx.currentAction = "Limite de quests atingido (" + QuestConfig.QuestFlowConfig.MAX_QUESTS_PER_MAP
                        + "/" + QuestConfig.QuestFlowConfig.MAX_QUESTS_PER_MAP + "). Saindo para executar...";
            }
            return true;
        }

        // Aceite NÃO confirmado. Se o jogo JÁ está no limite de missões ativas
        // (ex: 5 aceitas antes pelo jogador), o botão Accept não surte efeito e
        // nenhum aceite é confirmado — o acceptedThisCycle nunca chega a
        // maxQuestsPerMap, então o bot ficaria travado num loop infinito tentando
        // aceitar mais. Detectamos isso contando falhas CONSECUTIVAS: ao atingir
        // MAX_ACCEPT_FAILS, encerramos o ciclo (acceptCycleComplete=true) e o bot
        // sai para executar as missões que já existem no cliente.
        ctx.acceptFailStreak++;
        logger.logDiagnostic("[AcceptQuest] Aceite NAO confirmado (tentativa " + ctx.acceptFailStreak
                + "/" + QuestContext.MAX_ACCEPT_FAILS + "). Pode ser que o jogo ja atingiu o limite de missoes ativas.");
        if (ctx.acceptFailStreak >= QuestContext.MAX_ACCEPT_FAILS) {
            logger.logDiagnostic("[AcceptQuest] Limite de tentativas de aceite atingido sem confirmacao ("
                    + ctx.acceptFailStreak + "). O jogo provavelmente ja tem " + QuestConfig.QuestFlowConfig.MAX_QUESTS_PER_MAP
                    + " missoes ativas. Encerrando ciclo de aceite e saindo para executar.");
            ctx.acceptNeedSelect = false;
            ctx.acceptNeedAccept = false;
            ctx.acceptRowIndex = 0;
            ctx.acceptScanning = false;
            ctx.acceptScanIndex = -1;
            ctx.acceptCycleComplete = true;
            ctx.acceptedThisCycle = 0;
            ctx.acceptFailStreak = 0;
            ctx.currentAction = "Limite de missoes ativas atingido no cliente. Saindo para executar...";
            return true;
        }

        // Aceite não confirmado. Se viemos de um clique de varredura, avança para a
        // próxima célula; se o clique fixo falhou, inicia a varredura (se habilitada).
        if (ctx.acceptScanPendingCheck) {
            ctx.acceptScanPendingCheck = false;
            ctx.acceptScanIndex++; // próxima célula no próximo tick
            if (ctx.acceptScanIndex >= SCAN_CELLS) {
                // Varredura esgotada: reabre a janela agressivamente e tenta de novo do zero.
                logger.logDiagnostic("[AcceptQuest] Varredura do botao esgotada sem aceitar. Reabrindo janela agressivamente.");
                reopenQuestGiverWindowAggressively(now);
                ctx.acceptScanning = false;
                ctx.acceptScanIndex = -1;
            }
            return false;
        }

        // Clique fixo não aceitou: entra na varredura (se habilitada).
        if (QuestConfig.QuestFlowConfig.SCAN_ACCEPT_BUTTON) {
            ctx.currentAction = "[AcceptQuest] Clique fixo falhou, varrendo posicoes do botao...";
            logger.logDiagnostic("[AcceptQuest] Clique fixo no Accept NAO aceitou; iniciando varredura do botao.");
            ctx.acceptScanning = true;
            ctx.acceptScanIndex = 0;
        } else {
            // Sem varredura: reabre agressivamente e tenta de novo.
            ctx.currentAction = "[AcceptQuest] Nao foi possivel aceitar (botao nao encontrado). Tentando novamente...";
            logger.logDiagnostic("[AcceptQuest] FALHA ao aceitar (clique fixo). Reabrindo janela agressivamente.");
            reopenQuestGiverWindowAggressively(now);
        }
        return false;
    }

    private boolean isRZoneQuest(QuestListItem item) {
        if (item == null) return false;
        String type = item.getType() != null ? item.getType().toLowerCase() : "";
        String itemTitle = item.getTitle() != null ? item.getTitle().toLowerCase() : "";

        // NUNCA aceitar missões de R-ZONE (Refraction Zone)
        if (type.contains("r-zone") || type.contains("rzone") || type.contains("refraction") || type.contains("refra")
                || itemTitle.contains("r-zone") || itemTitle.contains("r zone") || itemTitle.contains("r_zone") 
                || itemTitle.contains("refraction") || itemTitle.contains("refra")) {
            return true;
        }

        // Se a quest estiver selecionada na GUI, checa seus requisitos para barrar termos R-ZONE
        eu.darkbot.api.managers.QuestAPI.Quest selectedQuest = ctx.questAPI.getSelectedQuest();
        if (selectedQuest != null && selectedQuest.getId() == item.getId()) {
            java.util.List<? extends eu.darkbot.api.managers.QuestAPI.Requirement> reqs = selectedQuest.getRequirements();
            if (reqs != null) {
                for (eu.darkbot.api.managers.QuestAPI.Requirement r : reqs) {
                    if (r.getDescription() != null) {
                        String desc = r.getDescription().toLowerCase();
                        if (desc.contains("r-zone") || desc.contains("r zone") || desc.contains("r_zone") || desc.contains("refraction") || desc.contains("refra")) {
                            logger.logDiagnostic("[AcceptQuest] Recusando quest selecionada id=" + item.getId() + " '" + item.getTitle() + "' pois contem R-Zone nos requisitos.");
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean matchesQuestTypeFilter(QuestListItem item) {
        if (item == null || ctx.config == null) return false;
        if (isRZoneQuest(item)) return false;

        String type = item.getType() != null ? item.getType().toLowerCase() : "";
        String itemTitle = item.getTitle() != null ? item.getTitle().toLowerCase() : "";

        // CORREÇÃO: Missões PVP (kill players, damage players) geralmente têm tipo
        // "pvp" ou "kill_players" ou similar. Mas também podem ter tipo genérico
        // ("clan_mission", "event") e a descrição "Destroi jogadores inimigos 0/10"
        // está no requirement, não no tipo. Por isso verificamos também o título
        // e os requirements da quest ativa (se disponível).
        boolean isPvpType = type.contains("pvp") || type.contains("kill") || type.contains("player");

        // Se não identificou pelo tipo, verifica se a quest ativa (getDisplayedQuest)
        // tem algum requirement que mencione "jogador", "player", "kill" em português
        if (!isPvpType) {
            eu.darkbot.api.managers.QuestAPI.Quest activeQuest = ctx.questAPI.getDisplayedQuest();
            if (activeQuest != null) {
                // Verifica o título da quest
                String title = activeQuest.getTitle();
                if (title != null) {
                    String titleLower = title.toLowerCase();
                    if (titleLower.contains("jogador") || titleLower.contains("player")
                            || titleLower.contains("kill") || titleLower.contains("pvp")) {
                        isPvpType = true;
                    }
                }
                // Verifica os requirements da quest ativa
                if (!isPvpType) {
                    java.util.List<? extends eu.darkbot.api.managers.QuestAPI.Requirement> reqs = activeQuest.getRequirements();
                    if (reqs != null) {
                        for (eu.darkbot.api.managers.QuestAPI.Requirement r : reqs) {
                            if (r.getDescription() != null) {
                                String desc = r.getDescription().toLowerCase();
                                if (desc.contains("jogador") || desc.contains("player")
                                        || desc.contains("kill") || desc.contains("pvp")
                                        || desc.contains("inimigo") || desc.contains("enemy")) {
                                    isPvpType = true;
                                    break;
                                }
                            }
                            // Verifica também o tipo do requirement
                            if (r.getRequirementType() == eu.darkbot.api.managers.QuestAPI.Requirement.RequirementType.KILL_PLAYERS
                                    || r.getRequirementType() == eu.darkbot.api.managers.QuestAPI.Requirement.RequirementType.DAMAGE_PLAYERS
                                    || r.getRequirementType() == eu.darkbot.api.managers.QuestAPI.Requirement.RequirementType.DAMAGE_ENEMY_PLAYERS
                                    || r.getRequirementType() == eu.darkbot.api.managers.QuestAPI.Requirement.RequirementType.KILL_ANY) {
                                isPvpType = true;
                                break;
                            }
                        }
                    }
                }
            }
            // Fallback: verifica pelo título do próprio item da lista
            if (!isPvpType && item.getTitle() != null) {
                if (itemTitle.contains("jogador") || itemTitle.contains("player")
                        || itemTitle.contains("kill") || itemTitle.contains("pvp")
                        || itemTitle.contains("inimigo") || itemTitle.contains("enemy")) {
                    isPvpType = true;
                }
            }
        }

        if (isPvpType) {
            return ctx.config.acceptPvpQuests;
        }

        String configured = ctx.config != null && ctx.config.questTypesToAccept != null
                ? ctx.config.questTypesToAccept.trim().toUpperCase()
                : "ALL";
        if (!configured.isEmpty() && !"ALL".equals(configured)) {
            boolean matchesConfigured = false;
            for (String token : configured.split(",")) {
                String t = token.trim();
                if (t.isEmpty()) continue;
                if (t.equalsIgnoreCase("NORMAL")) {
                    boolean isNormal = type.isEmpty() 
                            || type.contains("normal") 
                            || type.contains("level") 
                            || type.contains("quest") 
                            || type.contains("challenge")
                            || type.contains("main")
                            || (!type.contains("daily") && !type.contains("weekly") && !type.contains("event") && !type.contains("special") && !type.contains("season") && !type.contains("urgent"));
                    if (isNormal) {
                        matchesConfigured = true;
                        break;
                    }
                } else if (t.equalsIgnoreCase("SPECIAL")) {
                    if (type.contains("special") || type.contains("event")) {
                        matchesConfigured = true;
                        break;
                    }
                } else {
                    if (type.contains(t.toLowerCase())) {
                        matchesConfigured = true;
                        break;
                    }
                }
            }
            // Se não casou com o filtro configurado, mas é PVP, permite passar mesmo assim
            if (!matchesConfigured && !isPvpType) return false;
        }

        boolean isUrgent = type.contains("urgent");
        if (ctx.config.questType == QuestTypeEnum.URGENT) {
            return isUrgent || isPvpType; // PVP sempre permitido mesmo em modo URGENT
        }
        return !isUrgent || isPvpType; // PVP sempre permitido
    }

    private int getAcceptRetryCount(int questId) {
        return ctx.acceptRetryCounts.getOrDefault(questId, 0);
    }

    private void incrementAcceptRetryCount(int questId) {
        int current = ctx.acceptRetryCounts.getOrDefault(questId, 0);
        ctx.acceptRetryCounts.put(questId, current + 1);
        // Reset retry count after 5 attempts to avoid infinite growth
        if (current + 1 > 5) {
            ctx.acceptRetryCounts.put(questId, 0);
        }
    }

    private Station findQuestGiver() {
        Collection<? extends Station> stations = ctx.entitiesAPI.getStations();
        if (stations != null) {
            for (Station s : stations) {
                if (s instanceof Station.QuestGiver) {
                    return s;
                }
            }
        }
        return null;
    }
}
