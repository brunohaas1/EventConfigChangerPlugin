package com.eventchanger.quest;

import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.extensions.Behavior;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Feature;
import eu.darkbot.api.extensions.InstructionProvider;
import eu.darkbot.api.extensions.Module;
import eu.darkbot.api.game.entities.Player;
import eu.darkbot.api.game.other.GameMap;
import eu.darkbot.api.game.other.Gui;
import eu.darkbot.api.managers.GameScreenAPI;
import eu.darkbot.api.managers.QuestAPI;
import eu.darkbot.api.managers.QuestAPI.Quest;
import eu.darkbot.api.managers.QuestAPI.QuestListItem;
import eu.darkbot.api.managers.QuestAPI.Requirement;
import eu.darkbot.api.managers.QuestAPI.Requirement.RequirementType;

import javax.swing.JComponent;

/**
 * Orquestrador do módulo de quests. Mantém apenas o tick loop, a tomada de
 * decisão e a delegação para os componentes funcionais especializados
 * (MapResolver, NpcBoxMatcher, QuestGiverInteraction, SellingHandler,
 * ConfigMarker, QuestPanel, MissionMapLoader, QuestLogger). Todo o estado
 * mutável e as APIs ficam em {@link QuestContext}.
 */
@Feature(name = "Quest Module (Bypass)", description = "Completa quests automaticamente: navega mapas, mata NPCs, coleta loot")
public class QuestModule implements Module, Behavior, Configurable<QuestConfig>, InstructionProvider {

    private static final java.util.regex.Pattern COORDINATES_PATTERN = 
            java.util.regex.Pattern.compile("(\\d{3,5})\\s*[,/\\\\\\s]\\s*(\\d{3,5})");

    private final QuestContext ctx;
    private final QuestLogger logger;
    private final MissionMapLoader missionMapLoader;
    private final NpcBoxMatcher npcBoxMatcher;
    private final MapResolver mapResolver;
    private final QuestGiverInteraction questGiverInteraction;
    private final SellingHandler sellingHandler;
    private final ConfigMarker configMarker;
    private final QuestPanel questPanel;

    public QuestModule(PluginAPI api) {
        this.ctx = new QuestContext(api);
        this.logger = new QuestLogger(ctx);
        this.missionMapLoader = new MissionMapLoader(ctx, logger);
        this.npcBoxMatcher = new NpcBoxMatcher(ctx, logger);
        this.mapResolver = new MapResolver(ctx, logger, npcBoxMatcher, missionMapLoader);
        this.questGiverInteraction = new QuestGiverInteraction(ctx, logger, mapResolver);
        this.sellingHandler = new SellingHandler(ctx, logger, mapResolver);
        this.configMarker = new ConfigMarker(ctx, logger, mapResolver, missionMapLoader, npcBoxMatcher);
        this.questPanel = new QuestPanel(ctx, logger);

        // Registra os componentes no contexto para acesso cruzado
        ctx.logger = logger;
        ctx.missionMapLoader = missionMapLoader;
        ctx.npcBoxMatcher = npcBoxMatcher;
        ctx.mapResolver = mapResolver;
        ctx.questGiverInteraction = questGiverInteraction;
        ctx.sellingHandler = sellingHandler;
        ctx.configMarker = configMarker;
        ctx.questPanel = questPanel;

        // Load external mission->NPC mapping file (optional)
        missionMapLoader.loadExternalMissionMap();
        // Load persisted accepted quests cache (for secondary quests)
        questGiverInteraction.loadAcceptedQuestCacheFromFile();
    }

    @Override
    public void setConfig(ConfigSetting<QuestConfig> setting) {
        this.ctx.config = setting.getValue();
    }

    @Override
    public JComponent beforeConfig() {
        if (ctx.config != null && !ctx.config.showStatusPanel) {
            return null;
        }
        questPanel.updatePanel();
        return questPanel.getRootPanel();
    }

    // -------------------------------------------------------------------------
    // Tick Entrypoints (must run every tick for smooth movement)
    // -------------------------------------------------------------------------

    @Override
    public void onTickModule() {
        tickLogic();
    }

    @Override
    public void onTickBehavior() {
        // Avoid double execution when QuestModule is the active module
        if (ctx.botAPI.getModule() == this) return;
        tickLogic();
    }

    @Override
    public boolean canRefresh() {
        return true;
    }

    @Override
    public String getStatus() {
        return ctx.currentAction;
    }

    private void tickLogic() {
        if (ctx.config == null) return;
        // [QUEST_STATE] Ponto 9: INÍCIO do tick
        // try {
        //     Quest qIni = ctx.questAPI.getDisplayedQuest();
        //     GameMap hmIni = ctx.heroAPI.getMap();
        //     Integer wmIni = mapResolver.getCurrentWorkingMapId();
        //     System.out.println("==================== TICK INÍCIO ===================="
        //             + "\n  Quest=" + (qIni != null ? qIni.getTitle() : "null")
        //             + "\n  Requirement=" + (ctx.currentReq != null ? ctx.currentReq.getDescription() : "null")
        //             + "\n  targetMap=" + (ctx.targetMap != null ? ctx.targetMap.getName() : "null")
        //             + "\n  working_map=" + (wmIni != null ? wmIni : "null")
        //             + "\n  heroMap=" + (hmIni != null ? hmIni.getName() : "null")
        //             + "\n  module=" + (ctx.botAPI.getModule() != null ? ctx.botAPI.getModule().getClass().getSimpleName() : "null"));
        // } catch (Throwable t) {
        //     System.err.println("[QUEST_STATE] erro no log de inicio de tick: " + t);
        // }
        npcBoxMatcher.refreshCustomAliasesIfNeeded();

        if (ctx.questGui == null) {
            // IMPORTANTE: o DarkBot registra a janela do QuestGiver no GameScreenAPI
            // com o nome "quests" (PLURAL). O nome "quest" (singular) NAO existe,
            // entao getGui("quest") sempre retornava null e todos os cliques de
            // aceite caiam no vazio. Tentamos "quests" primeiro e, como fallback,
            // "quest" (caso algum build antigo use o singular).
            GameScreenAPI gsa = ctx.pluginAPI.requireAPI(GameScreenAPI.class);
            ctx.questGui = gsa.getGui("quests");
            if (ctx.questGui == null) {
                ctx.questGui = gsa.getGui("quest");
            }
            if (ctx.questGui == null) {
                logger.logDebug("[QuestModule] AVISO: nao foi possivel obter a GUI 'quests' do GameScreenAPI. "
                        + "O clique de aceite dependera do fallback de DarkInput (viewBounds).");
            } else {
                logger.logDebug("[QuestModule] GUI do QuestGiver obtida com sucesso (nome='quests').");
            }
        }

        long now = System.currentTimeMillis();
        questPanel.updatePanel();

        // Helper de diagnóstico: dumpe as GUIs visíveis (com bounds reais) enquanto a
        // janela do QuestGiver está aberta, para calibrar os offsets relativos
        // (listItemX/Y, acceptButtonX/Y) no client. Throttled internamente (5s).
        if (ctx.questGui != null && ctx.questGui.isVisible()) {
            questGiverInteraction.dumpVisibleGuis(now);
        }

        // Safety check: do NOT run quest movement/attack/navigation if bot is paused
        if (!ctx.botAPI.isRunning()) {
            ctx.botStartTime = 0L;
            return;
        }

        if (ctx.botStartTime == 0L) {
            ctx.botStartTime = now;
        }

        // TESTE ISOLADO DA CADEIA DE CLIQUE: se o modo de teste estiver ativo,


        // If quest cache is not initialized, fly to base and initialize it
        if (!ctx.questCacheInitialized) {
            questGiverInteraction.initializeQuestCache(now);
            return;
        }

        // Deliver completed quests instantly via backpage
        deliverCompletedQuests(now);

        // 2. Auto-sell when cargo hold is full (prevented during cargo ore quests or timed quests)
        if (ctx.config.loot.autoSellWhenFull && ctx.statsAPI.getCargo() >= ctx.statsAPI.getMaxCargo() && ctx.statsAPI.getMaxCargo() > 0) {
            Quest active = ctx.questAPI.getDisplayedQuest();
            boolean isOreQuest = QuestContext.questHasOreFromShipRequirement(active);
            boolean isTimedQuest = QuestContext.questHasTimer(active);
            if (!isOreQuest && !isTimedQuest) {
                ctx.isSellingCargo = true;
            }
        }
        // Safely clear selling state when cargo drops below threshold
        double clearLimit = ctx.statsAPI.getMaxCargo() * (ctx.config.loot.cargoClearThreshold / 100.0);
        if (ctx.isSellingCargo && ctx.statsAPI.getMaxCargo() > 0 && ctx.statsAPI.getCargo() <= clearLimit) {
            ctx.isSellingCargo = false;
            ctx.tradeWindowOpen = false;
            ctx.oreAPI.showTrade(false, null);
            logger.logDebug("Venda de cargo concluida (cargo abaixo do limite de " + (int)clearLimit + "). Retomando quests. Cargo atual: " + ctx.statsAPI.getCargo() + "/" + ctx.statsAPI.getMaxCargo());
        }

        if (ctx.isSellingCargo) {
            // CORREÇÃO DO PING-PONG: aplica cooldown simétrico na readoção do
            // QuestModule (reentrando do LootCollectorModule), reaproveitando
            // MODULE_SWITCH_STABILITY_MS. Sem isso, o tickLogic() rodando via
            // Behavior podia se readotar imediatamente a cada tick.
            if (ctx.botAPI.getModule() != this
                    && now - ctx.lastQuestReclaimTime >= QuestContext.MODULE_SWITCH_STABILITY_MS) {
                ctx.botAPI.setModule(this);
                ctx.lastQuestReclaimTime = now;
            }
            sellingHandler.handleSellingCargo(now);
            return;
        }

        // 3. Decision Making (Throttled to once per second) - run BEFORE navigation
        // so targetMap is fresh and the navigation check below uses the correct destination.
        long decisionInterval = QuestConfig.NavigationConfig.DECISION_INTERVAL_MS;
        boolean isLootCollectorActive = ctx.botAPI.getModule() == ctx.defaultLootCollectorModule;
        boolean forceDecision = !isLootCollectorActive && (ctx.targetNpcLastValidTime == 0 || ctx.targetBoxLastValidTime == 0);
        if (forceDecision || now - ctx.lastDecisionTime >= decisionInterval) {
            ctx.lastDecisionTime = now;
            makeDecision(now);
        }

        // CORREÇÃO RADICAL DO "PING-PONG": Se a GUI do QuestGiver está visível
        // no mapa base, PULAR toda a lógica de navegação, working_map e ação.
        // O makeDecision() já chamou autoAcceptNewQuest() se necessário.
        // Qualquer tentativa de navegar ou mudar working_map aqui interrompe o aceite.
        GameMap currentMap = ctx.heroAPI.getMap();
        GameMap homeMapForCheck = mapResolver.resolveHomeMap();
        boolean isOnHomeMapNow = homeMapForCheck != null && currentMap != null
                && currentMap.getId() == homeMapForCheck.getId();
        boolean isQuestGuiVisible = ctx.questGui != null && ctx.questGui.isVisible();

        if (isOnHomeMapNow && isQuestGuiVisible && QuestConfig.QuestFlowConfig.AUTO_ACCEPT) {
            if (ctx.acceptCycleComplete) {
                // CORREÇÃO DO TRAVAMENTO: quando o ciclo de aceite foi completado
                // (limite de missões atingido no cliente), NÃO podemos fazer
                // early-return aqui — isso deixava o bot travado na base com a janela
                // do QuestGiver aberta, sem nunca navegar para executar as missões.
                // Fechamos a janela e retornamos; no tick seguinte a GUI estará
                // fechada e o branch (4) abaixo deixa o fluxo cair para a navegação
                // normal (vai para o mapa da missão ativa).
                if (ctx.questGui != null) {
                    ctx.questGui.setVisible(false);
                }
                ctx.currentAction = "Limite de missoes atingido. Fechando janela e indo executar...";
                return;
            }
            // Apenas mantém o working_map na base e não faz mais nada
            mapResolver.updateBotWorkingMap(homeMapForCheck);
            // Garante que o autoAccept rode (caso o makeDecision não tenha chamado)
            if (now - ctx.lastAcceptAttemptTime >= QuestConfig.QuestFlowConfig.ACCEPT_INTERVAL_MS) {
                questGiverInteraction.autoAcceptNewQuest(now);
            }
            return;
        }

        // CORREÇÃO DO "PING-PONG" (2): Se a GUI do QuestGiver está visível mas o bot
        // NÃO está no mapa base (ex: abriu a janela em mapa errado), fecha a janela
        // e navega para a base antes de tentar aceitar. Isso evita o loop de
        // "abrir janela -> tentar aceitar -> falhar -> reabrir" no mapa errado.
        if (isQuestGuiVisible && !isOnHomeMapNow && QuestConfig.QuestFlowConfig.AUTO_ACCEPT) {
            if (ctx.questGui != null) {
                ctx.questGui.setVisible(false);
            }
            if (homeMapForCheck != null) {
                mapResolver.navigateToMap(homeMapForCheck, now);
            }
            return;
        }

        // CORREÇÃO DO "PING-PONG" (3): Se a GUI do QuestGiver NÃO está visível mas o
        // bot está no mapa base e ainda há quests para aceitar, chama autoAcceptNewQuest
        // para abrir a janela e começar o aceite. Isso garante que o bot não fique
        // parado na base sem fazer nada.
        if (isOnHomeMapNow && !isQuestGuiVisible && QuestConfig.QuestFlowConfig.AUTO_ACCEPT
                && !ctx.acceptCycleComplete) {
            questGiverInteraction.autoAcceptNewQuest(now);
            return;
        }

        // CORREÇÃO DO "PING-PONG" (4): Se o bot está no mapa base, a GUI NÃO está
        // visível, e o ciclo de aceite FOI completado (acceptCycleComplete = true),
        // sai para executar as quests (navega para o mapa da primeira quest ativa).
        // Isso evita o loop de "abrir janela -> fechar -> abrir de novo".
        if (isOnHomeMapNow && !isQuestGuiVisible && QuestConfig.QuestFlowConfig.AUTO_ACCEPT
                && ctx.acceptCycleComplete) {
            // Deixa o fluxo continuar para a navegação normal (bloco 4 abaixo)
            // que vai para o mapa da quest.
        }

        // 4. Map navigation check (now uses the freshly computed targetMap)
        boolean needsMapChange = ctx.targetMap != null && currentMap != null && currentMap.getId() != ctx.targetMap.getId();

        // 4b. Keep the bot's WORKING_MAP in sync with where the bot should operate.
        if (ctx.targetMap != null) {
            mapResolver.updateBotWorkingMap(ctx.targetMap);
            // [FLOW] 2) Após updateBotWorkingMap(targetMap)
            // Integer wmGravado = mapResolver.getCurrentWorkingMapId();
            // Integer wmLido = mapResolver.getCurrentWorkingMapId();
            // System.out.println("[FLOW] updateBotWorkingMap:"
            //         + "\n  gravado=" + (ctx.targetMap != null ? ctx.targetMap.getId() : "null")
            //         + "\n  general.working_map=" + (wmGravado != null ? wmGravado : "null")
            //         + "\n  ctx.targetMap=" + (ctx.targetMap != null ? ctx.targetMap.getName() : "null")
            //         + "\n  igual_ao_gravado=" + (ctx.targetMap != null && wmLido != null && wmLido == ctx.targetMap.getId()));
        } else if (ctx.currentReq != null) {
            if (currentMap != null) {
                mapResolver.updateBotWorkingMap(currentMap);
            }
        } else {
            mapResolver.restoreBotWorkingMap();
        }

        if (needsMapChange) {
            // CORREÇÃO DO PING-PONG: aplica cooldown simétrico na readoção do
            // QuestModule (reentrando do LootCollectorModule), reaproveitando
            // MODULE_SWITCH_STABILITY_MS. Sem isso, uma leitura passageira/instável
            // de ctx.targetMap fazia o setModule(this) disparar na hora, sem checar
            // lastCollectorSwitchTime, causando o ping-pong com o LootCollector.
            if (ctx.botAPI.getModule() != this
                    && now - ctx.lastQuestReclaimTime >= QuestContext.MODULE_SWITCH_STABILITY_MS) {
                ctx.botAPI.setModule(this);
                ctx.lastQuestReclaimTime = now;
            }
            logger.logDebug("navigator recebeu: targetMap="
                    + (ctx.targetMap != null ? ctx.targetMap.getName() : "null")
                    + " | currentMap=" + (currentMap != null ? currentMap.getName() : "null")
                    + " | needsMapChange=" + needsMapChange);
            mapResolver.navigateToMap(ctx.targetMap, now);
            return;
        }

        // 5. Update Configurations dynamically
        configMarker.updateConfigForQuest(ctx.questAPI.getDisplayedQuest(), ctx.targetMap);

        // 6. If the current requirement is not a kill or loot type, switch back to QuestModule
        if (ctx.currentReq == null || (!isKillType(ctx.currentReq.getRequirementType()) && !isLootType(ctx.currentReq.getRequirementType()))) {
            // CORREÇÃO DO PING-PONG: aplica cooldown simétrico na readoção do
            // QuestModule (reentrando do LootCollectorModule), reaproveitando
            // MODULE_SWITCH_STABILITY_MS.
            if (ctx.botAPI.getModule() != this
                    && now - ctx.lastQuestReclaimTime >= QuestContext.MODULE_SWITCH_STABILITY_MS) {
                ctx.botAPI.setModule(this);
                ctx.lastQuestReclaimTime = now;
            }
        }

        // Re-adopt QuestModule if we are in LootCollectorModule but an enemy player is nearby for PVP quest
        if (ctx.currentReq != null && isPvpType(ctx.currentReq.getRequirementType()) && ctx.botAPI.getModule() != this) {
            if (hasEnemyPlayerNearby()) {
                ctx.botAPI.setModule(this);
                ctx.lastQuestReclaimTime = now;
            }
        }

        // 7. Action Execution (if QuestModule is the active module)
        if (ctx.botAPI.getModule() == this) {
            executeAction(now);
        }

        // [FLOW-TICK] Resumo consolidado do estado ao final de cada tick
        // GameMap heroMapTick = ctx.heroAPI.getMap();
        // Integer wmTick = mapResolver.getCurrentWorkingMapId();
        // System.out.println("[FLOW-TICK]"
        //         + "\n  targetMap=" + (ctx.targetMap != null ? ctx.targetMap.getName() : "null")
        //         + "\n  working_map=" + (wmTick != null ? wmTick : "null")
        //         + "\n  module=" + (ctx.botAPI.getModule() != null ? ctx.botAPI.getModule().getClass().getSimpleName() : "null")
        //         + "\n  quest=" + (ctx.questAPI.getDisplayedQuest() != null ? ctx.questAPI.getDisplayedQuest().getTitle() : "null")
        //         + "\n  currentReq=" + (ctx.currentReq != null ? ctx.currentReq.getDescription() : "null")
        //         + "\n  heroMap=" + (heroMapTick != null ? heroMapTick.getName() : "null"));

        // [QUEST_STATE] Ponto 9: FIM do tick
        // try {
        //     Quest qFim = ctx.questAPI.getDisplayedQuest();
        //     GameMap hmFim = ctx.heroAPI.getMap();
        //     Integer wmFim = mapResolver.getCurrentWorkingMapId();
        //     System.out.println("==================== TICK FIM ===================="
        //             + "\n  Quest=" + (qFim != null ? qFim.getTitle() : "null")
        //             + "\n  Requirement=" + (ctx.currentReq != null ? ctx.currentReq.getDescription() : "null")
        //             + "\n  targetMap=" + (ctx.targetMap != null ? ctx.targetMap.getName() : "null")
        //             + "\n  working_map=" + (wmFim != null ? wmFim : "null")
        //             + "\n  heroMap=" + (hmFim != null ? hmFim.getName() : "null")
        //             + "\n  module=" + (ctx.botAPI.getModule() != null ? ctx.botAPI.getModule().getClass().getSimpleName() : "null"));
        // } catch (Throwable t) {
        //     System.err.println("[QUEST_STATE] erro no log de fim de tick: " + t);
        // }
    }

    private void makeDecision(long now) {
        GameMap oldTargetMap = ctx.stableTargetMap;
        makeDecisionInternal(now);
        GameMap calculatedTargetMap = ctx.targetMap;

        if (calculatedTargetMap != oldTargetMap) {
            if (oldTargetMap == null && ctx.tempTargetMap == null) {
                ctx.stableTargetMap = calculatedTargetMap;
                ctx.tempTargetMap = calculatedTargetMap;
                ctx.targetMap = calculatedTargetMap;
            } else {
                if (calculatedTargetMap != ctx.tempTargetMap) {
                    ctx.tempTargetMap = calculatedTargetMap;
                    ctx.targetMapChangeTime = now;
                } else if (now - ctx.targetMapChangeTime >= 2000) {
                    ctx.stableTargetMap = calculatedTargetMap;
                    ctx.targetMap = calculatedTargetMap;
                } else {
                    ctx.targetMap = oldTargetMap;
                }
            }
        } else {
            ctx.tempTargetMap = calculatedTargetMap;
        }
    }

    private void makeDecisionInternal(long now) {
        // [QUEST_STATE] Log de entrada (instrumentação p/ investigar regressão de perda de estado)
        // try {
        //     Quest displayedQuest = ctx.questAPI.getDisplayedQuest();
        //     QuestListItem selectedQuestInfo = ctx.questAPI.getSelectedQuestInfo();
        //     Quest selectedQuest = ctx.questAPI.getSelectedQuest();
        //     System.out.println("[QUEST_STATE] ENTRADA makeDecision"
        //             + "\n  displayedQuest=" + (displayedQuest != null ? displayedQuest.getTitle() : "null")
        //             + "\n  ctx.currentQuest=(inexistente - proxy=displayedQuest)"
        //             + "\n  ctx.currentReq=" + (ctx.currentReq != null ? ctx.currentReq.getDescription() : "null")
        //             + "\n  acceptedQuest=" + (selectedQuestInfo != null ? selectedQuestInfo.getTitle() : "null")
        //             + "\n  selectedQuest=" + (selectedQuest != null ? selectedQuest.getTitle() : "null")
        //             + "\n  questTitle=" + (displayedQuest != null ? displayedQuest.getTitle() : "null")
        //             + "\n  hashCode(quest)=" + (displayedQuest != null ? System.identityHashCode(displayedQuest) : "null")
        //             + "\n  ctx.targetMap=" + (ctx.targetMap != null ? ctx.targetMap.getName() : "null"));
        // } catch (Throwable t) {
        //     System.err.println("[QUEST_STATE] erro no log de entrada: " + t);
        // }

        Quest quest = ctx.questAPI.getDisplayedQuest();

        // CORREÇÃO: Se estamos no mapa base e o auto-accept está ativo, NÃO retornar
        // precocemente mesmo com quest ativa — precisamos continuar aceitando até
        // atingir o limite maxQuestsPerMap. Antes, o return na linha 266 impedia
        // o bloco autoAccept de rodar, fazendo o bot aceitar apenas 1 quest por ciclo.
        boolean isOnHomeMap = false;
        GameMap homeMap = mapResolver.resolveHomeMap();
        if (homeMap != null) {
            GameMap currentMap = ctx.heroAPI.getMap();
            isOnHomeMap = currentMap != null && currentMap.getId() == homeMap.getId();
        }

        // CORREÇÃO: Se estamos no mapa base com auto-accept ativo e ainda não
        // atingimos o limite de quests, NÃO setar targetMap nem manter currentReq.
        // Senão o executeAction() tenta delegar para o LootCollectorModule (fazendo
        // o bot sair andando) ou o tickLogic() tenta navegar para o mapa da quest,
        // interrompendo o aceite e causando o "vai-e-volta" infinito.
        //
        // CORREÇÃO DO "PING-PONG": Quando o limite é atingido, setamos
        // acceptCycleComplete = true. Isso impede que o bot tente aceitar de novo
        // no mesmo local. A flag só é resetada quando:
        //   - A quest ativa é completada (quest == null ou isCompleted)
        //   - OU o bot não está mais no mapa base (saiu para executar)
        boolean shouldAcceptMore = isOnHomeMap && QuestConfig.QuestFlowConfig.AUTO_ACCEPT
                && ctx.acceptedThisCycle < QuestConfig.QuestFlowConfig.MAX_QUESTS_PER_MAP
                && !ctx.acceptCycleComplete;

        // If HUD quest is active and not completed
        if (quest != null && quest.isActive() && !quest.isCompleted()) {
            // [QUEST_STATE] Ponto 2: antes de atribuir ctx.currentReq
            Requirement oldCurrentReq = ctx.currentReq;
            ctx.currentReq = findBestRequirement(quest);
            ctx.traceCurrentReqChange(oldCurrentReq, ctx.currentReq, "QuestModule", "makeDecision", 357);
            // System.out.println("[QUEST_STATE] ATRIB ctx.currentReq"
            //         + "\n  valor_antigo=" + (oldCurrentReq != null ? oldCurrentReq.getDescription() : "null")
            //         + "\n  valor_novo=" + (ctx.currentReq != null ? ctx.currentReq.getDescription() : "null")
            //         + "\n  origem=makeDecision() QuestModule (findBestRequirement)"
            //         + "\n  stack=" + "makeDecision:linha~" + (new Throwable().getStackTrace().length));
            if (ctx.currentReq != null) {
                String questTitle = quest.getTitle() != null ? quest.getTitle() : "";
                if (!questTitle.equals(ctx.lastQuestTitle)) {
                    ctx.lastQuestTitle = questTitle;
                    ctx.lastQuestId = quest.getId();
                    ctx.lastProgressValue = -1;
                    ctx.lastProgressTime = now;
                    GameMap oldTarget = ctx.targetMap;
                    ctx.targetMap = null;
                    ctx.traceTargetMapChange(oldTarget, null, quest.getTitle(), 
                        ctx.currentReq != null ? ctx.currentReq.getDescription() : "null",
                        "makeDecision", "QuestModule", 357);
                }

                double progress = ctx.currentReq.getProgress();
                if (progress != ctx.lastProgressValue) {
                    ctx.lastProgressValue = progress;
                    ctx.lastProgressTime = now;
                }

                if (shouldAcceptMore) {
                    // Estamos na base aceitando: limpa tudo para não executar ação
                    GameMap oldTarget2 = ctx.targetMap;
                    ctx.targetMap = null;
                    ctx.traceTargetMapChange(oldTarget2, null, quest.getTitle(), 
                        ctx.currentReq != null ? ctx.currentReq.getDescription() : "null",
                        "makeDecision", "QuestModule", 368);
                    Requirement oldReq368 = ctx.currentReq;
                    ctx.currentReq = null;
                    ctx.traceCurrentReqChange(oldReq368, null, "QuestModule", "makeDecision", 368);
                } else {
                    // CORREÇÃO (minérios de carga): se a quest ativa pede Prometid/
                    // Duranium/Promerium (só vêm de caixas from_ship), o bot deve ir
                    // ao mapa configurado em ore_from_ship.collect_map e matar TODOS
                    // os NPCs do mapa. Sobrescreve o targetMap pela escolha do usuário
                    // em vez de tentar deduzir o mapa pela descrição (que não cita mapa).
                    // Se for missão de minério de carga / venda de minério de nave (from_ship),
                    // respeita SEMPRE a preferência do mapa configurado pelo usuário.
                    // Só tenta resolver de forma automática se não houver mapa configurado.
                    boolean isOreFromShip = QuestContext.questHasOreFromShipRequirement(quest);
                    GameMap resolvedMap = null;
                    
                    if (isOreFromShip && ctx.config != null
                            && ctx.config.oreFromShip != null
                            && ctx.config.oreFromShip.collectMap != null
                            && !ctx.config.oreFromShip.collectMap.trim().isEmpty()) {
                        GameMap oldTarget2 = ctx.targetMap;
                        GameMap oreMap = ctx.starSystemAPI.findMap(ctx.config.oreFromShip.collectMap.trim()).orElse(null);
                        if (oreMap != null) {
                            ctx.targetMap = oreMap;
                            ctx.traceTargetMapChange(oldTarget2, ctx.targetMap, quest.getTitle(),
                                ctx.currentReq != null ? ctx.currentReq.getDescription() : "null",
                                "makeDecision", "QuestModule", 371);
                            logger.logDebug("[OreFromShip] usando mapa configurado pelo usuario: " + oreMap.getName());
                        } else {
                            resolvedMap = mapResolver.resolveQuestTargetMap(quest, ctx.currentReq);
                        }
                    } else {
                        resolvedMap = mapResolver.resolveQuestTargetMap(quest, ctx.currentReq);
                    }
                    
                    if (resolvedMap != null) {
                        GameMap oldTarget2 = ctx.targetMap;
                        ctx.targetMap = resolvedMap;
                        ctx.traceTargetMapChange(oldTarget2, ctx.targetMap, quest.getTitle(),
                            ctx.currentReq != null ? ctx.currentReq.getDescription() : "null",
                            "makeDecision", "QuestModule", 371);
                    } else if (!isOreFromShip) {
                        GameMap oldTarget2 = ctx.targetMap;
                        ctx.targetMap = null;
                        ctx.traceTargetMapChange(oldTarget2, null, quest.getTitle(),
                            ctx.currentReq != null ? ctx.currentReq.getDescription() : "null",
                            "makeDecision", "QuestModule", 371);
                    }
                    logger.logDebug("resolveTargetMap retornou: "
                            + (ctx.targetMap != null ? ctx.targetMap.getName() : "null")
                            + " | para req=" + (ctx.currentReq != null ? ctx.currentReq.getDescription() : "null"));

                    // [FLOW] 1) Após calcular ctx.targetMap em makeDecision
                    // GameMap heroMapFlow = ctx.heroAPI.getMap();
                    // Integer wmFlow = mapResolver.getCurrentWorkingMapId();
                    // System.out.println("[FLOW] makeDecision:"
                    //         + "\n  quest=" + (quest != null ? quest.getTitle() : "null")
                    //         + "\n  currentReq=" + (ctx.currentReq != null ? ctx.currentReq.getDescription() : "null")
                    //         + "\n  ctx.targetMap=" + (ctx.targetMap != null ? ctx.targetMap.getName() : "null")
                    //         + "\n  heroMap=" + (heroMapFlow != null ? heroMapFlow.getName() : "null")
                    //         + "\n  working_map_atual=" + (wmFlow != null ? wmFlow : "null"));
                }

                if (!shouldAcceptMore) {
                    return;
                }
                // Se chegou aqui: estamos na base com auto-accept ativo e ainda
                // não atingimos o limite. Continua para o bloco autoAccept abaixo.
            }
        }

        // CORREÇÃO DO "PING-PONG": Reseta acceptCycleComplete quando a quest ativa
        // é completada ou não existe mais (permite novo ciclo de aceite).
        // MAS: só reseta se NÃO houver mais quests aceitas no cache para executar.
        // Se ainda tem quests aceitas, o bot deve ir executá-las, não voltar ao Quest Giver.
        if (quest == null || !quest.isActive() || quest.isCompleted()) {
            java.util.List<? extends QuestListItem> allQuests = ctx.questAPI.getCurrestQuests();
            if (allQuests != null) {
                questGiverInteraction.cacheAcceptedQuestTitles(allQuests);
            }
            // Conta quantas quests aceitas (não completadas) ainda existem
            int activeAccepted = 0;
            if (allQuests != null) {
                for (QuestListItem q : allQuests) {
                    if (q != null && !q.isCompleted() && !q.isActivable()) {
                        activeAccepted++;
                    }
                }
            }
            if (ctx.acceptCycleComplete && activeAccepted == 0) {
                ctx.acceptCycleComplete = false;
                ctx.acceptFailStreak = 0;
                logger.logDebug("acceptCycleComplete resetado (todas as quests aceitas foram concluidas)");
            } else if (ctx.acceptCycleComplete && activeAccepted > 0) {
                logger.logDebug("Quest completada mas ainda ha " + activeAccepted
                        + " quests aceitas. Mantendo acceptCycleComplete=true para executar as restantes.");
            }
        }

        // CORREÇÃO DO "PING-PONG": Se não estamos mais no mapa base, reseta a flag
        // para permitir novo ciclo de aceite quando voltar.
        if (!isOnHomeMap && ctx.acceptCycleComplete) {
            ctx.acceptCycleComplete = false;
            ctx.acceptFailStreak = 0;
            logger.logDebug("acceptCycleComplete resetado (saiu do mapa base)");
        }

        // No accepted quests left
        // [QUEST_STATE] Ponto 4: antes de atribuir ctx.targetMap = null (fim do makeDecision)
        GameMap oldTarget3 = ctx.targetMap;
        // System.out.println("[QUEST_STATE] ATRIB ctx.targetMap"
        //         + "\n  valor_antigo=" + (oldTarget3 != null ? oldTarget3.getName() : "null")
        //         + "\n  valor_novo=null"
        //         + "\n  quem_alterou=makeDecision() (fim, 'Nenhum requirement'/sem quest ativa)");
        Requirement oldReqFim = ctx.currentReq;
        ctx.currentReq = null;
        ctx.traceCurrentReqChange(oldReqFim, null, "QuestModule", "makeDecision", 408);
        ctx.targetMap = null;
        ctx.traceTargetMapChange(oldTarget3, null, quest != null ? quest.getTitle() : "null", 
            "Nenhum requirement", "makeDecision", "QuestModule", 408);

        if (quest != null && quest.isCompleted()) {
            ctx.currentAction = "Quest '" + quest.getTitle() + "' completa! Aceitando proxima...";
            ctx.lastAcceptAttemptTime = 0L;
            if (activateAnotherAcceptedQuest()) {
                return;
            }
        }

        // Continua aceitando quests enquanto houver candidatas visíveis na GUI e o
        // limite de quests por mapa (maxQuestsPerMap) não foi atingido. Isso evita o
        // bug de "aceitou só uma e parou": após a 1ª aceita, ela vira a quest ativa
        // (getDisplayedQuest), mas ainda queremos encher a fila até o limite antes de
        // sair para executar.
        //
        // IMPORTANTE: o contador de aceitas NÃO usa getCurrestQuests() (essa lista só
        // traz as quests DISPONÍVEIS para aceitar — as já aceitas somem dela, e contar
        // !isActivable() nela dava 75/5). Usamos ctx.acceptedThisCycle, incrementado
        // em verifyPendingAccept() a cada aceite confirmado.
        if (QuestConfig.QuestFlowConfig.AUTO_ACCEPT) {
            int acceptedCount = ctx.acceptedThisCycle;
            int limit = QuestConfig.QuestFlowConfig.MAX_QUESTS_PER_MAP;
            // CORREÇÃO: só aceita mais se o ciclo de aceite ainda NÃO foi
            // encerrado (acceptCycleComplete == false). O contador acceptedThisCycle
            // é zerado dentro de verifyPendingAccept() quando o limite é atingido
            // (junto com acceptCycleComplete=true), então a checagem "acceptedCount <
            // limit" sozinha ficava 0 < 5 (verdadeira) e o bot continuava chamando
            // autoAcceptNewQuest após já ter aceito as 5 missões, indo para a próxima
            // linha e tentando aceitar mais. A flag acceptCycleComplete é a fonte
            // autoritativa de que o limite foi atingido neste ciclo.
            if (acceptedCount < limit && !ctx.acceptCycleComplete) {
                questGiverInteraction.autoAcceptNewQuest(now);
            } else {
                // Limite atingido: seta a flag para evitar re-aceite e sai para executar.
                ctx.acceptCycleComplete = true;
                ctx.acceptedThisCycle = 0;
                ctx.currentAction = "Limite de quests atingido (" + limit + "/" + limit
                        + "). Saindo para executar...";
            }
        } else {
            ctx.currentAction = "Sem quest ativa. Auto-accept desativado.";
        }
    }

    private void executeAction(long now) {
        if (ctx.currentReq == null) {
            return;
        }

        RequirementType type = ctx.currentReq.getRequirementType();

        // 1. Handle selling ores
        if (type == RequirementType.SELL_ORE) {
            sellingHandler.handleSellOre(ctx.currentReq, now);
            return;
        }

        // 2. Handle spending ammunition
        if (type == RequirementType.SPEND_AMMUNITION) {
            handleSpendAmmunition(now);
            return;
        }

        // 3. Handle killing players (PVP)
        if (isPvpType(type) && ctx.config != null && ctx.config.pvp.autoPvp) {
            handlePvpKill(now);
            return;
        }

        // 4. Handle map navigation
        GameMap currentMap = ctx.heroAPI.getMap();
        if (ctx.targetMap != null && currentMap != null && currentMap.getId() != ctx.targetMap.getId()) {
            logger.logDebug("navigator (executeAction) recebeu: targetMap="
                    + (ctx.targetMap != null ? ctx.targetMap.getName() : "null")
                    + " | currentMap=" + (currentMap != null ? currentMap.getName() : "null"));
            mapResolver.navigateToMap(ctx.targetMap, now);
            return;
        }

        // 4.5 Handle coordinate / travel / proximity / distance requirements specifically
        if (type == RequirementType.COORDINATES || type == RequirementType.TRAVEL 
                || type == RequirementType.PROXIMITY || type == RequirementType.DISTANCE) {
            String desc = ctx.currentReq.getDescription();
            if (desc != null && !desc.isEmpty()) {
                java.util.regex.Matcher m = COORDINATES_PATTERN.matcher(desc);
                if (m.find()) {
                    try {
                        double targetX = Double.parseDouble(m.group(1));
                        double targetY = Double.parseDouble(m.group(2));
                        
                        double dist = ctx.heroAPI.distanceTo(targetX, targetY);
                        if (dist > 300) {
                            ctx.setShipMode("roam");
                            ctx.movementAPI.moveTo(targetX, targetY);
                            ctx.currentAction = "[Quest] Voando para coordenadas: " + (int)targetX + ", " + (int)targetY + " (dist: " + (int)dist + ")";
                            return;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        // 5. DELEGAÇÃO ANTECIPADA: Delega para LootCollectorModule para TODOS os tipos
        // de requisito EXCETO os que precisam de lógica específica do QuestModule.
        // Isso permite que o LootCollector use suas próprias configurações de ofensiva,
        // velocidade, percurso e distância de ataque (do perfil atual).
        if (type != RequirementType.SELL_ORE
                && type != RequirementType.SPEND_AMMUNITION
                && !isPvpType(type)) {
            // [FLOW] 3) Antes de delegar para o LootCollectorModule
            // GameMap heroMapFlow3 = ctx.heroAPI.getMap();
            // Integer wmFlow3 = mapResolver.getCurrentWorkingMapId();
            // System.out.println("[FLOW] setModule(LootCollector):"
            //         + "\n  module_atual=" + (ctx.botAPI.getModule() != null ? ctx.botAPI.getModule().getClass().getSimpleName() : "null")
            //         + "\n  module_destino=LootCollectorModule"
            //         + "\n  ctx.targetMap=" + (ctx.targetMap != null ? ctx.targetMap.getName() : "null")
            //         + "\n  working_map=" + (wmFlow3 != null ? wmFlow3 : "null")
            //         + "\n  heroMap=" + (heroMapFlow3 != null ? heroMapFlow3.getName() : "null")
            //         + "\n  quest=" + (ctx.questAPI.getDisplayedQuest() != null ? ctx.questAPI.getDisplayedQuest().getTitle() : "null"));
            if (ctx.botAPI.getModule() != ctx.defaultLootCollectorModule
                    && now - ctx.lastCollectorSwitchTime >= QuestContext.MODULE_SWITCH_STABILITY_MS) {
                ctx.botAPI.setModule(ctx.defaultLootCollectorModule);
                ctx.lastCollectorSwitchTime = now;
            }
            return;
        }

        // 6. Delegação genérica: se chegou aqui, é um tipo não tratado.
        // O LootCollectorModule já está ativo e vai lidar com isso.
        ctx.currentAction = "[Quest] Em andamento (" + type + ")...";
    }

    private boolean isPvpType(RequirementType t) {
        return t == RequirementType.KILL_PLAYERS
                || t == RequirementType.DAMAGE_PLAYERS
                || t == RequirementType.DAMAGE_ENEMY_PLAYERS
                || t == RequirementType.KILL_ANY;
    }

    private long lastAmmoChangeTime = 0L;
    private boolean ammoChanged = false;

    private void handleSpendAmmunition(long now) {
        if (ctx.config == null) {
            ctx.currentAction = "[Ammo] Configs indisponíveis.";
            return;
        }

        // Need to be on the correct map first
        GameMap currentMap = ctx.heroAPI.getMap();
        if (ctx.targetMap != null && currentMap != null && currentMap.getId() != ctx.targetMap.getId()) {
            mapResolver.navigateToMap(ctx.targetMap, now);
            return;
        }

        // Change to expensive laser to spend ammo faster
        if (!ammoChanged && now - lastAmmoChangeTime > 2000) {
            lastAmmoChangeTime = now;
            ammoChanged = true;
            // Note: heroAPI has methods to change ammo, but exact API varies.
            // If available: heroAPI.setLaser(4);
            ctx.currentAction = "[Ammo] Trocando para laser L4 para gastar munição...";
        }

        // Delegate to LootCollectorModule to shoot at NPCs
        if (ctx.config.autoKillNpc && isKillType(RequirementType.KILL_NPC)) {
            if (ctx.botAPI.getModule() != ctx.defaultLootCollectorModule
                    && now - ctx.lastCollectorSwitchTime >= QuestContext.MODULE_SWITCH_STABILITY_MS) {
                ctx.botAPI.setModule(ctx.defaultLootCollectorModule);
                ctx.lastCollectorSwitchTime = now;
            }
            ctx.currentAction = "[Ammo] Gastando munição em NPCs...";
        } else {
            // Sem auto-kill: apenas marca que está gastando munição
            // O movimento fica a cargo do LootCollectorModule se estiver ativo
            ctx.currentAction = "[Ammo] Gastando munição...";
        }
    }

    private void handlePvpKill(long now) {
        if (ctx.config == null || !ctx.config.pvp.autoPvp) {
            ctx.currentAction = "[PVP] Auto-PVP desativado.";
            return;
        }

        // Need to be on the correct map first
        GameMap currentMap = ctx.heroAPI.getMap();
        if (ctx.targetMap != null && currentMap != null && currentMap.getId() != ctx.targetMap.getId()) {
            mapResolver.navigateToMap(ctx.targetMap, now);
            return;
        }

        // Find nearby enemy players and attack
        java.util.Collection<? extends Player> players = ctx.entitiesAPI.getPlayers();
        if (players != null && !players.isEmpty()) {
            Player target = null;
            double minDist = Double.MAX_VALUE;

            for (Player p : players) {
                if (p == null || p.getEntityInfo() == null) continue;
                // Só ataca inimigos (outra facção / guerra). Evita atacar aliados
                // (o que daria penalidade e não contaria na quest de kill players).
                if (!p.getEntityInfo().isEnemy()) continue;
                double dist = p.distanceTo(ctx.heroAPI);
                if (dist < minDist && dist < 1000) {
                    minDist = dist;
                    target = p;
                }
            }

            if (target != null) {
                double dist = target.distanceTo(ctx.heroAPI);
                if (dist > 500) {
                    ctx.setShipMode("roam");
                    ctx.movementAPI.moveTo(target);
                    ctx.currentAction = "[PVP] Aproximando de jogador: " + target.getEntityInfo().getUsername();
                } else {
                    ctx.setShipMode("attack");
                    ctx.attackAPI.setTarget(target);
                    tryLockAndAttackPvp(target, ctx.config != null ? ctx.config.pvp.pvpAmmoKey : null);
                    ctx.currentAction = "[PVP] Atacando jogador: " + target.getEntityInfo().getUsername();
                }
                return;
            }
        }

        // No players found: marca que está procurando
        // O movimento fica a cargo do LootCollectorModule se estiver ativo
        ctx.currentAction = "[PVP] Procurando jogadores...";

        // DELEGAÇÃO: Se não há players por perto, delega para o LootCollectorModule
        // para que a nave se mova e colete caixas pelo mapa enquanto procura inimigos.
        if (ctx.botAPI.getModule() != ctx.defaultLootCollectorModule
                && now - ctx.lastCollectorSwitchTime >= QuestContext.MODULE_SWITCH_STABILITY_MS) {
            ctx.botAPI.setModule(ctx.defaultLootCollectorModule);
            ctx.lastCollectorSwitchTime = now;
            // System.out.println("[PVP] Nenhum inimigo por perto. Delegando para LootCollectorModule para patrulhar/roaming.");
        }
    }

    private void deliverCompletedQuests(long now) {
        if (ctx.config == null || !QuestConfig.QuestFlowConfig.AUTO_DELIVER) return;
        if (now - ctx.lastDeliverCheckTime < 5000) return;
        ctx.lastDeliverCheckTime = now;

        // As quests se auto-completam in-game (o DarkOrbit entrega sozinho ao finalizar).
        // Aqui apenas limpamos o cache de titulos das quests que ja nao estao ativas,
        // para nao deixar lixo e nao depender do backpage (que esta obsoleto/quebrado).
        Quest displayed = ctx.questAPI.getDisplayedQuest();
        if (displayed != null && displayed.isCompleted()) {
            if (ctx.acceptedQuestTitleCache.remove(displayed.getId()) != null) {
                questGiverInteraction.saveAcceptedQuestCacheToFile();
            }
        }

        java.util.List<? extends QuestListItem> quests = ctx.questAPI.getCurrestQuests();
        if (quests != null) {
            questGiverInteraction.cacheAcceptedQuestTitles(quests);
        }
    }

    private boolean activateAnotherAcceptedQuest() {
        // As quests aceitas ficam ativas automaticamente no cliente apos completar a anterior.
        // Nao e necessario (nem possivel via backpage) ativar manualmente. Apenas sinalizamos
        // que nao ha acao necessaria aqui.
        return false;
    }

    private Requirement findBestRequirement(Quest quest) {
        java.util.List<? extends Requirement> reqs = quest.getRequirements();
        if (reqs == null || reqs.isEmpty()) {
            // [QUEST_STATE] Ponto 6: getCurrentRequirement retornou null (sem requirements)
            // System.out.println("[QUEST_STATE] getCurrentRequirement(retorno)"
            //         + "\n  retorno=null"
            //         + "\n  descricao=(lista vazia/nula)"
            //         + "\n  tipo=(indisponivel)");
            return null;
        }
        for (RequirementType preferred : QuestContext.PRIORITY) {
            for (Requirement req : reqs) {
                if (req.isEnabled() && !req.isCompleted()
                        && req.getRequirementType() == preferred) {
                    // [QUEST_STATE] Ponto 6: getCurrentRequirement retornou requirement
                    // System.out.println("[QUEST_STATE] getCurrentRequirement(retorno)"
                    //         + "\n  retorno=" + (req.getDescription() != null ? req.getDescription() : "null")
                    //         + "\n  descricao=" + (req.getDescription() != null ? req.getDescription() : "null")
                    //         + "\n  tipo=" + req.getRequirementType());
                    return req;
                }
            }
        }
        Requirement fallback = reqs.stream()
                .filter(r -> r.isEnabled() && !r.isCompleted())
                .findFirst().orElse(null);
        // [QUEST_STATE] Ponto 6: getCurrentRequirement retornou requirement (fallback)
        // System.out.println("[QUEST_STATE] getCurrentRequirement(retorno)"
        //         + "\n  retorno=" + (fallback != null ? fallback.getDescription() : "null")
        //         + "\n  descricao=" + (fallback != null ? fallback.getDescription() : "null")
        //         + "\n  tipo=" + (fallback != null ? fallback.getRequirementType() : "null"));
        return fallback;
    }

    private boolean isKillType(RequirementType t) {
        return t == RequirementType.KILL_NPC
                || t == RequirementType.KILL_NPCS
                || t == RequirementType.DAMAGE_NPCS
                || t == RequirementType.KILL_ANY;
    }

    private boolean isLootType(RequirementType t) {
        return t == RequirementType.COLLECT_LOOT
                || t == RequirementType.COLLECT_BONUS_BOX
                || t == RequirementType.COLLECT_BONUS_BOX_TYPE
                || t == RequirementType.COLLECT
                || t == RequirementType.CARGO;
    }

    private boolean hasEnemyPlayerNearby() {
        java.util.Collection<? extends eu.darkbot.api.game.entities.Player> players = ctx.entitiesAPI.getPlayers();
        if (players == null || players.isEmpty()) return false;
        for (eu.darkbot.api.game.entities.Player p : players) {
            if (p == null || p.getEntityInfo() == null) continue;
            if (!p.getEntityInfo().isEnemy()) continue;
            if (p.distanceTo(ctx.heroAPI) < 1000) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onTickStopped() {
        // Quando o bot é parado, limpamos todas as marcações de NPC/box temporárias 
        // para não deixar resíduos na configuração e evitar que fiquem marcados para sempre.
        configMarker.unmarkAll();
        // Reseta o estado de diárias para reprocessar na próxima execução
        questGiverInteraction.resetDailyMissionsState();
    }

    private void tryLockAndAttackPvp(Player target, Character ammoKey) {
        if (target == null) return;
        
        // 1. Mira no alvo
        if (!ctx.attackAPI.isLocked()) {
            ctx.attackAPI.tryLockTarget();
            return;
        }
        
        // 2. Alvo mirado: seleciona a munição de PVP via teclado
        if (ammoKey != null) {
            ctx.coreApi.keyboardClick(ammoKey);
        }
        
        // 3. Ataca
        if (!ctx.attackAPI.isAttacking()) {
            ctx.heroAPI.triggerLaserAttack();
        }
    }
}