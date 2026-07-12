package com.eventchanger.quest;

import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.Number;
import eu.darkbot.api.config.annotations.Option;

@Configuration("quest_module")
public class QuestConfig {

    // =========================================================================
    // Comportamento da Quest
    // =========================================================================

    @Option("quest_module.quest_type")
    public QuestTypeEnum questType = QuestTypeEnum.NORMAL; // Tipo de quest: NORMAL, URGENT

    @Option("quest_module.auto_kill_npc")
    public boolean autoKillNpc = true; // Matar NPCs automaticamente
    
    @Option("quest_module.auto_collect_loot")
    public boolean autoCollectLoot = true; // Coletar loot automaticamente

    @Option("quest_module.show_status_panel")
    public boolean showStatusPanel = true; // Mostrar painel de status

    // =========================================================================
    // Aceitar / Completar Quest — valores FIXOS (não expostos na UI)
    //
    // O fluxo de aceite e a navegação entre mapas NÃO precisam ser configurados
    // pelo usuário: o bot já faz tudo automaticamente com estes valores. Por isso
    // foram removidos do menu de módulos e viram constantes internas. Se precisar
    // reabilitar algum como @Option no futuro, basta descomentar.
    // =========================================================================

    public static class QuestFlowConfig {
        public static final boolean AUTO_ACCEPT = true;            // Aceitar quests automaticamente
        public static final int ACCEPT_INTERVAL_MS = 5000;         // Intervalo entre tentativas de aceitar (ms)
        public static final boolean KEEP_SECONDARY_QUESTS_MARKED = true; // Manter NPCs de quests secundárias marcados
        public static final boolean AUTO_DELIVER = true;           // Entregar quests automaticamente
        public static final int MAX_QUESTS_PER_MAP = 5;            // Máximo de quests por mapa
        public static final String QUEST_TYPES_TO_ACCEPT = "ALL";  // Tipos: ALL, NORMAL, DAILY, WEEKLY, SPECIAL, SEASON

        // Posições de clique FIXAS (iguais ao DmPlugin nativo, não configuráveis)
        public static final double ACCEPT_BUTTON_X = 0.95; // X relativa do botão Aceitar (~X=775/813)
        public static final double ACCEPT_BUTTON_Y = 0.93; // Y relativa do botão Aceitar (~Y=512/550)
        public static final double LIST_ITEM_X = 0.151;    // Centro da lista (127/840) = winX+127
        public static final double LIST_ITEM_Y = 0.586;    // Centro 1ª linha (322/550)

        public static final boolean SCAN_ACCEPT_BUTTON = true;     // Varrer posições candidatas se o clique fixo falhar
        public static final boolean CALIBRATION_MODE = false;      // Modo de calibração
        public static final ClickTestMode CLICK_TEST_MODE = ClickTestMode.OFF; // Teste de clique
    }

    /**
     * Modos de teste de clique para isolar a cadeia de mouseClick.
     *  - OFF: comportamento normal (usa ctx.darkInput, caminho atual do plugin).
     *  - VIA_DARKINPUT: clica no centro do mapa usando ctx.darkInput (caminho atual).
     *  - VIA_CORE_API: clica no centro do mapa usando Main.API (caminho do DmPlugin).
     * Com a janela do QuestGiver FECHADA, se a nave andar no VIA_CORE_API e NÃO
     * andar no VIA_DARKINPUT, confirma que ctx.darkInput é uma instância órfã.
     */
    public enum ClickTestMode {
        OFF,
        VIA_DARKINPUT,
        VIA_CORE_API
    }

    // =========================================================================
    // Coleta de Loot / Cargo
    // =========================================================================

    @Option("quest_module.loot")
    public LootConfig loot = new LootConfig();

    public static class LootConfig {

        @Option("quest_module.loot.auto_sell_when_full")
        public boolean autoSellWhenFull = true; // Vender minerios quando cargo cheio

        @Option("quest_module.loot.always_collect_event_ores")
        public boolean alwaysCollectEventOres = true; // Sempre coletar minerios de evento

        @Option("quest_module.loot.event_ore_keys")
        public String eventOreKeys = "scrapium,prismatium,mucosum,muscosum,luminium,boltrum,bifenon"; // Minerios de evento (separados por virgula)

        @Option("quest_module.loot.cargo_clear_threshold")
        @Number(min = 0, max = 50, step = 1)
        public int cargoClearThreshold = 5; // Limiar de cargo para vender (%)

        @Option("quest_module.loot.collect_green_boxes")
        public boolean collectGreenBoxes = true; // Coletar caixas verdes

        @Option("quest_module.loot.collect_red_boxes")
        public boolean collectRedBoxes = true; // Coletar caixas vermelhas

        @Option("quest_module.loot.collect_blue_boxes")
        public boolean collectBlueBoxes = true; // Coletar caixas azuis

        @Option("quest_module.loot.collect_bonus_boxes")
        public boolean collectBonusBoxes = true; // Coletar caixas bonus

        @Option("quest_module.loot.collect_cargo_boxes")
        public boolean collectCargoBoxes = true; // Coletar caixas de cargo
    }

    // =========================================================================
    // Navegação — valores FIXOS (não expostos na UI)
    //
    // A navegação entre mapas é automática e não precisa ser configurada pelo
    // usuário. Estes valores são constantes internas; para reabilitar algum como
    // @Option, basta descomentar.
    // =========================================================================

    public static class NavigationConfig {
        public static final int PORTAL_JUMP_DISTANCE = 400;     // Distância para pular portal
        public static final int PORTAL_JUMP_COOLDOWN_MS = 5000; // Cooldown entre pulos de portal (ms)
        public static final int DECISION_INTERVAL_MS = 1000;    // Intervalo de decisão (ms)
        public static final int FORCED_WORKING_MAP = -1;        // Mapa forçado (-1 = automático)
        public static final String MAP_BLACKLIST = "";          // Lista de mapas bloqueados (ex: "4-5,5-2")
    }

    // =========================================================================
    // Venda / Trade
    // =========================================================================

    @Option("quest_module.sell")
    public SellConfig sell = new SellConfig();

    public static class SellConfig {

        @Option("quest_module.sell.use_external_ore_seller")
        public boolean useExternalOreSeller = true; // Usar vendedor externo (SharedPlugin)
    }

    // =========================================================================
    // Gasto de Munição (para quests SPEND_AMMUNITION)
    // =========================================================================

    @Option("quest_module.ammo")
    public AmmoConfig ammo = new AmmoConfig();

    public static class AmmoConfig {

        @Option("quest_module.ammo.auto_spend_ammo")
        public boolean autoSpendAmmo = true; // Gastar munição automaticamente

        @Option("quest_module.ammo.spend_ammo_laser")
        @Number(min = 1, max = 4, step = 1)
        public int spendAmmoLaser = 4; // Laser para gastar (1-4, LCB-10 mais caro)
    }

    // =========================================================================
    // PVP (para quests KillPlayers / DamagePlayers)
    // =========================================================================

    @Option("quest_module.pvp")
    public PvpConfig pvp = new PvpConfig();

    public static class PvpConfig {

        @Option("quest_module.pvp.auto_pvp")
        public boolean autoPvp = true; // Atacar jogadores automaticamente

        @Option("quest_module.pvp.pvp_map")
        public String pvpMap = "4-4"; // Mapa de destino para quests PVP (ex: 4-4, 5-3, 5-4). Deixe vazio para usar o mapa atual.
    }

    // =========================================================================
    // Matching de NPCs
    // =========================================================================

    @Option("quest_module.npc")
    public NpcConfig npc = new NpcConfig();

    public static class NpcConfig {

        @Option("quest_module.npc.custom_aliases")
        public String customAliases = ""; // Formato: "nome_npc1=desc_quest1;nome_npc2=desc_quest2"
    }

    // =========================================================================
    // Logs
    // =========================================================================

    @Option("quest_module.logging")
    public LoggingConfig logging = new LoggingConfig();

    public static class LoggingConfig {

        @Option("quest_module.logging.verbose")
        public boolean verbose = false; // Logs detalhados no console

        @Option("quest_module.logging.log_to_file")
        public boolean logToFile = true; // Salvar logs em arquivo
    }

}