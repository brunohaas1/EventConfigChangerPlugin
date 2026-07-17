package com.eventchanger.quest;

import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.Dropdown;
import eu.darkbot.api.config.annotations.Number;
import eu.darkbot.api.config.annotations.Option;

import java.util.Arrays;
import java.util.List;

@Configuration("quest_module")
public class QuestConfig {

    // =========================================================================
    // Comportamento da Quest
    // =========================================================================

    @Option("quest_module.quest_type")
    public QuestTypeEnum questType = QuestTypeEnum.NORMAL; // Tipo de quest: NORMAL, URGENT

    @Option("quest_module.quest_flow.quest_types_to_accept")
    @Dropdown(options = QuestTypesOptions.class)
    public String questTypesToAccept = "ALL"; // Tipos de quest para aceitar

    @Option("quest_module.quest_flow.accept_pvp_quests")
    public boolean acceptPvpQuests = true; // Aceitar missões PVP

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
        public static final int ACCEPT_INTERVAL_MS = 3000;         // Intervalo entre tentativas de aceitar (ms)
        public static final boolean KEEP_SECONDARY_QUESTS_MARKED = true; // Manter NPCs de quests secundárias marcados
        public static final boolean AUTO_DELIVER = true;           // Entregar quests automaticamente
        public static final int MAX_QUESTS_PER_MAP = 5;            // Máximo de quests por mapa
        public static final String QUEST_TYPES_TO_ACCEPT = "ALL";  // Tipos: ALL, NORMAL, DAILY, WEEKLY, SPECIAL, SEASON

        // Posições de clique FIXAS (não configuráveis)
        public static final double ACCEPT_BUTTON_X = 0.95; // X relativa do botão Aceitar (~X=775/813)
        public static final double ACCEPT_BUTTON_Y = 0.93; // Y relativa do botão Aceitar (~Y=512/550)
        public static final double LIST_ITEM_X = 0.151;    // Centro da lista (127/840) = winX+127
        public static final double LIST_ITEM_Y = 0.586;    // Centro 1ª linha (322/550)

        public static final boolean SCAN_ACCEPT_BUTTON = true;     // Varrer posições candidatas se o clique fixo falhar
        public static final boolean CALIBRATION_MODE = false;      // Modo de calibração
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

        @Option.Ignore
        public String eventOreKeys = "scrapium,prismatium,mucosum,muscosum,luminium,boltrum,bifenon"; // Minerios de evento (separados por virgula)

        @Option.Ignore
        public int cargoClearThreshold = 5; // Limiar de cargo para vender (%)

        @Option.Ignore
        public boolean collectGreenBoxes = true; // Coletar caixas verdes
        @Option.Ignore
        public boolean collectRedBoxes = true; // Coletar caixas vermelhas
        @Option.Ignore
        public boolean collectBlueBoxes = true; // Coletar caixas azuis
        @Option.Ignore
        public boolean collectBonusBoxes = true; // Coletar caixas bonus
        @Option.Ignore
        public boolean collectCargoBoxes = true; // Coletar caixas de cargo
    }

    // =========================================================================
    // Coleta dos 3 minérios de carga (Prometid / Duranium / Promerium)
    //
    // Esses minérios só vêm de caixas from_ship (carga dropada por NPCs), então o
    // bot precisa ir a um mapa e matar TODOS os NPCs do mapa para coletá-los. O
    // usuário escolhe o mapa numa lista (dropdown); o plugin navega para lá, marca
    // todos os NPCs do mapa e, ao completar a missão, desmarca tudo.
    // =========================================================================

    @Option("quest_module.ore_from_ship")
    public OreFromShipConfig oreFromShip = new OreFromShipConfig();

    public static class OreFromShipConfig {

        @Option("quest_module.ore_from_ship.collect_map")
        @Dropdown(options = OreMapOptions.class)
        public String collectMap = "1-2"; // Mapa onde coletar os 3 minérios (Prometid/Duranium/Promerium)
    }

    /**
     * Lista de mapas disponíveis no dropdown de coleta dos minérios de carga.
     * Cobre os mapas de baixo de cada facção (onde esses minérios costumam dropar).
     */
    public static class OreMapOptions implements Dropdown.Options<String> {
        @Override
        public List<String> options() {
            return Arrays.asList(
                "1-2", "2-2", "3-2", "1-3", "2-3", "3-3", "1-4", "2-4", "3-4", "1-5", "2-5", "3-5",
                "1-6", "2-6", "3-6", "1-7", "2-7", "3-7", "1-8", "2-8", "3-8"
            );
        }
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

    @Option.Ignore
    public AmmoConfig ammo = new AmmoConfig();

    public static class AmmoConfig {

        public boolean autoSpendAmmo = true; // Gastar munição automaticamente

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
        @Dropdown(options = PvpMapOptions.class)
        public String pvpMap = "4-4"; // Mapa de destino para quests PVP (ex: 4-4, 5-3, 5-4). Deixe vazio para usar o mapa atual.

        @Option("quest_module.pvp.pvp_ammo_key")
        public Character pvpAmmoKey = Character.valueOf('4'); // Tecla de munição para PVP (ex: 4)
    }

    public static class PvpMapOptions implements Dropdown.Options<String> {
        @Override
        public List<String> options() {
            return Arrays.asList(
                "1-1", "1-2", "1-3", "1-4", "1-5", "1-6", "1-7", "1-8",
                "2-1", "2-2", "2-3", "2-4", "2-5", "2-6", "2-7", "2-8",
                "3-1", "3-2", "3-3", "3-4", "3-5", "3-6", "3-7", "3-8",
                "4-1", "4-2", "4-3", "4-4", "4-5", ""
            );
        }
    }

    // =========================================================================
    // Matching de NPCs
    // =========================================================================

    @Option.Ignore
    public NpcConfig npc = new NpcConfig();

    public static class NpcConfig {

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

    public static class QuestTypesOptions implements Dropdown.Options<String> {
        @Override
        public List<String> options() {
            return Arrays.asList("ALL", "NORMAL", "DAILY");
        }
    }

}