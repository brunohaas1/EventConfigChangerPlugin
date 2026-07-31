package com.eventchanger.quest;

import eu.darkbot.api.game.other.Area;
import eu.darkbot.api.game.other.GameMap;
import eu.darkbot.api.game.other.Gui;
import eu.darkbot.api.managers.GameScreenAPI;
import eu.darkbot.api.managers.QuestAPI;
import eu.darkbot.api.managers.QuestAPI.Quest;
import eu.darkbot.api.managers.QuestAPI.Requirement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cicla pelas quests aceitas clicando nas "bolinhas" do HUD de Missões do jogo.
 *
 * A janela de Missões do HUD (GUI "quests" do GameScreenAPI) mostra a quest ativa
 * e possui bolinhas no topo — uma por quest aceita. Ao clicar numa bolinha, a quest
 * exibida muda, e podemos ler seus requisitos via {@code getDisplayedQuest()}.
 *
 * A ciclagem é feita em etapas, uma por tick do bot (cada tick = ~100ms):
 * 1. Abre/verifica a GUI do HUD
 * 2. Registra a quest original (para restaurar depois)
 * 3. Clica sequencialmente em cada bolinha (da esquerda para a direita)
 * 4. Após cada clique, aguarda 1 tick e lê/cacheia os requisitos
 * 5. Ao final, clica na bolinha da quest original para restaurar
 */
public class QuestHudCycler {

    private final QuestContext ctx;
    private final QuestLogger logger;
    private final MapResolver mapResolver;

    // ---- Layout das bolinhas no HUD de Missões ----
    // Baseado na análise da screenshot: as bolinhas ficam ~12px abaixo do topo,
    // centralizadas, com ~16px de espaçamento centro-a-centro.
    // O DarkOrbit suporta até ~10 quests aceitas simultâneas.
    private static final int MAX_DOTS = 10;
    private static final int DOT_SPACING_PX = 16;  // espaçamento centro-a-centro em pixels
    private static final int DOT_Y_OFFSET_PX = 12; // distância do topo da janela ao centro das bolinhas
    private static final long CLICK_COOLDOWN_MS = 600; // tempo entre cliques
    private static final long VERIFY_DELAY_MS = 400;   // tempo para esperar a quest mudar após o clique
    private static final long CYCLE_TIMEOUT_MS = 15_000; // timeout total da ciclagem

    // ---- Estado da ciclagem ----
    public enum CycleState {
        IDLE,           // Não está ciclando
        OPENING_GUI,    // Tentando abrir a GUI de missões
        RECORD_ORIGINAL,// Registrando a quest original
        CLICKING_DOT,   // Clicando numa bolinha
        WAITING_CHANGE, // Aguardando a quest mudar após clique
        CACHING,        // Cacheando os requisitos da quest exibida
        RESTORING,      // Restaurando a quest original
        DONE            // Ciclagem concluída
    }

    private CycleState state = CycleState.IDLE;
    private int currentDotIndex = 0;
    private int originalQuestId = -1;
    private int originalDotIndex = -1;
    private long lastActionTime = 0L;
    private long cycleStartTime = 0L;
    private int lastDisplayedQuestId = -1;
    private final Set<Integer> seenQuestIds = new HashSet<>();
    private int consecutiveNoChange = 0; // quantas vezes o clique não mudou a quest
    private boolean cycleCompletedOnce = false; // se já ciclamos pelo menos uma vez
    private long lastCycleCompleteTime = 0L;

    // Intervalo entre ciclagens automáticas (60 segundos)
    private static final long CYCLE_INTERVAL_MS = 60_000;

    public QuestHudCycler(QuestContext ctx, QuestLogger logger, MapResolver mapResolver) {
        this.ctx = ctx;
        this.logger = logger;
        this.mapResolver = mapResolver;
    }

    /**
     * @return true se está ativamente ciclando (o módulo principal deve esperar)
     */
    public boolean isCycling() {
        return state != CycleState.IDLE && state != CycleState.DONE;
    }

    /**
     * Inicia uma ciclagem se:
     * - Não está ciclando atualmente
     * - Passou tempo suficiente desde a última ciclagem
     * - Há quest ativa no HUD
     */
    public void startCycleIfNeeded(long now) {
        if (isCycling()) return;

        // Não re-ciclar se acabou de completar
        if (cycleCompletedOnce && now - lastCycleCompleteTime < CYCLE_INTERVAL_MS) return;

        // Precisa ter quest ativa no HUD
        Quest displayed = ctx.questAPI.getDisplayedQuest();
        if (displayed == null) return;

        // Inicia
        logger.logDebug("[HudCycler] Iniciando ciclagem de quests via bolinhas do HUD.");
        state = CycleState.OPENING_GUI;
        currentDotIndex = 0;
        originalQuestId = displayed.getId();
        originalDotIndex = -1;
        lastActionTime = now;
        cycleStartTime = now;
        lastDisplayedQuestId = displayed.getId();
        seenQuestIds.clear();
        seenQuestIds.add(displayed.getId());
        consecutiveNoChange = 0;
    }

    /**
     * Tick principal da ciclagem. Deve ser chamado a cada tick do QuestModule.
     * @return true se está ciclando (o caller deve evitar outras ações)
     */
    public boolean tick(long now) {
        if (state == CycleState.IDLE || state == CycleState.DONE) {
            return false;
        }

        // Timeout de segurança
        if (now - cycleStartTime > CYCLE_TIMEOUT_MS) {
            logger.logDebug("[HudCycler] Timeout de ciclagem atingido. Abortando.");
            finishCycle(now);
            return false;
        }

        switch (state) {
            case OPENING_GUI:
                tickOpeningGui(now);
                break;
            case RECORD_ORIGINAL:
                tickRecordOriginal(now);
                break;
            case CLICKING_DOT:
                tickClickingDot(now);
                break;
            case WAITING_CHANGE:
                tickWaitingChange(now);
                break;
            case CACHING:
                tickCaching(now);
                break;
            case RESTORING:
                tickRestoring(now);
                break;
            default:
                break;
        }

        return isCycling();
    }

    // ---- Estado: Abrindo a GUI do HUD ----
    private void tickOpeningGui(long now) {
        Gui questGui = ctx.questGui;
        if (questGui == null) {
            logger.logDebug("[HudCycler] GUI 'quests' não disponível. Abortando.");
            finishCycle(now);
            return;
        }

        if (questGui.isVisible()) {
            logger.logDebug("[HudCycler] GUI 'quests' já está visível. "
                    + "x=" + (int) questGui.getX()
                    + " y=" + (int) questGui.getY()
                    + " w=" + (int) questGui.getWidth()
                    + " h=" + (int) questGui.getHeight());
            state = CycleState.RECORD_ORIGINAL;
            lastActionTime = now;
            return;
        }

        // Tenta abrir
        if (now - lastActionTime > 1000) {
            questGui.setVisible(true);
            lastActionTime = now;
            logger.logDebug("[HudCycler] Tentando abrir GUI 'quests' via setVisible(true).");
        }
    }

    // ---- Estado: Registrando a quest original ----
    private void tickRecordOriginal(long now) {
        Quest displayed = ctx.questAPI.getDisplayedQuest();
        if (displayed == null) {
            logger.logDebug("[HudCycler] Nenhuma quest exibida após abrir GUI. Abortando.");
            finishCycle(now);
            return;
        }

        originalQuestId = displayed.getId();
        lastDisplayedQuestId = displayed.getId();

        // Cachear a quest atual como a primeira
        cacheDisplayedQuestRequirements(displayed);

        logger.logDebug("[HudCycler] Quest original registrada: id=" + originalQuestId
                + " titulo='" + displayed.getTitle() + "'. Iniciando ciclagem de bolinhas.");

        // Começar a clicar nas bolinhas a partir do índice 0
        currentDotIndex = 0;
        state = CycleState.CLICKING_DOT;
        lastActionTime = now;
    }

    // ---- Estado: Clicando numa bolinha ----
    private void tickClickingDot(long now) {
        if (now - lastActionTime < CLICK_COOLDOWN_MS) return;

        if (currentDotIndex >= MAX_DOTS || consecutiveNoChange >= 3) {
            // Já tentamos todas as posições possíveis ou paramos de detectar novas quests
            logger.logDebug("[HudCycler] Ciclagem completa. Total de quests encontradas: " + seenQuestIds.size()
                    + " dotIndex=" + currentDotIndex + " noChange=" + consecutiveNoChange);
            // Restaurar a quest original
            state = CycleState.RESTORING;
            lastActionTime = now;
            return;
        }

        // Registrar qual quest está exibida ANTES do clique
        Quest before = ctx.questAPI.getDisplayedQuest();
        lastDisplayedQuestId = (before != null) ? before.getId() : -1;

        // Calcular posição e clicar na bolinha
        clickDot(currentDotIndex);
        logger.logDebug("[HudCycler] Clicou bolinha index=" + currentDotIndex
                + " (questAntes=" + lastDisplayedQuestId + ")");

        state = CycleState.WAITING_CHANGE;
        lastActionTime = now;
    }

    // ---- Estado: Aguardando a quest mudar ----
    private void tickWaitingChange(long now) {
        if (now - lastActionTime < VERIFY_DELAY_MS) return;

        Quest displayed = ctx.questAPI.getDisplayedQuest();
        int newId = (displayed != null) ? displayed.getId() : -1;

        if (newId != -1 && newId != lastDisplayedQuestId) {
            // Quest mudou! 
            consecutiveNoChange = 0;
            logger.logDebug("[HudCycler] Quest mudou! Nova quest id=" + newId
                    + " titulo='" + (displayed != null ? displayed.getTitle() : "?") + "'");

            // Se essa quest é a original, registrar o dotIndex
            if (newId == originalQuestId) {
                originalDotIndex = currentDotIndex;
            }

            if (!seenQuestIds.contains(newId)) {
                seenQuestIds.add(newId);
                state = CycleState.CACHING;
            } else {
                // Já vimos essa quest, pular direto pro próximo dot
                currentDotIndex++;
                state = CycleState.CLICKING_DOT;
            }
        } else {
            // Quest não mudou — essa posição pode estar vazia ou é a mesma quest
            consecutiveNoChange++;
            currentDotIndex++;
            state = CycleState.CLICKING_DOT;
        }

        lastActionTime = now;
    }

    // ---- Estado: Cacheando requisitos ----
    private void tickCaching(long now) {
        Quest displayed = ctx.questAPI.getDisplayedQuest();
        if (displayed != null) {
            cacheDisplayedQuestRequirements(displayed);
        }

        // Próxima bolinha
        currentDotIndex++;
        state = CycleState.CLICKING_DOT;
        lastActionTime = now;
    }

    // ---- Estado: Restaurando a quest original ----
    private void tickRestoring(long now) {
        if (now - lastActionTime < CLICK_COOLDOWN_MS) return;

        Quest displayed = ctx.questAPI.getDisplayedQuest();
        int currentId = (displayed != null) ? displayed.getId() : -1;

        if (currentId == originalQuestId) {
            logger.logDebug("[HudCycler] Quest original restaurada com sucesso (id=" + originalQuestId + ").");
            finishCycle(now);
            return;
        }

        // Se sabemos o dot index original, clicar diretamente nele
        if (originalDotIndex >= 0) {
            clickDot(originalDotIndex);
            logger.logDebug("[HudCycler] Restaurando quest original via dotIndex=" + originalDotIndex);
            lastActionTime = now;
            // Esperar e verificar no próximo tick
            originalDotIndex = -1; // evitar loop infinito
            return;
        }

        // Fallback: ciclar todas as bolinhas até achar a original
        // Tenta clicar sequencialmente
        for (int i = 0; i < MAX_DOTS; i++) {
            if (now - lastActionTime < CLICK_COOLDOWN_MS) return;
            clickDot(i);
            lastActionTime = now;
            // Na próxima chamada de tick, verificaremos se restaurou
            return;
        }

        // Se depois de tudo não restaurou, aceitar e finalizar
        logger.logDebug("[HudCycler] Não conseguiu restaurar quest original. Finalizando mesmo assim.");
        finishCycle(now);
    }

    // ---- Cachear os requisitos da quest exibida ----
    private void cacheDisplayedQuestRequirements(Quest quest) {
        if (quest == null || quest.getRequirements() == null) return;
        int qId = quest.getId();

        // Sempre atualizar o cache (pode ter mudado desde a última vez)
        List<QuestContext.CachedRequirement> crList = new ArrayList<>();
        for (Requirement r : quest.getRequirements()) {
            if (r == null || r.isCompleted()) continue;
            GameMap rMap = mapResolver.resolveQuestTargetMap(quest, r);
            crList.add(new QuestContext.CachedRequirement(
                    r.getDescription(),
                    r.getRequirementType(),
                    rMap != null ? rMap.getId() : null));
        }

        ctx.acceptedQuestRequirementsCache.put(qId, crList);
        logger.logDebug("[HudCycler] Cacheados " + crList.size() + " requisitos da quest id="
                + qId + " titulo='" + quest.getTitle() + "'");

        // Persistir no arquivo
        ctx.questGiverInteraction.saveAcceptedQuestRequirementsToFile();
    }

    // ---- Calcular posição e clicar numa bolinha ----
    private void clickDot(int dotIndex) {
        Gui gui = ctx.questGui;
        if (gui == null || !gui.isVisible()) return;

        // A GUI "quests" do HUD reporta suas coordenadas via getX()/getY().
        // As bolinhas ficam no topo da janela, centralizadas.
        // Usamos gui.click(plusX, plusY) que clica em (gui.x + plusX, gui.y + plusY).
        double guiWidth = gui.getWidth();
        if (guiWidth <= 0) guiWidth = 400; // fallback

        // Estimativa: se há até MAX_DOTS bolinhas (10), a largura total
        // é MAX_DOTS * DOT_SPACING_PX. Centralizamos na largura da GUI.
        // Para cada dotIndex, o X relativo é:
        //   centerX - (totalWidth/2) + dotIndex * spacing + spacing/2
        double totalDotsWidth = MAX_DOTS * DOT_SPACING_PX;
        double startX = (guiWidth / 2.0) - (totalDotsWidth / 2.0);
        int plusX = (int) (startX + dotIndex * DOT_SPACING_PX + DOT_SPACING_PX / 2.0);
        int plusY = DOT_Y_OFFSET_PX;

        // Limites de segurança
        if (plusX < 0) plusX = 5;
        if (plusX > (int) guiWidth) plusX = (int) guiWidth - 5;

        logger.logDiagnostic("[HudCycler] clickDot(" + dotIndex + ") plusX=" + plusX + " plusY=" + plusY
                + " guiPos=(" + (int) gui.getX() + "," + (int) gui.getY()
                + ") guiSize=" + (int) gui.getWidth() + "x" + (int) gui.getHeight());

        gui.click(plusX, plusY);
    }

    // ---- Finalizar ciclagem ----
    private void finishCycle(long now) {
        state = CycleState.IDLE;
        cycleCompletedOnce = true;
        lastCycleCompleteTime = now;
        logger.logDebug("[HudCycler] Ciclagem finalizada. Quests cacheadas: " + seenQuestIds.size()
                + " IDs: " + seenQuestIds);
    }

    /**
     * Força uma nova ciclagem na próxima oportunidade.
     */
    public void requestCycle() {
        lastCycleCompleteTime = 0L;
        cycleCompletedOnce = false;
    }

    /**
     * @return true se já completou pelo menos uma ciclagem
     */
    public boolean hasCycledOnce() {
        return cycleCompletedOnce;
    }

    /**
     * @return IDs das quests encontradas na última ciclagem
     */
    public Set<Integer> getSeenQuestIds() {
        return seenQuestIds;
    }
}
