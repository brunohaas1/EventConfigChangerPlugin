package com.eventchanger.quest;

import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.config.types.BoxInfo;
import eu.darkbot.api.config.types.NpcInfo;
import eu.darkbot.api.game.other.GameMap;
import eu.darkbot.api.managers.QuestAPI;
import eu.darkbot.api.managers.QuestAPI.Quest;
import eu.darkbot.api.managers.QuestAPI.QuestListItem;
import eu.darkbot.api.managers.QuestAPI.Requirement;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Responsável por espelhar as quests ativas na configuração do DarkBot: marca
 * os NPCs/boxes a serem caçados/coletados, mantém quests secundárias marcadas e
 * trata troca de perfil sem desmarcar configs de outros perfis.
 */
public class ConfigMarker {

    private final QuestContext ctx;
    private final QuestLogger logger;
    private final MapResolver mapResolver;
    private final MissionMapLoader missionMapLoader;
    private final NpcBoxMatcher npcBoxMatcher;

    public ConfigMarker(QuestContext ctx, QuestLogger logger, MapResolver mapResolver,
                        MissionMapLoader missionMapLoader, NpcBoxMatcher npcBoxMatcher) {
        this.ctx = ctx;
        this.logger = logger;
        this.mapResolver = mapResolver;
        this.missionMapLoader = missionMapLoader;
        this.npcBoxMatcher = npcBoxMatcher;
    }

    public void updateConfigForQuest(Quest quest, GameMap targetMap) {
        ensureProfileTracking();

        // Rastreamento de chamadas para updateConfigForQuest
        System.out.println("[UPDATE_CONFIG_FOR_QUEST] quest='" + (quest != null ? quest.getTitle() : "null")
                + "' | targetMap=" + (targetMap != null ? targetMap.getName() : "null")
                + " | caller: " + Thread.currentThread().getStackTrace()[2].getMethodName()
                + "() em " + Thread.currentThread().getStackTrace()[2].getClassName());

        if (targetMap == null) {
            unmarkAll();
            return;
        }

        logger.logDebug("npcFinder recebeu: targetMap="
                + (targetMap != null ? targetMap.getName() : "null")
                + " | quest=" + (quest != null ? quest.getTitle() : "null"));

        // Find if we actually need to update the configuration
        // (to avoid spamming ConfigAPI which is expensive)
        Map<Requirement, GameMap> reqMapCache = new java.util.HashMap<>();
        StringBuilder reqState = new StringBuilder();
        if (quest != null) {
            for (Requirement r : quest.getRequirements()) {
                if (r.isCompleted()) continue;
                GameMap rMap = mapResolver.resolveTargetMap(r);
                reqMapCache.put(r, rMap);
                if (rMap != null && rMap.getId() == targetMap.getId()) {
                    reqState.append(r.getDescription()).append(r.getProgress()).append("|");
                }
            }
        } else {
            reqState.append("null-quest").append(targetMap.getId());
        }
        String currentReqState = reqState.toString();

        // [FLOW] 4) Dentro de updateConfigForQuest, após avaliar cada requirement
        if (quest != null) {
            StringBuilder flowReqs = new StringBuilder();
            for (Requirement r : quest.getRequirements()) {
                if (r.isCompleted()) continue;
                GameMap rMap = reqMapCache.get(r);
                flowReqs.append("\n    req='").append(r.getDescription()).append("'")
                        .append(" | rMap=").append(rMap != null ? rMap.getName() : "null")
                        .append(" | rMap==targetMap=").append(rMap != null && rMap.getId() == targetMap.getId());
            }
            System.out.println("[FLOW] updateConfigForQuest:"
                    + "\n  quest=" + quest.getTitle()
                    + "\n  targetMap_recebido=" + (targetMap != null ? targetMap.getName() : "null")
                    + "\n  requirements_avaliados:" + flowReqs);
        }

        boolean stateChanged = !currentReqState.equals(ctx.lastRequirementState);

        if (stateChanged) {
            ctx.lastRequirementState = currentReqState;
        }

        Map<String, NpcInfo> npcInfos = getNpcInfos();
        Map<String, BoxInfo> boxInfos = getBoxInfos();

        Set<String> desiredNpcKeys = new HashSet<>();
        Set<String> desiredBoxKeys = new HashSet<>();

        if (quest != null) {
            for (Requirement r : quest.getRequirements()) {
                if (r.isCompleted()) continue;
                GameMap rMap = reqMapCache.get(r);
                if (rMap == null || rMap.getId() != targetMap.getId()) continue;

                Requirement.RequirementType type = r.getRequirementType();
                if (isKillType(type) && npcInfos != null && !npcInfos.isEmpty()) {
                    int matches = 0;
                    for (Map.Entry<String, NpcInfo> entry : npcInfos.entrySet()) {
                        String npcName = npcBoxMatcher.getNpcName(entry);
                        if (npcName == null || npcName.isEmpty()) continue;
                    if (npcBoxMatcher.npcMatchesQuestDesc(npcName, r.getDescription())) {
                            desiredNpcKeys.add(entry.getKey());
                            matches++;
                            if (stateChanged && logger.isVerboseLoggingEnabled()) {
                                // Throttle pela DESCRIÇÃO da missão (chave estável), não pelo
                                // nome da variante de NPC. Assim, "Destrói Lordakia" casando com
                                // ~20 variantes de Lordakia gera no máximo 1 linha a cada 10s
                                // (para a missão inteira), em vez de 1 linha por variante.
                                logger.appendPluginLogThrottled(
                                        "MATCH_PRINCIPAL:" + r.getDescription(),
                                        "[QuestModule] MATCH PRINCIPAL: '" + r.getDescription() + "' casou com NPC: " + npcName,
                                        10000L);
                            }
                        }
                    }
                    if (matches == 0) {
                        logger.logDebug("Sem match NPC para requisito: " + r.getDescription());
                    }
                    // CORREÇÃO: NÃO marcamos todas as caixas de carga (from_ship/cargo)
                    // aqui. No DarkOrbit, matar NPC dropa caixa de carga que contém
                    // minério; marcar TODAS as caixas de carga faria o bot coletar
                    // TODO o minério, mesmo sem missão de minério. A coleta de
                    // minério/carga é coberta corretamente pelo branch isLootType
                    // (abaixo), que só roda quando a missão tem requirement COLLECT_*
                    // e casa o nome do minério, e pelo markAlwaysCollectOres (já
                    // condicionado à missão pedir aquele minério). Assim o minério
                    // só é marcado quando a missão realmente exige.
                } else if (isLootType(type) && boxInfos != null) {
                    String cleanBoxName = MissionMapLoader.normalize(r.getDescription()).toLowerCase();
                    for (Map.Entry<String, BoxInfo> entry : boxInfos.entrySet()) {
                        String boxName = entry.getKey() != null ? entry.getKey().toLowerCase() : "";
                        if (npcBoxMatcher.matchesBoxName(boxName, cleanBoxName) && isBoxAllowedByLootConfig(boxName)) {
                            desiredBoxKeys.add(entry.getKey());
                        }
                    }
                }
            }
        }

        // Keep secondary accepted quests marked only when enabled
        if (npcInfos != null && ctx.config != null && QuestConfig.QuestFlowConfig.KEEP_SECONDARY_QUESTS_MARKED) {
            markSecondaryAcceptedQuestNpcs(npcInfos, quest, desiredNpcKeys, targetMap, stateChanged);
        }

        if (boxInfos != null && ctx.config != null && ctx.config.loot.alwaysCollectEventOres) {
            markAlwaysCollectOres(boxInfos, desiredBoxKeys, quest);
        }

        long now = System.currentTimeMillis();

        // CORREÇÃO: invalida o cache de NPCs quando o targetMap muda.
        // Se o requirement anterior era de um mapa (ex: Lordakia em 1-2) e o atual
        // é de outro (ex: Saimon em 1-3), o cache antigo (NPCs de Lordakia) não
        // deve ser reutilizado, senão o bot marca NPCs que não existem no novo mapa.
        if (ctx.lastTargetMapId != null && targetMap != null
                && ctx.lastTargetMapId != targetMap.getId()) {
            ctx.lastDesiredNpcKeys.clear();
            ctx.lastNpcMatchSuccessTime = 0;
            logger.logDebug("Cache de NPCs invalidado: mapa mudou de id="
                    + ctx.lastTargetMapId + " para " + targetMap.getId());
        }
        ctx.lastTargetMapId = (targetMap != null) ? targetMap.getId() : null;

        if (!desiredNpcKeys.isEmpty()) {
            ctx.lastNpcMatchSuccessTime = now;
            ctx.targetNpcLastValidTime = now;
            ctx.lastDesiredNpcKeys.clear();
            ctx.lastDesiredNpcKeys.addAll(desiredNpcKeys);
        } else if (!ctx.lastDesiredNpcKeys.isEmpty() && now - ctx.lastNpcMatchSuccessTime <= QuestContext.NPC_MATCH_CACHE_TTL_MS) {
            desiredNpcKeys.addAll(ctx.lastDesiredNpcKeys);
            ctx.targetNpcLastValidTime = now;
            logger.logDebug("Reutilizando cache de NPCs marcados (TTL)");
        }

        if (!desiredBoxKeys.isEmpty()) {
            ctx.lastBoxMatchSuccessTime = now;
            ctx.targetBoxLastValidTime = now;
            ctx.lastDesiredBoxKeys.clear();
            ctx.lastDesiredBoxKeys.addAll(desiredBoxKeys);
        } else if (!ctx.lastDesiredBoxKeys.isEmpty() && now - ctx.lastBoxMatchSuccessTime <= QuestContext.BOX_MATCH_CACHE_TTL_MS) {
            desiredBoxKeys.addAll(ctx.lastDesiredBoxKeys);
            ctx.targetBoxLastValidTime = now;
            logger.logDebug("Reutilizando cache de boxes marcadas (TTL)");
        }

        applyDesiredNpcMarksIncremental(npcInfos, desiredNpcKeys);
        applyDesiredBoxMarksIncremental(boxInfos, desiredBoxKeys);

        if (stateChanged && npcInfos != null && desiredNpcKeys.isEmpty()) {
            String questTitle = quest != null ? String.valueOf(quest.getTitle()) : "null";
            String reqInfo = ctx.currentReq != null
                    ? (ctx.currentReq.getRequirementType() + " | " + String.valueOf(ctx.currentReq.getDescription()))
                    : "null";
            logger.appendPluginLog("[QuestModule] nenhum NPC marcado | quest='" + questTitle
                    + "' | targetMap='" + targetMap.getName() + "' | req='" + reqInfo
                    + "' | reqState='" + currentReqState + "'");
        }
    }

    private void applyDesiredNpcMarksIncremental(Map<String, NpcInfo> npcInfos, Set<String> desiredNpcKeys) {
        if (npcInfos == null) return;

        Set<String> toEnable = new HashSet<>(desiredNpcKeys);
        toEnable.removeAll(ctx.lastAppliedNpcKeys);

        Set<String> toDisable = new HashSet<>(ctx.lastAppliedNpcKeys);
        toDisable.removeAll(desiredNpcKeys);

        for (String key : toEnable) {
            NpcInfo info = npcInfos.get(key);
            if (info != null && !info.getShouldKill()) {
                info.setShouldKill(true);
            }
        }

        for (String key : toDisable) {
            NpcInfo info = npcInfos.get(key);
            if (info != null && info.getShouldKill()) {
                info.setShouldKill(false);
            }
        }

        ctx.lastAppliedNpcKeys.clear();
        ctx.lastAppliedNpcKeys.addAll(desiredNpcKeys);
    }

    private void applyDesiredBoxMarksIncremental(Map<String, BoxInfo> boxInfos, Set<String> desiredBoxKeys) {
        if (boxInfos == null) return;

        Set<String> toEnable = new HashSet<>(desiredBoxKeys);
        toEnable.removeAll(ctx.lastAppliedBoxKeys);

        Set<String> toDisable = new HashSet<>(ctx.lastAppliedBoxKeys);
        toDisable.removeAll(desiredBoxKeys);

        for (String key : toEnable) {
            BoxInfo info = boxInfos.get(key);
            if (info != null && !info.shouldCollect()) {
                info.setShouldCollect(true);
            }
        }

        for (String key : toDisable) {
            BoxInfo info = boxInfos.get(key);
            if (info != null && info.shouldCollect()) {
                info.setShouldCollect(false);
            }
        }

        ctx.lastAppliedBoxKeys.clear();
        ctx.lastAppliedBoxKeys.addAll(desiredBoxKeys);
    }

    private void unmarkAll() {
        Map<String, NpcInfo> npcInfos = getNpcInfos();
        if (npcInfos != null) {
            for (String key : ctx.lastAppliedNpcKeys) {
                NpcInfo info = npcInfos.get(key);
                if (info != null && info.getShouldKill()) {
                    info.setShouldKill(false);
                }
            }
        }
        Map<String, BoxInfo> boxInfos = getBoxInfos();
        if (boxInfos != null) {
            for (String key : ctx.lastAppliedBoxKeys) {
                BoxInfo info = boxInfos.get(key);
                if (info != null && info.shouldCollect()) {
                    info.setShouldCollect(false);
                }
            }
        }
        ctx.lastAppliedNpcKeys.clear();
        ctx.lastAppliedBoxKeys.clear();
        ctx.lastDesiredNpcKeys.clear();
        ctx.lastDesiredBoxKeys.clear();
        ctx.lastRequirementState = "";
    }

    private boolean isBoxAllowedByLootConfig(String boxNameLower) {
        if (ctx.config == null || ctx.config.loot == null || boxNameLower == null) return true;

        if ((boxNameLower.contains("from_ship") || boxNameLower.contains("cargo")) && !ctx.config.loot.collectCargoBoxes) {
            return false;
        }
        if ((boxNameLower.contains("bonus") || boxNameLower.startsWith("box_")) && !ctx.config.loot.collectBonusBoxes) {
            return false;
        }
        if (boxNameLower.contains("green") && !ctx.config.loot.collectGreenBoxes) return false;
        if (boxNameLower.contains("red") && !ctx.config.loot.collectRedBoxes) return false;
        if (boxNameLower.contains("blue") && !ctx.config.loot.collectBlueBoxes) return false;
        return true;
    }

    private boolean isKillType(Requirement.RequirementType t) {
        return t == Requirement.RequirementType.KILL_NPC
                || t == Requirement.RequirementType.KILL_NPCS
                || t == Requirement.RequirementType.DAMAGE_NPCS
                || t == Requirement.RequirementType.KILL_ANY;
    }

    private boolean isLootType(Requirement.RequirementType t) {
        return t == Requirement.RequirementType.COLLECT_LOOT
                || t == Requirement.RequirementType.COLLECT_BONUS_BOX
                || t == Requirement.RequirementType.COLLECT_BONUS_BOX_TYPE
                || t == Requirement.RequirementType.CARGO;
    }

    private void markSecondaryAcceptedQuestNpcs(Map<String, NpcInfo> npcInfos, Quest displayedQuest,
                                                Set<String> desiredNpcKeys, GameMap targetMap, boolean logMatches) {
        List<? extends QuestListItem> quests = ctx.questAPI.getCurrestQuests();
        if (quests != null && !quests.isEmpty()) {
            ctx.questGiverInteraction.cacheAcceptedQuestTitles(quests);
        }
        if (ctx.acceptedQuestTitleCache.isEmpty()) return;

        int totalSecondary = 0;
        int maxSecondary = ctx.config != null && QuestConfig.QuestFlowConfig.MAX_QUESTS_PER_MAP > 0
                ? QuestConfig.QuestFlowConfig.MAX_QUESTS_PER_MAP
                : Integer.MAX_VALUE;

        for (Map.Entry<Integer, String> entry : ctx.acceptedQuestTitleCache.entrySet()) {
            if (totalSecondary >= maxSecondary) break;
            Integer questId = entry.getKey();
            String questTitle = entry.getValue();
            if (questId == null || questTitle == null || questTitle.isEmpty()) continue;
            if (displayedQuest != null && questId == displayedQuest.getId()) continue;

            totalSecondary++;

            // Primeiro tenta resolver via mapa externo (título -> descrição)
            String objectiveText = missionMapLoader.resolveQuestObjectiveText(questTitle, true);

            // Se não encontrou no mapa externo, tenta matching direto do título
            if (objectiveText == null || objectiveText.isEmpty()) {
                objectiveText = questTitle;
            }

            if (objectiveText == null || objectiveText.isEmpty()) {
                continue;
            }

            // Verifica se o mapa destino desta quest secundária é o mesmo que o targetMap atual
            // Para isso, tenta resolver o mapa a partir do objectiveText
            boolean sameMap = true; // default: assume mesmo mapa
            if (targetMap != null) {
                // Cria um requirement temporário para resolver o mapa
                GameMap questMap = mapResolver.resolveMapFromText(objectiveText);
                if (questMap != null && questMap.getId() != targetMap.getId()) {
                    sameMap = false; // esta quest é de outro mapa, não marcar NPCs
                }
            }

            if (!sameMap) continue;

            // Tenta matching com strict=false primeiro (mais flexível), depois strict=true
            boolean matched = false;
            for (Map.Entry<String, NpcInfo> npcEntry : npcInfos.entrySet()) {
                String npcName = npcBoxMatcher.getNpcName(npcEntry);
                if (npcName == null || npcName.isEmpty()) continue;

                // Tenta matching flexível primeiro
                if (npcBoxMatcher.npcMatchesQuestDesc(npcName, objectiveText, false)) {
                    desiredNpcKeys.add(npcEntry.getKey());
                    matched = true;
                    if (logMatches) {
                        logger.appendPluginLog("[QuestModule] MATCH SECUNDARIA (flex): '" + questTitle + "' -> '" + npcName + "' usando '" + objectiveText + "'");
                    }
                }
            }

            // Se não encontrou com flexível, tenta com strict=true (mais preciso)
            if (!matched) {
                for (Map.Entry<String, NpcInfo> npcEntry : npcInfos.entrySet()) {
                    String npcName = npcBoxMatcher.getNpcName(npcEntry);
                    if (npcName == null || npcName.isEmpty()) continue;
                    if (npcBoxMatcher.npcMatchesQuestDesc(npcName, objectiveText, true)) {
                        desiredNpcKeys.add(npcEntry.getKey());
                        matched = true;
                        if (logMatches) {
                            logger.appendPluginLog("[QuestModule] MATCH SECUNDARIA (strict): '" + questTitle + "' -> '" + npcName + "' usando '" + objectiveText + "'");
                        }
                    }
                }
            }
        }
        // Throttle: este log era emitido a cada tick (~40ms) gerando spam no console.
        // Agora só repete no máximo a cada 10s para a mesma contagem.
        logger.logDebugThrottled("Quest secundária processadas no mapa: " + totalSecondary, 10000L);
    }

    private void markAlwaysCollectOres(Map<String, BoxInfo> boxInfos, Set<String> desiredBoxKeys, Quest quest) {
        // Carrega as chaves de minério de evento da config (com cache)
        if (ctx.alwaysCollectOreKeysCache == null && ctx.config != null && ctx.config.loot != null) {
            ctx.alwaysCollectOreKeysCache = new HashSet<>();
            String raw = ctx.config.loot.eventOreKeys;
            if (raw != null && !raw.isEmpty()) {
                for (String key : raw.split(",")) {
                    String trimmed = key.trim().toLowerCase();
                    if (!trimmed.isEmpty()) ctx.alwaysCollectOreKeysCache.add(trimmed);
                }
            }
        }
        if (ctx.alwaysCollectOreKeysCache == null || ctx.alwaysCollectOreKeysCache.isEmpty()) return;

        // CORREÇÃO: só marca o minério para coleta se houver uma quest ativa que
        // realmente peça esse minério (igual ao comportamento dos NPCs, que só são
        // marcados quando a quest os exige). Antes, o alwaysCollectEventOres marcava
        // TODOS os minérios de evento incondicionalmente, fazendo o bot coletar
        // terbium/prometid/scrapium etc. mesmo sem missão de minério.
        Set<String> questOreKeys = collectQuestOreKeys(quest);
        if (questOreKeys.isEmpty()) return;

        for (Map.Entry<String, BoxInfo> entry : boxInfos.entrySet()) {
            String key = entry.getKey();
            if (key == null) continue;
            String lowered = key.toLowerCase();
            if (!isBoxAllowedByLootConfig(lowered)) continue;
            for (String oreKey : ctx.alwaysCollectOreKeysCache) {
                // Só marca se a quest ativa pede este minério E a caixa corresponde a ele
                if (questOreKeys.contains(oreKey) && lowered.contains(oreKey)) {
                    desiredBoxKeys.add(entry.getKey());
                    break;
                }
            }
        }
    }

    /**
     * Coleta os nomes de minério (das chaves de evento configuradas) que são
     * exigidos por alguma quest ativa: a quest em exibição (displayed) e as quests
     * secundárias já aceitas (via título -> texto de objetivo). Assim o bot só
     * marca para coleta os minérios que realmente fazem parte de uma missão.
     */
    private Set<String> collectQuestOreKeys(Quest quest) {
        Set<String> oreKeys = new HashSet<>();
        if (ctx.alwaysCollectOreKeysCache == null) return oreKeys;

        // 1) Quest em exibição (a que está sendo executada agora)
        if (quest != null) {
            for (Requirement r : quest.getRequirements()) {
                if (r.isCompleted()) continue;
                String desc = MissionMapLoader.normalize(r.getDescription());
                for (String oreKey : ctx.alwaysCollectOreKeysCache) {
                    if (desc.contains(oreKey)) oreKeys.add(oreKey);
                }
            }
        }

        // 2) Quests secundárias aceitas (mantidas marcadas) — resolve o objetivo
        //    pelo título para checar se pedem algum minério de evento.
        for (Map.Entry<Integer, String> e : ctx.acceptedQuestTitleCache.entrySet()) {
            if (quest != null && e.getKey() != null && e.getKey() == quest.getId()) continue;
            String title = e.getValue();
            if (title == null || title.isEmpty()) continue;
            String objective = missionMapLoader.resolveQuestObjectiveText(title, true);
            if (objective == null || objective.isEmpty()) objective = title;
            String norm = MissionMapLoader.normalize(objective);
            for (String oreKey : ctx.alwaysCollectOreKeysCache) {
                if (norm.contains(oreKey)) oreKeys.add(oreKey);
            }
        }

        return oreKeys;
    }

    // Detects a profile switch and resets the incremental tracking state so the next
    // update recomputes marks from scratch for the current profile (no cross-profile unmarking).
    void ensureProfileTracking() {
        String currentProfile = ctx.configAPI.getCurrentProfile();
        if (currentProfile == null) return;
        if (ctx.lastProfileName == null) {
            ctx.lastProfileName = currentProfile;
        } else if (!ctx.lastProfileName.equals(currentProfile)) {
            logger.logDebug("[QuestModule] Perfil mudou de '" + ctx.lastProfileName + "' para '" + currentProfile
                    + "'. Resetando estado de marcacao (sem desmarcar configs do outro perfil).");
            resetTrackingState();
            ctx.lastProfileName = currentProfile;
        }
    }

    // Reset tracking state WITHOUT touching the config (used when the active profile changes,
    // so we don't unmark settings that belong to a different profile).
    void resetTrackingState() {
        ctx.lastAppliedNpcKeys.clear();
        ctx.lastAppliedBoxKeys.clear();
        ctx.lastDesiredNpcKeys.clear();
        ctx.lastDesiredBoxKeys.clear();
        ctx.lastRequirementState = "";
        ctx.lastTargetMapId = null;
    }

    Map<String, NpcInfo> getNpcInfos() {
        // Try multiple path variants: DarkBot stores the DB under "LOOT.NPC_INFOS"
        // (uppercase) but some setups use "loot.npc_infos". ConfigAPI is case-sensitive
        // on the path, so we must try both to avoid returning null (which would prevent
        // any NPC from being marked for a quest).
        String[] paths = {"LOOT.NPC_INFOS", "loot.NPC_INFOS", "loot.npc_infos"};
        for (String path : paths) {
            try {
                ConfigSetting<Map<String, NpcInfo>> setting = ctx.configAPI.requireConfig(path);
                Map<String, NpcInfo> result = setting.getValue();
                if (result != null && !result.isEmpty()) {
                    // Throttle: este log era emitido a cada tick (~40ms) gerando spam.
                    // Agora só repete no máximo a cada 10s para a mesma mensagem.
                    logger.logDebugThrottled("getNpcInfos() [" + path + "] -> " + result.size() + " NPCs", 10000L);
                    return result;
                }
            } catch (Exception ignored) {}
        }
        if (logger.isVerboseLoggingEnabled()) {
            System.err.println("[QuestModule] getNpcInfos() ERRO: nenhum caminho de NPC_INFOS resolveu (tentou: "
                    + String.join(", ", paths) + ")");
        }
        return null;
    }

    private Map<String, BoxInfo> getBoxInfos() {
        String[] paths = {"COLLECT.BOX_INFOS", "collect.BOX_INFOS", "collect.box_infos"};
        for (String path : paths) {
            try {
                ConfigSetting<Map<String, BoxInfo>> setting = ctx.configAPI.requireConfig(path);
                Map<String, BoxInfo> result = setting.getValue();
                if (result != null && !result.isEmpty()) {
                    return result;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }
}
