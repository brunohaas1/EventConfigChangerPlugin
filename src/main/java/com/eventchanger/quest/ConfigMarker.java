package com.eventchanger.quest;

import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.config.types.BoxInfo;
import eu.darkbot.api.config.types.NpcInfo;
import eu.darkbot.api.game.other.GameMap;
import eu.darkbot.api.managers.QuestAPI;
import eu.darkbot.api.managers.QuestAPI.Quest;
import eu.darkbot.api.managers.QuestAPI.QuestListItem;
import eu.darkbot.api.managers.QuestAPI.Requirement;

import java.util.HashMap;
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
    private static final Map<String, Double> NPC_DEFAULT_RADII = new HashMap<>();
    static {
        // X-1 TO X-4 MAPS
        NPC_DEFAULT_RADII.put("streuner", 450.0);
        NPC_DEFAULT_RADII.put("lordakia", 450.0);
        NPC_DEFAULT_RADII.put("saimon", 500.0);
        NPC_DEFAULT_RADII.put("mordon", 500.0);
        NPC_DEFAULT_RADII.put("devolarium", 536.0);
        NPC_DEFAULT_RADII.put("sibelon", 530.0);
        NPC_DEFAULT_RADII.put("boss streuner", 450.0);
        NPC_DEFAULT_RADII.put("boss lordakia", 450.0);
        NPC_DEFAULT_RADII.put("boss saimon", 500.0);
        NPC_DEFAULT_RADII.put("boss mordon", 520.0);
        NPC_DEFAULT_RADII.put("boss devolarium", 575.0);
        NPC_DEFAULT_RADII.put("boss sibelon", 570.0);

        // X-5 TO X-8 MAPS
        NPC_DEFAULT_RADII.put("sibelonit", 575.0);
        NPC_DEFAULT_RADII.put("lordakium", 610.0);
        NPC_DEFAULT_RADII.put("kristallin", 575.0);
        NPC_DEFAULT_RADII.put("kristallon", 600.0);
        NPC_DEFAULT_RADII.put("strauner", 600.0);
        NPC_DEFAULT_RADII.put("cubicon", 525.0);
        NPC_DEFAULT_RADII.put("protegit", 525.0);
        NPC_DEFAULT_RADII.put("boss sibelonit", 575.0);
        NPC_DEFAULT_RADII.put("boss lordakium", 625.0);
        NPC_DEFAULT_RADII.put("boss kristallin", 575.0);
        NPC_DEFAULT_RADII.put("boss kristallon", 615.0);
        NPC_DEFAULT_RADII.put("boss strauner", 600.0);

        // UBER MAP
        NPC_DEFAULT_RADII.put("uber streuner", 500.0);
        NPC_DEFAULT_RADII.put("uber lordakia", 500.0);
        NPC_DEFAULT_RADII.put("uber saimon", 500.0);
        NPC_DEFAULT_RADII.put("uber simon", 500.0);
        NPC_DEFAULT_RADII.put("uber mordon", 580.0);
        NPC_DEFAULT_RADII.put("uber devolarium", 500.0);
        NPC_DEFAULT_RADII.put("uber sibelon", 600.0);
        NPC_DEFAULT_RADII.put("uber sibelonit", 575.0);
        NPC_DEFAULT_RADII.put("uber lordakium", 600.0);
        NPC_DEFAULT_RADII.put("uber kristallin", 580.0);
        NPC_DEFAULT_RADII.put("uber kristallon", 625.0);
        NPC_DEFAULT_RADII.put("uber strauner", 580.0);

        // BL MAPS
        NPC_DEFAULT_RADII.put("attend ix", 670.0);
        NPC_DEFAULT_RADII.put("impulse ii", 615.0);

        // PIRATE MAP 5-2
        NPC_DEFAULT_RADII.put("saboteur", 580.0);
        NPC_DEFAULT_RADII.put("interceptor", 500.0);
        NPC_DEFAULT_RADII.put("barracuda", 560.0);
        NPC_DEFAULT_RADII.put("annihilator", 575.0);

        // MIMESIS EVENT
        NPC_DEFAULT_RADII.put("raging mimes1s", 590.0);
        NPC_DEFAULT_RADII.put("kamikaze mime5is", 580.0);
        NPC_DEFAULT_RADII.put("cloning mim3sis", 590.0);
        NPC_DEFAULT_RADII.put("cloned mim3sis", 590.0);
        NPC_DEFAULT_RADII.put("terror mime5is", 610.0);
        NPC_DEFAULT_RADII.put("hexor m1mesis", 590.0);
        NPC_DEFAULT_RADII.put("reflector mimesi5", 670.0);
    }

    private Double getCorrectRadius(String npcKey) {
        String norm = MissionMapLoader.normalize(npcKey);
        // Direct match
        Double radius = NPC_DEFAULT_RADII.get(norm);
        if (radius != null) return radius;

        // Fallback: partial match
        for (Map.Entry<String, Double> entry : NPC_DEFAULT_RADII.entrySet()) {
            if (norm.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

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

        if (targetMap == null) {
            unmarkAll();
            return;
        }

        long now = System.currentTimeMillis();
        boolean forceUpdate = (ctx.lastTargetMapId == null) || (targetMap.getId() != ctx.lastTargetMapId);

        // Rastreamento de chamadas para updateConfigForQuest
        // System.out.println("[UPDATE_CONFIG_FOR_QUEST] quest='" + (quest != null ? quest.getTitle() : "null")
        //         + "' | targetMap=" + (targetMap != null ? targetMap.getName() : "null")
        //         + " | caller: updateConfigForQuest() em com.eventchanger.quest.ConfigMarker");

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
                // CORREÇÃO: usa resolveQuestTargetMap(quest, r) em vez de
                // resolveTargetMap(r). O resolveTargetMap() resolve cada requirement
                // isoladamente e, para minérios sem mapa no texto, cai no hardcode
                // ore->mapa (ex: Terbium = 1-2), divergindo do targetMap real da quest
                // (que veio de um requirement irmão "no mapa 1-3."). Isso fazia o
                // requirement de minério ser descartado no filtro abaixo e a caixa
                // nunca ser marcada. O resolveQuestTargetMap() aplica a mesma
                // prioridade "mapa explícito de qualquer requirement da quest > hardcode"
                // usada na navegação, então o rMap da Terbium também vira 1-3 e bate
                // com targetMap.
                GameMap rMap = mapResolver.resolveQuestTargetMap(quest, r);
                reqMapCache.put(r, rMap);
                if (rMap == null || rMap.getId() != targetMap.getId()) {
                    reqState.append(r.getDescription()).append(r.getProgress()).append("|");
                }
            }
        } else {
            reqState.append("null-quest").append(targetMap.getId());
        }
        String currentReqState = reqState.toString();

        // [FLOW] 4) Dentro de updateConfigForQuest, após avaliar cada requirement
        // if (quest != null) {
        //     StringBuilder flowReqs = new StringBuilder();
        //     for (Requirement r : quest.getRequirements()) {
        //         if (r.isCompleted()) continue;
        //         GameMap rMap = reqMapCache.get(r);
        //         flowReqs.append("\n    req='").append(r.getDescription()).append("'")
        //                 .append(" | rMap=").append(rMap != null ? rMap.getName() : "null")
        //                 .append(" | rMap==targetMap=").append(rMap != null && rMap.getId() == targetMap.getId());
        //     }
        //     System.out.println("[FLOW] updateConfigForQuest:"
        //             + "\n  quest=" + quest.getTitle()
        //             + "\n  targetMap_recebido=" + (targetMap != null ? targetMap.getName() : "null")
        //             + "\n  requirements_avaliados:" + flowReqs);
        // }

        boolean stateChanged = !currentReqState.equals(ctx.lastRequirementState);

        if (stateChanged) {
            ctx.lastRequirementState = currentReqState;
        }

        // THROTTLE: Se o estado dos requisitos não mudou e o mapa é o mesmo,
        // só atualiza o ConfigAPI a cada 500ms para evitar alto custo de processamento.
        if (!forceUpdate && !stateChanged && now - ctx.lastConfigUpdateRunTime < 500) {
            return;
        }
        ctx.lastConfigUpdateRunTime = now;

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
                } else if ((isLootType(type) || type == Requirement.RequirementType.SELL_ORE) && boxInfos != null) {
                    String desc = r.getDescription();
                    String cleanBoxName = ctx.normalizedDescCache.get(desc);
                    if (cleanBoxName == null) {
                        cleanBoxName = MissionMapLoader.normalize(desc).toLowerCase();
                        ctx.normalizedDescCache.put(desc, cleanBoxName);
                    }
                    for (Map.Entry<String, BoxInfo> entry : boxInfos.entrySet()) {
                        String boxName = entry.getKey() != null ? entry.getKey().toLowerCase() : "";
                        if (npcBoxMatcher.matchesBoxName(boxName, cleanBoxName)) {
                            desiredBoxKeys.add(entry.getKey());
                        }
                    }
                }
            }
        }

        // CORREÇÃO (minérios de carga): se a quest ativa pede Prometid/Duranium/
        // Promerium (só vêm de caixas from_ship), marca TODOS os NPCs do mapa
        // alvo para que o bot mate NPCs e colete a carga dropada. O requirement é
        // do tipo COLLECT (não KILL), então o loop acima não marcaria nenhum NPC.
        if (quest != null && targetMap != null && npcInfos != null
                && hasOreFromShipRequirement(quest)) {
            markAllNpcsOnMap(targetMap, npcInfos, desiredNpcKeys);
            // Também marca as caixas de carga (from_ship) para o bot coletá-las
            // ao matar os NPCs. Sem isso, o bot mata os NPCs mas não recolhe a
            // carga dropada (que contém os minérios da missão).
            if (boxInfos != null) {
                markAllCargoBoxes(boxInfos, desiredBoxKeys);
            }
        }

        // Keep secondary accepted quests marked only when enabled
        if (npcInfos != null && ctx.config != null && QuestConfig.QuestFlowConfig.KEEP_SECONDARY_QUESTS_MARKED) {
            markSecondaryAcceptedQuestNpcs(npcInfos, quest, desiredNpcKeys, targetMap, stateChanged);
        }

        if (boxInfos != null && ctx.config != null && ctx.config.loot.alwaysCollectEventOres) {
            markAlwaysCollectOres(boxInfos, desiredBoxKeys, quest);
        }


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

        // Gerenciamento de raio dinâmico: se estamos atacando, usa o raio correto recomendado.
        // Se NÃO estamos atacando, diminui o raio para 400 para aproximar do target para o primeiro tiro.
        if (npcInfos != null) {
            boolean isAttacking = ctx.attackAPI.isAttacking();
            for (String key : ctx.lastAppliedNpcKeys) {
                NpcInfo info = npcInfos.get(key);
                if (info != null) {
                    Double correctRadius = getCorrectRadius(key);
                    if (correctRadius != null) {
                        double targetRadius = (isAttacking && correctRadius > 450.0) ? correctRadius : Math.min(400.0, correctRadius);
                        if (Math.abs(info.getRadius() - targetRadius) > 1.0) {
                            info.setRadius(targetRadius);
                        }
                    }
                }
            }
        }

        if (stateChanged && npcInfos != null && desiredNpcKeys.isEmpty()) {
            String questTitle = quest != null ? String.valueOf(quest.getTitle()) : "null";
            String reqInfo = ctx.currentReq != null
                    ? (ctx.currentReq.getRequirementType() + " | " + String.valueOf(ctx.currentReq.getDescription()))
                    : "null";
            logger.appendPluginLog("[QuestModule] nenhum NPC marcado | quest='" + questTitle
                    + "' | targetMap='" + targetMap.getName() + "' | req='" + reqInfo
                    + "' | reqState='" + currentReqState + "'");
        }

        // PET Enemy Locator logic for Boss / Uber
        boolean isBossOrUberKill = false;
        if (ctx.config != null && ctx.config.pet.useLocatorForBossUber && ctx.currentReq != null) {
            Requirement.RequirementType type = ctx.currentReq.getRequirementType();
            if (isKillType(type) && ctx.currentReq.getDescription() != null) {
                String descLower = ctx.currentReq.getDescription().toLowerCase();
                isBossOrUberKill = descLower.contains("boss") || descLower.contains("uber");
            }
        }

        if (isBossOrUberKill) {
            enablePetLocator();
        } else {
            disablePetLocatorIfEnabled();
        }
    }

    private void enablePetLocator() {
        if (ctx.petAPI == null) return;
        try {
            if (!ctx.hasSavedOriginalPetState) {
                ctx.originalPetEnabled = ctx.petAPI.isEnabled();
                ctx.originalPetGear = ctx.petAPI.getGear();
                ctx.hasSavedOriginalPetState = true;
                logger.logDebug("Salvando estado original do PET: enabled=" + ctx.originalPetEnabled + ", gear=" + ctx.originalPetGear);
            }
            
            if (!ctx.petAPI.isEnabled()) {
                ctx.petAPI.setEnabled(true);
            }
            if (ctx.petAPI.getGear() != eu.darkbot.api.game.enums.PetGear.ENEMY_LOCATOR) {
                ctx.petAPI.setGear(eu.darkbot.api.game.enums.PetGear.ENEMY_LOCATOR);
                logger.logDebug("Ativando Enemy Locator no PET para buscar Boss/Uber");
            }
        } catch (Exception e) {
            logger.logDebug("Erro ao ativar localizador de PET: " + e.getMessage());
        }
    }

    private void disablePetLocatorIfEnabled() {
        if (ctx.petAPI == null) return;
        if (ctx.hasSavedOriginalPetState) {
            try {
                if (ctx.petAPI.isEnabled() != ctx.originalPetEnabled) {
                    ctx.petAPI.setEnabled(ctx.originalPetEnabled);
                }
                if (ctx.originalPetGear != null && ctx.petAPI.getGear() != ctx.originalPetGear) {
                    ctx.petAPI.setGear(ctx.originalPetGear);
                }
                logger.logDebug("Restaurando estado original do PET: enabled=" + ctx.originalPetEnabled + ", gear=" + ctx.originalPetGear);
            } catch (Exception e) {
                logger.logDebug("Erro ao restaurar PET: " + e.getMessage());
            }
            ctx.hasSavedOriginalPetState = false;
        }
    }

    private void applyDesiredNpcMarksIncremental(Map<String, NpcInfo> npcInfos, Set<String> desiredNpcKeys) {
        if (npcInfos == null) return;

        if (!desiredNpcKeys.isEmpty()) {
            // 1. Save original enabled NPCs if we haven't done so yet.
            if (!ctx.hasSavedOriginalNpcs) {
                ctx.originalEnabledNpcs.clear();
                for (Map.Entry<String, NpcInfo> entry : npcInfos.entrySet()) {
                    if (entry.getValue() != null && entry.getValue().getShouldKill()) {
                        ctx.originalEnabledNpcs.add(entry.getKey());
                    }
                }
                ctx.hasSavedOriginalNpcs = true;
            }

            // 2. Set only desired NPCs to true, and all others to false.
            for (Map.Entry<String, NpcInfo> entry : npcInfos.entrySet()) {
                String key = entry.getKey();
                NpcInfo info = entry.getValue();
                if (info != null) {
                    boolean shouldKill = desiredNpcKeys.contains(key);
                    if (info.getShouldKill() != shouldKill) {
                        info.setShouldKill(shouldKill);
                        if (!shouldKill) {
                            Double correctRadius = getCorrectRadius(key);
                            if (correctRadius != null) {
                                info.setRadius(correctRadius);
                            }
                        }
                    }
                }
            }
        } else {
            // No active quest NPCs: restore user's original configuration if saved.
            if (ctx.hasSavedOriginalNpcs) {
                for (Map.Entry<String, NpcInfo> entry : npcInfos.entrySet()) {
                    String key = entry.getKey();
                    NpcInfo info = entry.getValue();
                    if (info != null) {
                        boolean shouldKill = ctx.originalEnabledNpcs.contains(key);
                        if (info.getShouldKill() != shouldKill) {
                            info.setShouldKill(shouldKill);
                        }
                        Double correctRadius = getCorrectRadius(key);
                        if (correctRadius != null) {
                            info.setRadius(correctRadius);
                        }
                    }
                }
                ctx.originalEnabledNpcs.clear();
                ctx.hasSavedOriginalNpcs = false;
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

    void unmarkAll() {
        Map<String, NpcInfo> npcInfos = getNpcInfos();
        if (npcInfos != null) {
            if (ctx.hasSavedOriginalNpcs) {
                for (Map.Entry<String, NpcInfo> entry : npcInfos.entrySet()) {
                    String key = entry.getKey();
                    NpcInfo info = entry.getValue();
                    if (info != null) {
                        boolean shouldKill = ctx.originalEnabledNpcs.contains(key);
                        if (info.getShouldKill() != shouldKill) {
                            info.setShouldKill(shouldKill);
                        }
                        Double correctRadius = getCorrectRadius(key);
                        if (correctRadius != null) {
                            info.setRadius(correctRadius);
                        }
                    }
                }
                ctx.originalEnabledNpcs.clear();
                ctx.hasSavedOriginalNpcs = false;
            } else {
                for (String key : ctx.lastAppliedNpcKeys) {
                    NpcInfo info = npcInfos.get(key);
                    if (info != null && info.getShouldKill()) {
                        info.setShouldKill(false);
                    }
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
        disablePetLocatorIfEnabled();
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
                || t == Requirement.RequirementType.COLLECT
                || t == Requirement.RequirementType.CARGO;
    }

    /**
     * Verifica se a quest tem algum requirement de coleta dos 3 minérios de carga
     * (Prometid/Duranium/Promerium), que só vêm de caixas from_ship.
     */
    private boolean hasOreFromShipRequirement(Quest quest) {
        if (quest == null) return false;
        // Só marca todos os NPCs do mapa e as caixas from_ship se o requirement ATUAL
        // ativo for a coleta de minérios. Se a tarefa atual for matar um NPC específico
        // (ex: Kristallon), o loop de requirements principal já ativa o NPC correto,
        // e o requirement de coleta de minério já ativa as caixas from_ship correspondentes
        // no loop isLootType. Assim evitamos marcar todos os NPCs do mapa erroneamente.
        return ctx.currentReq != null && QuestContext.isOreFromShipQuest(ctx.currentReq);
    }

    /**
     * Marca TODOS os NPCs do mapa informado (targetMap) para serem caçados. Usado
     * para as quests de minério de carga, onde o bot precisa matar NPCs e coletar
     * a carga dropada (from_ship). O requirement é COLLECT, então o matching por
     * descrição não marcaria nenhum NPC — aqui marcamos o mapa inteiro.
     */
    private void markAllNpcsOnMap(GameMap targetMap, Map<String, NpcInfo> npcInfos, Set<String> desiredNpcKeys) {
        if (targetMap == null || npcInfos == null) return;
        int marked = 0;
        for (Map.Entry<String, NpcInfo> entry : npcInfos.entrySet()) {
            NpcInfo info = entry.getValue();
            if (info == null) continue;
            Set<Integer> mapIds = info.getMapIds();
            if (mapIds != null && mapIds.contains(targetMap.getId())) {
                desiredNpcKeys.add(entry.getKey());
                marked++;
            }
        }
        logger.logDebug("[OreFromShip] marcados " + marked + " NPCs do mapa " + targetMap.getName()
                + " para coleta de minerio de carga");
    }

    /**
     * Marca TODAS as caixas de carga (from_ship) para coleta. Usado junto com
     * markAllNpcsOnMap nas quests de minério de carga: ao matar os NPCs, a carga
     * dropada (que contém Prometid/Duranium/Promerium) precisa ser coletada.
     * Respeita o toggle collectCargoBoxes da config de loot.
     */
    private void markAllCargoBoxes(Map<String, BoxInfo> boxInfos, Set<String> desiredBoxKeys) {
        if (boxInfos == null) return;
        int marked = 0;
        for (Map.Entry<String, BoxInfo> entry : boxInfos.entrySet()) {
            String boxName = entry.getKey() != null ? entry.getKey().toLowerCase() : "";
            if (boxName.contains("from_ship") || boxName.contains("cargo")) {
                desiredBoxKeys.add(entry.getKey());
                marked++;
            }
        }
        logger.logDebug("[OreFromShip] marcadas " + marked + " caixas de carga (from_ship) para coleta");
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
        // Tenta múltiplos caminhos pois o DarkBot pode registrar as BoxInfos
        // em paths diferentes dependendo da build. Os NPCs usam LOOT.NPC_INFOS,
        // então as boxes podem usar LOOT.BOX_INFOS também. Testamos COLLECT.*
        // e LOOT.* em todos os cases.
        String[] paths = {
            "COLLECT.BOX_INFOS", "collect.BOX_INFOS", "collect.box_infos",
            "LOOT.BOX_INFOS",    "loot.BOX_INFOS",    "loot.box_infos"
        };
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
