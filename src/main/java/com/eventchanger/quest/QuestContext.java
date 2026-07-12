package com.eventchanger.quest;

import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.DarkInput;
import eu.darkbot.api.game.entities.Portal;
import eu.darkbot.api.game.other.GameMap;
import eu.darkbot.api.game.other.Gui;
import eu.darkbot.api.managers.AttackAPI;
import eu.darkbot.api.managers.BotAPI;
import eu.darkbot.api.managers.ConfigAPI;
import eu.darkbot.api.managers.EntitiesAPI;
import eu.darkbot.api.managers.HeroAPI;
import eu.darkbot.api.managers.MovementAPI;
import eu.darkbot.api.managers.OreAPI;
import eu.darkbot.api.managers.QuestAPI;
import eu.darkbot.api.managers.StarSystemAPI;
import eu.darkbot.api.managers.StatsAPI;
import eu.darkbot.api.managers.QuestAPI.Requirement;
import eu.darkbot.api.managers.QuestAPI.Requirement.RequirementType;
import eu.darkbot.shared.modules.LootCollectorModule;
import com.github.manolo8.darkbot.Main;
import com.github.manolo8.darkbot.core.IDarkBotAPI;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holder compartilhado de todas as APIs do DarkBot, da referência de config e de
 * todo o estado mutável que antes vivia espalhado dentro de {@link QuestModule}.
 * Cada componente funcional ({@code MapResolver}, {@code NpcBoxMatcher}, etc.)
 * recebe um {@code QuestContext} e opera sobre ele, mantendo o {@code QuestModule}
 * como um orquestrador enxuto.
 *
 * Os campos são package-private de propósito: todos os componentes residem no
 * mesmo pacote {@code com.eventchanger.quest}, então não há necessidade de
 * getters/setters cerimoniais para um holder interno.
 */
public class QuestContext {

    // ---- APIs (imutáveis) ----
    public final PluginAPI pluginAPI;
    public final QuestAPI questAPI;
    public final HeroAPI heroAPI;
    public final EntitiesAPI entitiesAPI;
    public final MovementAPI movementAPI;
    public final StarSystemAPI starSystemAPI;
    public final ConfigAPI configAPI;
    public final OreAPI oreAPI;
    public final StatsAPI statsAPI;
    public final BotAPI botAPI;
    public final DarkInput darkInput;
    public final AttackAPI attackAPI;
    // Caminho de clique do CORE (Main.API), usado pelo DmPlugin e por nós no teste
    // de comparação. É a instância de IDarkBotAPI real, com a interação nativa
    // ACOPLADA ao pid do cliente do jogo (ao contrário de ctx.darkInput, que é uma
    // instância DarkInput órfã criada pelo PluginApiImpl e nunca acoplada).
    public final IDarkBotAPI coreApi;

    // ---- Config ----
    public QuestConfig config;

    // ---- GUI do QuestGiver (resolvida por tick) ----
    public Gui questGui;

    // ---- Throttled decision state (calculated once a second) ----
    public long lastDecisionTime = 0L;
    public Requirement currentReq = null;
    public GameMap targetMap = null;

    // Rastreamento de atribuições de targetMap para debug
    public static class TargetMapTrace {
        public final GameMap oldValue;
        public final GameMap newValue;
        public final String questTitle;
        public final String requirementDesc;
        public final String methodName;
        public final String className;
        public final int lineNumber;
        public final String stackTrace;
        public final long timestamp;

        public TargetMapTrace(GameMap oldValue, GameMap newValue, String questTitle, 
                            String requirementDesc, String methodName, String className, 
                            int lineNumber, String stackTrace) {
            this.oldValue = oldValue;
            this.newValue = newValue;
            this.questTitle = questTitle;
            this.requirementDesc = requirementDesc;
            this.methodName = methodName;
            this.className = className;
            this.lineNumber = lineNumber;
            this.stackTrace = stackTrace;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format("[TARGETMAP TRACE] %s%n" +
                    "  %s -> %s%n" +
                    "  quest='%s' req='%s'%n" +
                    "  origem: %s.%s():%d%n" +
                    "  stack: %s%n",
                    new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(timestamp),
                    oldValue != null ? oldValue.getName() : "null",
                    newValue != null ? newValue.getName() : "null",
                    questTitle, requirementDesc,
                    className, methodName, lineNumber,
                    stackTrace);
        }
    }

    public final java.util.List<TargetMapTrace> targetMapTraces = new java.util.ArrayList<>();
    private static final int MAX_TRACES = 50; // Limita para não floodar

    // Método helper para rastrear mudanças em targetMap
    public void traceTargetMapChange(GameMap oldValue, GameMap newValue, String questTitle, 
                                     String requirementDesc, String methodName, String className, int lineNumber) {
        if (targetMapTraces.size() >= MAX_TRACES) {
            targetMapTraces.clear(); // Reset quando atingir limite
        }
        
        // [QUEST_STATE] Ponto 7: registra alteração de targetMap com Thread/Classe/Método/Linha/Stack simplificada
        String threadName = "bloqueado-por-seguranca";
        String simpleStack = "stack-bloqueado";
        try {
            // DarkBot bloqueia java.lang.Thread no PluginClassLoader, mas
            // System.identityHashCode e o nome da thread podem estar indisponíveis.
            // Usamos o caller informado por parâmetro como fonte autoritativa.
            threadName = "QuestModule-thread";
        } catch (Throwable ignored) {}
        
        TargetMapTrace trace = new TargetMapTrace(oldValue, newValue, questTitle, requirementDesc,
                                                    methodName, className, lineNumber, 
                                                    simpleStack);
        targetMapTraces.add(trace);
        
        // Log imediato para debug (formato simplificado sem stack trace)
        System.out.println("[TARGETMAP TRACE] " + new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(trace.timestamp)
                + " | " + (oldValue != null ? oldValue.getName() : "null") + " -> " + (newValue != null ? newValue.getName() : "null")
                + " | quest='" + questTitle + "' req='" + requirementDesc + "'"
                + " | origem: " + className + "." + methodName + "():" + lineNumber
                + " | thread=" + threadName);
    }

    // [QUEST_STATE] Ponto 7: registra alteração de currentReq (Requirement)
    // Mantém um histórico curto das últimas N alterações para diagnóstico.
    public static class CurrentReqTrace {
        public final Requirement oldValue;
        public final Requirement newValue;
        public final String className;
        public final String methodName;
        public final int lineNumber;
        public final long timestamp;
        public CurrentReqTrace(Requirement oldValue, Requirement newValue, String className, String methodName, int lineNumber) {
            this.oldValue = oldValue;
            this.newValue = newValue;
            this.className = className;
            this.methodName = methodName;
            this.lineNumber = lineNumber;
            this.timestamp = System.currentTimeMillis();
        }
        @Override
        public String toString() {
            return String.format("[CURRENTREQ TRACE] %s | %s -> %s | origem: %s.%s():%d",
                    new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(timestamp),
                    oldValue != null ? oldValue.getDescription() : "null",
                    newValue != null ? newValue.getDescription() : "null",
                    className, methodName, lineNumber);
        }
    }
    public final java.util.List<CurrentReqTrace> currentReqTraces = new java.util.ArrayList<>();
    private static final int MAX_REQ_TRACES = 50;

    public void traceCurrentReqChange(Requirement oldValue, Requirement newValue, String className, String methodName, int lineNumber) {
        if (currentReqTraces.size() >= MAX_REQ_TRACES) currentReqTraces.clear();
        currentReqTraces.add(new CurrentReqTrace(oldValue, newValue, className, methodName, lineNumber));
        System.out.println("[CURRENTREQ TRACE] " + new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(System.currentTimeMillis())
                + " | " + (oldValue != null ? oldValue.getDescription() : "null") + " -> " + (newValue != null ? newValue.getDescription() : "null")
                + " | origem: " + className + "." + methodName + "():" + lineNumber
                + " | thread=QuestModule-thread");
    }

    // ---- Working map do bot (general.working_map é Integer na API) ----
    public Integer originalWorkingMapId = null;
    public Integer lastSetWorkingMapId = null;

    // ---- General state ----
    public String currentAction = "Aguardando...";
    public long lastAcceptAttemptTime = 0L;
    public long questCacheOpenAttemptTime = 0L;
    public long acceptOpenAttemptTime = 0L;
    public long lastAcceptSuccessTime = 0L;
    // ---- Non-blocking accept verification / scan state ----
    // Timestamp (ms) at which we should verify if the quest was accepted after a click.
    public long pendingAcceptVerifyTime = 0L;
    // Timestamp (ms) before which no new accept click may be sent after a failed attempt.
    public long nextAcceptRetryTime = 0L;
    // The quest we are waiting to confirm acceptance for (id), or -1 if none pending.
    public int pendingAcceptQuestId = -1;
    // Title of the pending quest (cached, since candidates list changes after accept).
    public String pendingAcceptQuestTitle = "";
    // Current cell index in the non-blocking accept-button grid scan (-1 = not scanning).
    public int acceptScanIndex = -1;
    // Índice da linha da lista de quests que estamos processando (0 = 1ª missão,
    // que já vem selecionada ao abrir o QuestGiver). Cada missão aceita avança o
    // índice para a "próxima de baixo" (linha seguinte).
    public int acceptRowIndex = 0;
    // true quando precisamos clicar na linha da lista para selecioná-la antes de
    // aceitar (usado para linhas > 0; a 1ª já vem selecionada).
    public boolean acceptNeedSelect = false;
    // true quando precisamos clicar no botão Aceitar para a missão atualmente
    // selecionada na GUI.
    public boolean acceptNeedAccept = false;
    // Contador de quests efetivamente aceitas neste ciclo de aceite (controla o
    // limite maxQuestsPerMap). Incrementado em verifyPendingAccept() ao confirmar
    // o aceite; resetado quando o ciclo de aceite recomeça (janela reaberta ou
    // ao sair para executar as quests). NÃO usa getCurrestQuests() para contar,
    // pois essa lista só traz as quests DISPONÍVEIS para aceitar (as já aceitas
    // somem dela), e contar !isActivable() nela dava 75/5 (contava quests indisponíveis).
    public int acceptedThisCycle = 0;
    // Flag que indica que o ciclo de aceite foi completado (atingiu maxQuestsPerMap).
    // Só é resetada quando não há mais quest ativa (todas completadas), evitando
    // o "ping-pong" de aceitar -> resetar -> tentar aceitar de novo no mesmo local.
    public boolean acceptCycleComplete = false;
    // Contador de falhas CONSECUTIVAS de aceite neste ciclo. Usado para detectar
    // que o jogo JÁ atingiu o limite de missões ativas (ex: 5) e o botão Accept
    // não surte efeito — assim evitamos o loop infinito "tentando aceitar mais"
    // quando já existem 5 missões aceitas no cliente (o acceptedThisCycle nunca
    // chega a maxQuestsPerMap porque nenhum aceite é confirmado). Ao atingir
    // MAX_ACCEPT_FAILS, o ciclo é encerrado (acceptCycleComplete=true) e o bot
    // sai para executar as missões existentes.
    public int acceptFailStreak = 0;
    public static final int MAX_ACCEPT_FAILS = 10;
    // True while the grid scan is the active accept strategy (skips fixed-click branch).
    public boolean acceptScanning = false;
    // True if we clicked a scan cell last tick and must verify acceptance next tick.
    public boolean acceptScanPendingCheck = false;
    // Last scan cell relative coords (for logging on success).
    public double acceptScanLastRx = 0.0;
    public double acceptScanLastRy = 0.0;
    // ---- Diagnóstico de seleção de linha (etapa SELECT) ----
    // Quest que ESPERAMOS que esteja selecionada após clicar numa linha da lista.
    // Armazenada no clique e conferida no tick seguinte (etapa ACCEPT) para saber
    // se o clique na lista realmente trocou a missão selecionada no core.
    public int expectedSelectQuestId = -1;
    public String expectedSelectQuestTitle = "";
    public long selectClickTime = 0L;
    // Throttle (ms timestamp) for the visible-GUI diagnostic dump.
    public long lastGuiDumpTime = 0L;
    // Throttle (ms timestamp) for the trySelect call on the QuestGiver (avoid spam).
    public long lastQuestGiverTrySelectTime = 0L;
    public long lastDeliverCheckTime = 0L;
    public long lastDeliverCooldownTime = 0L;
    public long lastPortalJumpTime = 0L;
    public long lastProgressTime = System.currentTimeMillis();
    public double lastProgressValue = -1;
    public int lastQuestId = -1;
    public long lastCollectAttemptTime = 0L;
    public long lastSellAttemptTime = 0L;
    public boolean isSellingCargo = false;
    public boolean questCacheInitialized = false;
    public long botStartTime = 0L;
    public String cachedFactionPrefix = null;

    // ---- Sell-state ----
    public long lastTradeOpenTime = 0L;
    public boolean tradeWindowOpen = false;

    // ---- Cache for navigation routes ----
    public GameMap cachedRouteTarget = null;
    public Portal cachedRoutePortal = null;
    public long cachedRouteTime = 0L;
    public static final long ROUTE_CACHE_TTL_MS = 10_000L;
    public static final String ACCEPTED_QUESTS_CACHE_FILE = "accepted_quests_cache.properties";
    public long lastCacheSaveTime = 0L;
    public static final long CACHE_SAVE_INTERVAL_MS = 5_000L;

    // ---- Priority order for requirement types ----
    public static final List<RequirementType> PRIORITY = Arrays.asList(
            RequirementType.KILL_NPC,
            RequirementType.KILL_NPCS,
            RequirementType.DAMAGE_NPCS,
            RequirementType.KILL_PLAYERS,
            RequirementType.DAMAGE_PLAYERS,
            RequirementType.DAMAGE_ENEMY_PLAYERS,
            RequirementType.KILL_ANY,
            RequirementType.SELL_ORE,
            RequirementType.COLLECT_BONUS_BOX,
            RequirementType.COLLECT_BONUS_BOX_TYPE,
            RequirementType.COLLECT_LOOT,
            RequirementType.CARGO,
            RequirementType.SPEND_AMMUNITION
    );

    /**
     * Verifica se um requirement é de coleta dos 3 minérios que só vêm de caixas
     * from_ship (carga dropada por NPCs): Prometid, Duranium e Promerium.
     * Usado para direcionar o bot ao mapa configurado e marcar todos os NPCs do mapa.
     */
    public static boolean isOreFromShipQuest(Requirement r) {
        if (r == null) return false;
        RequirementType t = r.getRequirementType();
        if (t != RequirementType.COLLECT) return false;
        String d = r.getDescription() != null ? r.getDescription().toLowerCase() : "";
        return d.contains("prometid") || d.contains("duranium") || d.contains("promerium");
    }

    // ---- Ore keys cache ----
    public Set<String> alwaysCollectOreKeysCache = null;

    // ---- Quest cache / retry state ----
    public final Map<Integer, String> acceptedQuestTitleCache = new LinkedHashMap<>();
    public final Map<Integer, Integer> acceptRetryCounts = new HashMap<>();
    public String lastRequirementState = "";
    public long targetBoxLastValidTime = 0L;
    public long targetNpcLastValidTime = 0L;
    public long lastNpcCountRefreshTime = 0L;
    public final Map<String, Integer> cachedMarkedNpcCounts = new LinkedHashMap<>();
    public final Set<String> lastDesiredNpcKeys = new HashSet<>();
    public final Set<String> lastDesiredBoxKeys = new HashSet<>();
    public long lastNpcMatchSuccessTime = 0L;
    public long lastBoxMatchSuccessTime = 0L;
    public long lastCollectorSwitchTime = 0L;
    // Timestamp (ms) da última vez que o QuestModule se readotou como módulo ativo
    // (reentrando do LootCollectorModule). Usado para aplicar o mesmo cooldown
    // simétrico de MODULE_SWITCH_STABILITY_MS na volta, evitando o ping-pong
    // QuestModule <-> LootCollectorModule.
    public long lastQuestReclaimTime = 0L;
    public static final long NPC_MATCH_CACHE_TTL_MS = 3500L;
    public static final long BOX_MATCH_CACHE_TTL_MS = 3500L;
    public static final long MODULE_SWITCH_STABILITY_MS = 1500L;
    public LootCollectorModule defaultLootCollectorModule;
    public final Set<String> lastAppliedNpcKeys = new HashSet<>();
    public final Set<String> lastAppliedBoxKeys = new HashSet<>();
    public String lastProfileName = null;
    public String lastCustomAliasesRaw = "";
    public final Map<String, Set<String>> customNpcAliases = new HashMap<>();
    public Integer lastTargetMapId = null;

    // ---- External mission maps (EN / PT / PT overrides) ----
    public final Map<String, String> externalMissionMap = new HashMap<>();
    public final Map<String, String> externalMissionMapPt = new HashMap<>();
    public final Map<String, String> externalMissionMapPtOverrides = new HashMap<>();

    // ---- Sell / ammo transient state ----
    public long sellStartTime = 0L;
    public long lastAmmoChangeTime = 0L;
    public boolean ammoChanged = false;

    // ---- Calibration mode (user clicks on the QuestGiver window to log positions) ----
    public boolean calibrationMode = false;
    public long lastCalibrationLogTime = 0L;

    // ---- Click test mode (isolates the mouseClick chain) ----
    // Throttle (ms) para o teste de clique no centro do mapa (1 clique por segundo).
    public long lastClickTestTime = 0L;

    public QuestContext(PluginAPI api) {
        this.pluginAPI = api;
        this.defaultLootCollectorModule = new LootCollectorModule(api);
        this.questAPI = api.requireAPI(QuestAPI.class);
        this.heroAPI = api.requireAPI(HeroAPI.class);
        this.entitiesAPI = api.requireAPI(EntitiesAPI.class);
        this.movementAPI = api.requireAPI(MovementAPI.class);
        this.starSystemAPI = api.requireAPI(StarSystemAPI.class);
        this.configAPI = api.requireAPI(ConfigAPI.class);
        this.oreAPI = api.requireAPI(OreAPI.class);
        this.statsAPI = api.requireAPI(StatsAPI.class);
        this.botAPI = api.requireAPI(BotAPI.class);
        this.darkInput = (DarkInput) api.requireInstance(DarkInput.class);
        this.attackAPI = api.requireAPI(AttackAPI.class);
        this.questGui = null;
        // Captura a instância real do core (Main.API). Se for null, o bot ainda
        // não terminou de inicializar a API — mas em runtime ela estará presente.
        this.coreApi = Main.API;

        // DIAGNÓSTICO DA CADEIA DE CLIQUE: registra a identidade das duas instâncias
        // de clique para confirmar a hipótese de que ctx.darkInput é uma instância
        // DarkInput ÓRFÃ (nunca acoplada ao pid do jogo via openWindow), enquanto
        // coreApi (Main.API) usa a interação nativa acoplada do core.
        try {
            String darkInputClass = (this.darkInput != null) ? this.darkInput.getClass().getName() : "null";
            int darkInputHash = (this.darkInput != null) ? System.identityHashCode(this.darkInput) : -1;
            String coreApiClass = (this.coreApi != null) ? this.coreApi.getClass().getName() : "null";
            int coreApiHash = (this.coreApi != null) ? System.identityHashCode(this.coreApi) : -1;
            System.out.println("[QuestModule][CLICK-CHAIN] darkInput class=" + darkInputClass
                    + " hash=" + darkInputHash
                    + " | coreApi(Main.API) class=" + coreApiClass
                    + " hash=" + coreApiHash
                    + " | Main.API==" + (com.github.manolo8.darkbot.Main.API != null));
        } catch (Throwable t) {
            System.err.println("[QuestModule][CLICK-CHAIN] Erro ao logar identidade das instancias: " + t);
        }
    }

    // ---- Componentes funcionais (instanciados pelo QuestModule) ----
    public QuestLogger logger;
    public MissionMapLoader missionMapLoader;
    public NpcBoxMatcher npcBoxMatcher;
    public MapResolver mapResolver;
    public QuestGiverInteraction questGiverInteraction;
    public SellingHandler sellingHandler;
    public ConfigMarker configMarker;
    public QuestPanel questPanel;
}
