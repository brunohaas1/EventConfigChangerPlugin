package com.eventchanger.quest;

import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.config.types.NpcInfo;
import eu.darkbot.api.game.entities.Portal;
import eu.darkbot.api.game.entities.Station;
import eu.darkbot.api.game.other.Area;
import eu.darkbot.api.game.other.EntityInfo.Faction;
import eu.darkbot.api.game.other.GameMap;
import eu.darkbot.api.managers.GameScreenAPI;
import eu.darkbot.api.managers.QuestAPI;
import eu.darkbot.api.managers.QuestAPI.Quest;
import eu.darkbot.api.managers.QuestAPI.Requirement;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Responsável por tudo que envolva mapas: resolver o mapa-alvo de uma quest a
 * partir de texto/descrição, resolver o mapa base (X-1 / X-8), navegar via
 * portais e manter o {@code general.working_map} do bot sincronizado.
 */
public class MapResolver {

    private final QuestContext ctx;
    private final QuestLogger logger;
    private final NpcBoxMatcher npcBoxMatcher;
    private final MissionMapLoader missionMapLoader;

    public MapResolver(QuestContext ctx, QuestLogger logger, NpcBoxMatcher npcBoxMatcher, MissionMapLoader missionMapLoader) {
        this.ctx = ctx;
        this.logger = logger;
        this.npcBoxMatcher = npcBoxMatcher;
        this.missionMapLoader = missionMapLoader;
    }

    public GameMap resolveTargetMap(Requirement req) {
        // Check forced working map first
        if (QuestConfig.NavigationConfig.FORCED_WORKING_MAP > 0) {
            Optional<GameMap> forced = ctx.starSystemAPI.findMap(QuestConfig.NavigationConfig.FORCED_WORKING_MAP);
            if (forced.isPresent() && !isMapBlacklisted(forced.get())) {
                logger.logDebug("Usando mapa de trabalho forçado: " + forced.get().getName() + " (id=" + QuestConfig.NavigationConfig.FORCED_WORKING_MAP + ")");
                return forced.get();
            }
        }

        // CORREÇÃO: Só redireciona para o mapa Uber se o requirement ATUAL (req) também for do tipo Uber.
        // Antes, o redirecionamento era aplicado a TODOS os requirements de uma quest Uber ativa,
        // mesmo depois que os NPCs do mapa Uber já estavam completos e o requirement atual era
        // de outro mapa (ex: Sibelon em 1-4). Isso fazia o bot ficar preso no 4-5 sem nunca trocar.
        Quest activeQuest = ctx.questAPI.getDisplayedQuest();
        if (activeQuest != null && req != null) {
            String uberReqDesc = req.getDescription();
            boolean reqIsUber = uberReqDesc != null && MissionMapLoader.normalize(uberReqDesc).contains("uber");
            // Fallback: se a descrição do requirement não contém "uber", verifica o título da quest
            // com getUberMapForQuest apenas SE o requirement também for do tipo NPC-kill compatível.
            if (reqIsUber || getUberMapForQuest(activeQuest) != null) {
                // Se o requirement atual NÃO contém "uber" no texto, não redireciona para o mapa Uber
                if (!reqIsUber) {
                    // Deixa o fluxo continuar e resolver o mapa normalmente pela descrição do requirement
                    logger.logDebug("resolveTargetMap: quest Uber ativa mas requirement atual nao e Uber ("
                            + (uberReqDesc != null ? uberReqDesc : "null") + "). Ignorando redirecionamento Uber.");
                } else {
                    GameMap uberMap = getUberMapForQuest(activeQuest);
                    if (uberMap != null && !isMapBlacklisted(uberMap)) {
                        return uberMap;
                    }
                }
            }
        }

        // Quests PVP (kill/damage players): usam o mapa configurado em pvp.pvpMap.
        // A descrição ("kill X players") não casa com NPC/ore, então sem isso o
        // targetMap ficaria nulo e o bot não saberia para onde ir.
        Requirement.RequirementType rType = req.getRequirementType();
        if (rType == Requirement.RequirementType.KILL_PLAYERS
                || rType == Requirement.RequirementType.DAMAGE_PLAYERS
                || rType == Requirement.RequirementType.DAMAGE_ENEMY_PLAYERS
                || rType == Requirement.RequirementType.KILL_ANY) {
            GameMap pvpMap = resolvePvpMap();
            if (pvpMap != null && !isMapBlacklisted(pvpMap)) {
                return pvpMap;
            }
        }

        // CORREÇÃO DO BUG "não troca de mapa após matar NPCs": muitas descrições de
        // quest citam o mapa explicitamente (ex: "Destroy Mordon 0/15 on your X-4 map",
        // "Travel to 4-5 map", "Destroy Lordakia 0/30 on 1-3 map", "maps: 1-3, 2-3, 3-3").
        // As regras hardcoded de NPC->mapa (abaixo) muitas vezes apontam para o mapa
        // ERRADO (Mordon hardcoded é X-3, mas a quest diz X-4), fazendo o bot ficar no
        // mapa errado e nunca trocar. Extraímos o mapa citado na descrição com prioridade.
        String reqDesc = req.getDescription();
        if (reqDesc != null && !reqDesc.isEmpty()) {
            GameMap explicitMap = extractMapFromDescription(reqDesc);
            if (explicitMap != null && !isMapBlacklisted(explicitMap)) {
                logger.logDebug("resolveTargetMap: mapa explicito extraido da descricao '"
                        + reqDesc + "' -> " + explicitMap.getName());
                return explicitMap;
            }
        }

        // CORREÇÃO DO BUG "Lordakia 1-3 foi para o 1-2": a descrição do requirement
        // (req.getDescription) muitas vezes traz só a 1ª linha ("Destroi Lordakia"),
        // enquanto o mapa ("no mapa 1-3") fica na 2ª linha ou no título/objetivo da
        // missão. Como o extractMapFromDescription acima não achou o mapa na descrição,
        // o bot caía no banco/hardcoded que dizem Lordakia = 1-2. Agora tentamos
        // também o TÍTULO da quest ativa e o OBJETIVO resolvido (mapa externo PT),
        // nesta ordem de prioridade. O primeiro que citar um mapa válido vence.
        if (activeQuest != null) {
            GameMap titleMap = extractMapFromDescription(String.valueOf(activeQuest.getTitle()));
            if (titleMap != null && !isMapBlacklisted(titleMap)) {
                logger.logDebug("resolveTargetMap: mapa explicito extraido do TITULO '"
                        + activeQuest.getTitle() + "' -> " + titleMap.getName());
                return titleMap;
            }
            String objective = missionMapLoader.resolveQuestObjectiveText(
                    String.valueOf(activeQuest.getTitle()), false);
            if (objective != null && !objective.isEmpty()) {
                // CORREÇÃO SEQUENCIAL: Em vez de extrair o mapa de todo o texto do objetivo
                // (que pode conter mapas de outros requisitos futuros no mesmo texto),
                // filtramos apenas a linha correspondente ao requisito atual (reqDesc).
                String matchingLine = getMatchingObjectiveLine(objective, reqDesc);
                if (matchingLine != null) {
                    GameMap objMap = extractMapFromDescription(matchingLine);
                    if (objMap != null && !isMapBlacklisted(objMap)) {
                        logger.logDebug("resolveTargetMap: mapa explicito extraido da linha correspondente do OBJETIVO '"
                                + matchingLine + "' -> " + objMap.getName());
                        return objMap;
                    }
                } else {
                    // Fallback se não casar linha específica, mas apenas se o objetivo for curto / mapa único
                    if (!objective.contains("|")) {
                        GameMap objMap = extractMapFromDescription(objective);
                        if (objMap != null && !isMapBlacklisted(objMap)) {
                            logger.logDebug("resolveTargetMap: mapa explicito extraido do OBJETIVO (sem linhas) '"
                                    + objective + "' -> " + objMap.getName());
                            return objMap;
                        }
                    }
                }
            }
        }
        if (reqDesc == null || reqDesc.isEmpty()) {
            // Collection requirements can be fulfilled on the current map even without a description
            if (isLootType(req.getRequirementType())) {
                GameMap currentMap = ctx.heroAPI.getMap();
                if (currentMap != null) return currentMap;
            }
            return null;
        }

        String normalized = MissionMapLoader.normalize(reqDesc);

        // 3. RESOLUÇÃO AUTOMÁTICA via banco de NPCs do DarkBot (loot.npc_infos).
        //    Cada NPC tem seus mapas reais (getMapIds()), então o bot vai para o
        //    mapa CORRETO do jogo sem depender de regras hardcoded manuais (que
        //    causaram bugs como Lordakia->1-7 e Sibelonit->1-4 por colisão de
        //    substring). Esta é a fonte PRIMÁRIA; as regras hardcoded abaixo são
        //    apenas fallback para quando o nome do NPC não casa com o BD.
        String[] paths = {"loot.NPC_INFOS", "loot.npc_infos"};
        for (String path : paths) {
            try {
                Set<String> npcKeys = ctx.configAPI.getChildren(path);
                if (npcKeys == null || npcKeys.isEmpty()) continue;

                for (String key : npcKeys) {
                    ConfigSetting<NpcInfo> setting = ctx.configAPI.requireConfig(path + "." + key);
                    NpcInfo info = setting.getValue();
                    if (info == null) continue;
                    String npcName = info.getName();
                    if (npcName == null || npcName.isEmpty()) {
                        npcName = key;
                    }

                    if (npcBoxMatcher.npcMatchesQuestDesc(npcName, reqDesc)) {
                        Set<Integer> mapIds = info.getMapIds();
                        if (mapIds != null && !mapIds.isEmpty()) {
                            // CORREÇÃO DO BUG "Saimon 3-3 foi para 2-3": antes usávamos
                            // mapIds.iterator().next(), que retornava o PRIMEIRO mapa do
                            // HashSet do NPC (ordem arbitrária). Para Saimon o set é
                            // {1-3, 2-3, 3-3} e o .next() podia retornar 2-3, mandando o
                            // bot para o mapa errado quando a Quest NÃO informava o mapa
                            // no texto. Agora escolhemos o mapa MAIS PRÓXIMO / alcançável
                            // a partir do mapa atual (ver pickClosestNpcMap).
                            GameMap npcMap = pickClosestNpcMap(mapIds);
                            // Guarda de segurança: Boss/Uber NPCs nunca nascem em 1-1 (map ID 1),
                            // então se o banco de dados retornou 1-1 por falta de sufixo no config.json,
                            // ignoramos e deixamos o fallback resolver.
                            boolean isBossOrUber = npcName.toLowerCase().contains("boss") || npcName.toLowerCase().contains("uber");
                            if (isBossOrUber && npcMap != null && npcMap.getId() == 1) {
                                logger.logDebug("resolveTargetMap: ignorando mapa 1-1 do BD para Boss/Uber NPC '" + npcName + "'");
                                continue;
                            }
                            if (npcMap != null) {
                                logger.logDebug("resolveTargetMap: mapa automatico (mais proximo) via BD para '"
                                        + npcName + "' -> " + npcMap.getName());
                                return npcMap;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 4. FALLBACK: regras hardcoded de NPC->mapa (texto normalizado).
        //    Mantidas como rede de segurança para quando o nome do NPC não casa
        //    com o banco (ex: "boss kristallon" sem entrada exata). As regras de
        //    ore (prometium/duranium/osmium) também ficam aqui e continuam úteis.
        GameMap byTextRules = resolveMapFromNormalizedText(normalized, reqDesc, true);
        if (byTextRules != null) {
            return byTextRules;
        }

        // For collection requirements, stay on the current map instead of giving up with a
        // null targetMap (which would prevent box marking and leave the bot stuck).
        if (isLootType(req.getRequirementType())) {
            GameMap currentMap = ctx.heroAPI.getMap();
            if (currentMap != null) {
                logger.logDebug("resolveTargetMap: coleta sem mapa especifico, usando mapa atual: " + currentMap.getName());
                return currentMap;
            }
        }
        logger.logDebug("resolveTargetMap retornou nulo para requisito: " + reqDesc);
        return null;
    }

    /**
     * Resolve o mapa-alvo da QUEST INTEIRA (não só do requirement atual).
     *
     * CORREÇÃO DO BUG "bot vai para 1-2 em vez de 1-3 (Traficante de escravos)":
     * O makeDecision() escolhe um requirement via findBestRequirement() (ex:
     * "Destrói Lordakia") e usa resolveTargetMap(esseReq) como targetMap. Mas em
     * quests de mapa único (ex: "Traficante de escravos"), o mapa "no mapa 1-3"
     * está citado na descrição de OUTRO requirement da mesma quest, não na
     * descrição do requirement escolhido. Como resolveTargetMap("Destrói Lordakia")
     * isolado não acha o mapa na descrição, ele cai no hardcoded Lordakia = 1-2 e
     * o bot vai para 1-2 ignorando o 1-3 obrigatório.
     *
     * Agora priorizamos o mapa explícito citado em QUALQUER requirement não-
     * completado da quest ativa: se a quest cita "no mapa 1-3" em algum requirement,
     * esse é o targetMap da quest inteira (pois é uma quest de mapa único). Só
     * caímos no resolveTargetMap(reqAtual) (banco de NPCs / hardcoded) quando
     * nenhum requirement cita um mapa explicitamente.
     */
    public GameMap resolveQuestTargetMap(Quest quest, Requirement currentReq) {
        if (currentReq == null) {
            return null;
        }

        // 1) Se o requirement atual/ativo já cita um mapa explícito, usa ele.
        String curDesc = currentReq.getDescription();
        if (curDesc != null && !curDesc.isEmpty()) {
            GameMap curExplicit = extractMapFromDescription(curDesc);
            if (curExplicit != null && !isMapBlacklisted(curExplicit)) {
                return curExplicit;
            }
        }

        // 2) Procura mapa explícito em outros requisitos habilitados (ativos) e não completados
        //    (ex: requisitos paralelos de mapa como "no mapa 1-4")
        if (quest != null) {
            for (Requirement r : quest.getRequirements()) {
                if (r.isCompleted() || !r.isEnabled()) continue;
                String desc = r.getDescription();
                if (desc == null || desc.isEmpty()) continue;
                GameMap explicit = extractMapFromDescription(desc);
                if (explicit != null && !isMapBlacklisted(explicit)) {
                    logger.logDebug("resolveQuestTargetMap: usando mapa explicito do requisito ativo '"
                            + desc + "' -> " + explicit.getName()
                            + " para currentReq='" + currentReq.getDescription() + "'");
                    return explicit;
                }
            }
        }

        // 3) Resolver o mapa pelo NPC/ore do currentReq, otimizando para satisfazer múltiplos requisitos paralelos.
        java.util.Set<Integer> currentMapIds = getCandidateMapIdsForRequirement(currentReq);
        if (currentMapIds != null && !currentMapIds.isEmpty() && quest != null) {
            // Conta quantos outros requisitos não completados e habilitados também podem ser feitos em cada mapa
            java.util.Map<Integer, Integer> mapScores = new java.util.HashMap<>();
            int maxScore = 0;
            
            for (int mapId : currentMapIds) {
                int score = 0;
                for (Requirement r : quest.getRequirements()) {
                    if (r == currentReq || r.isCompleted() || !r.isEnabled()) continue;
                    java.util.Set<Integer> rMaps = getCandidateMapIdsForRequirement(r);
                    if (rMaps != null && rMaps.contains(mapId)) {
                        score++;
                    }
                }
                mapScores.put(mapId, score);
                if (score > maxScore) {
                    maxScore = score;
                }
            }
            
            // Filtra os mapas candidatos que atingiram a pontuação máxima de compatibilidade
            java.util.Set<Integer> bestMapIds = new java.util.HashSet<>();
            for (java.util.Map.Entry<Integer, Integer> entry : mapScores.entrySet()) {
                if (entry.getValue() == maxScore) {
                    bestMapIds.add(entry.getKey());
                }
            }
            
            // Escolhe o melhor mapa dentre os que possuem maior pontuação (mais próximo/alcançável)
            GameMap bestNpcMap = pickClosestNpcMap(bestMapIds);
            if (bestNpcMap != null) {
                logger.logDebug("resolveQuestTargetMap: mapa otimizado para multiplos requisitos (score=" + maxScore + ") -> " + bestNpcMap.getName() + " para currentReq='" + currentReq.getDescription() + "'");
                return bestNpcMap;
            }
        }

        // Fallback: resolve normalmente pelo requisito ativo
        return resolveTargetMap(currentReq);
    }

    public java.util.Set<Integer> getCandidateMapIdsForRequirement(Requirement req) {
        java.util.Set<Integer> mapIds = new java.util.HashSet<>();
        if (req == null) return mapIds;

        // 1. Explicit map in description
        String reqDesc = req.getDescription();
        if (reqDesc != null && !reqDesc.isEmpty()) {
            GameMap explicitMap = extractMapFromDescription(reqDesc);
            if (explicitMap != null && !isMapBlacklisted(explicitMap)) {
                mapIds.add(explicitMap.getId());
                return mapIds;
            }
        }

        // 2. NPC database matching
        String[] paths = {"loot.NPC_INFOS", "loot.npc_infos"};
        for (String path : paths) {
            try {
                Set<String> npcKeys = ctx.configAPI.getChildren(path);
                if (npcKeys == null || npcKeys.isEmpty()) continue;
                for (String key : npcKeys) {
                    ConfigSetting<NpcInfo> setting = ctx.configAPI.requireConfig(path + "." + key);
                    NpcInfo info = setting.getValue();
                    if (info == null) continue;
                    String npcName = info.getName();
                    if (npcName == null || npcName.isEmpty()) npcName = key;

                    if (npcBoxMatcher.npcMatchesQuestDesc(npcName, reqDesc)) {
                        Set<Integer> dbMapIds = info.getMapIds();
                        if (dbMapIds != null) {
                            for (int id : dbMapIds) {
                                boolean isBossOrUber = npcName.toLowerCase().contains("boss") || npcName.toLowerCase().contains("uber");
                                if (isBossOrUber && id == 1) continue; // Skip 1-1 for boss/uber
                                Optional<GameMap> mapOpt = ctx.starSystemAPI.findMap(id);
                                if (mapOpt.isPresent() && !isMapBlacklisted(mapOpt.get())) {
                                    mapIds.add(id);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. Fallback text rules
        if (mapIds.isEmpty() && reqDesc != null && !reqDesc.isEmpty()) {
            String normalized = MissionMapLoader.normalize(reqDesc);
            mapIds.addAll(getFallbackMapIdsForNpc(normalized));
        }

        return mapIds;
    }

    public java.util.Set<Integer> getFallbackMapIdsForNpc(String cleanReq) {
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        if (cleanReq == null || cleanReq.isEmpty()) return ids;

        // Determine company maps based on faction
        String x2 = "1-2", x3 = "1-3", x4 = "1-4", x5 = "1-5", x6 = "1-6", x7 = "1-7", x8 = "1-8";
        if (ctx.heroAPI.getEntityInfo() != null && ctx.heroAPI.getEntityInfo().getFaction() != null) {
            switch (ctx.heroAPI.getEntityInfo().getFaction()) {
                case EIC:
                    x2 = "2-2"; x3 = "2-3"; x4 = "2-4"; x5 = "2-5"; x6 = "2-6"; x7 = "2-7"; x8 = "2-8";
                    break;
                case VRU:
                    x2 = "3-2"; x3 = "3-3"; x4 = "3-4"; x5 = "3-5"; x6 = "3-6"; x7 = "3-7"; x8 = "3-8";
                    break;
            }
        }

        // Add maps to the set
        if (cleanReq.contains("uber")) {
            if (cleanReq.contains("interceptor") || cleanReq.contains("barracuda") ||
                cleanReq.contains("saboteur") || cleanReq.contains("annihilator")) {
                addMapId(ids, "5-2");
            } else {
                addMapId(ids, "4-5");
            }
            return ids;
        }

        // Bosses
        if (cleanReq.contains("boss")) {
            if (cleanReq.contains("kristallon") || cleanReq.contains("kristallin") || 
                cleanReq.contains("protegit") || cleanReq.contains("cubikon")) {
                addMapId(ids, x7);
                addMapId(ids, "4-5");
            } else if (cleanReq.contains("sibelonit") || cleanReq.contains("lordakium")) {
                addMapId(ids, x5);
                addMapId(ids, "4-5");
            } else if (cleanReq.contains("sibelon")) {
                addMapId(ids, x4);
                addMapId(ids, "4-5");
            } else if (cleanReq.contains("devolarium")) {
                addMapId(ids, x3);
                addMapId(ids, "4-5");
            } else if (cleanReq.contains("mordon") || cleanReq.contains("saimon")) {
                addMapId(ids, x3);
                addMapId(ids, "4-5");
            } else if (cleanReq.contains("streuner") || cleanReq.contains("lordakia")) {
                addMapId(ids, x2);
                addMapId(ids, "4-5");
            }
            return ids;
        }

        // Normal NPCs
        if (cleanReq.contains("kristallin") || cleanReq.contains("kristallon")) {
            addMapId(ids, x7);
        } else if (cleanReq.contains("cubikon") || cleanReq.contains("protegit")) {
            addMapId(ids, x6);
        } else if (cleanReq.contains("sibelonit") || cleanReq.contains("lordakium")) {
            addMapId(ids, x5);
        } else if (cleanReq.contains("sibelon")) {
            addMapId(ids, x4);
        } else if (cleanReq.contains("mordon") || cleanReq.contains("saimon") || cleanReq.contains("devolarium")) {
            addMapId(ids, x3);
        } else if (cleanReq.contains("lordakia") || cleanReq.contains("streuner")) {
            addMapId(ids, x2);
        }

        return ids;
    }

    private void addMapId(java.util.Set<Integer> ids, String mapName) {
        Optional<GameMap> m = ctx.starSystemAPI.findMap(mapName);
        if (m.isPresent() && !isMapBlacklisted(m.get())) {
            ids.add(m.get().getId());
        }
    }

    /**
     * Filtra a linha do texto de objetivos da missão que melhor corresponde
     * ao requisito que estamos tentando resolver, evitando extrair mapas de
     * objetivos futuros/outros.
     */
    private String getMatchingObjectiveLine(String objectiveText, String reqDesc) {
        if (objectiveText == null || reqDesc == null || objectiveText.isEmpty() || reqDesc.isEmpty()) {
            return null;
        }
        String normalizedReq = MissionMapLoader.normalize(reqDesc).toLowerCase();
        String[] reqWords = normalizedReq.split("\\s+");
        java.util.List<String> keywords = new java.util.ArrayList<>();
        for (String w : reqWords) {
            if (w.length() > 3 && !w.equals("destroi") && !w.equals("recolhe") && !w.equals("destrua") && !w.equals("matar")) {
                keywords.add(w);
            }
        }
        if (keywords.isEmpty()) {
            return null;
        }

        String[] lines = objectiveText.split("\\|");
        for (String line : lines) {
            String normalizedLine = MissionMapLoader.normalize(line).toLowerCase();
            for (String kw : keywords) {
                if (normalizedLine.contains(kw)) {
                    return line;
                }
            }
        }
        return null;
    }

    /**
     * Dado o conjunto de mapas em que um NPC existe (NpcInfo.getMapIds()),
     * escolhe o melhor mapa-alvo a partir do mapa atual do herói.
     *
     * CORREÇÃO DO BUG "Saimon 3-3 foi para 2-3": o antigo código usava
     * mapIds.iterator().next(), que retornava o primeiro elemento do HashSet
     * (ordem não determinística). Isso mandava o bot para um mapa errado
     * quando a Quest não informava o mapa no texto. Agora priorizamos:
     *   1) o mapa atual (se o NPC existir nele, não precisa se mover);
     *   2) o mapa alcançável cujo próximo portal (rota) está mais perto;
     *   3) qualquer mapa alcançável (fallback).
     * Mapas inalcançáveis a partir do mapa atual (findNext == null) são ignorados,
     * o que também filtra mapas de facção errada (ex: 2-3 / 3-3 para um jogador MMO).
     */
    private GameMap pickClosestNpcMap(Set<Integer> mapIds) {
        if (mapIds == null || mapIds.isEmpty()) return null;

        // ESTABILIDADE: Se o nosso mapa de destino atual já está na lista de candidatos válidos,
        // mantemos ele! Isso evita que o bot mude de ideia durante o vôo se a distância mudar.
        if (ctx.targetMap != null && mapIds.contains(ctx.targetMap.getId())) {
            return ctx.targetMap;
        }

        GameMap current = ctx.heroAPI.getMap();
        GameMap best = null;
        double bestCost = Double.MAX_VALUE;

        for (int id : mapIds) {
            Optional<GameMap> mapOpt = ctx.starSystemAPI.findMap(id);
            if (!mapOpt.isPresent()) continue;
            GameMap map = mapOpt.get();
            if (isMapBlacklisted(map)) continue;

            // 1) Mapa atual: custo zero, vence imediatamente
            if (current != null && current.getId() == map.getId()) {
                return map;
            }

            // 2) Custo = distância do centro do mapa até o próximo portal na rota até esse mapa.
            //    Usamos o centro do mapa (10400, 6500) como referência estática em vez da posição do Heroi
            //    para que o custo seja constante e não mude conforme o bot se move.
            Portal next = ctx.starSystemAPI.findNext(map);
            if (next == null) continue;
            double cost = next.distanceTo(10400.0, 6500.0);
            if (cost < bestCost) {
                bestCost = cost;
                best = map;
            } else if (cost == bestCost) {
                if (best == null || map.getId() < best.getId()) {
                    best = map;
                }
            }
        }
        return best;
    }

    public GameMap resolveMapFromText(String text) {
        if (text == null || text.isEmpty()) return null;
        String normalized = MissionMapLoader.normalize(text);
        return resolveMapFromNormalizedText(normalized, text, false);
    }

    /**
     * Resolve o mapa de destino para quests PVP a partir da config pvp.pvpMap.
     * Se a config estiver vazia, retorna o mapa atual (opera onde o bot já está).
     */
    public GameMap resolvePvpMap() {
        if (ctx.config == null || ctx.config.pvp == null) return null;
        String pvpMapName = ctx.config.pvp.pvpMap;
        if (pvpMapName == null || pvpMapName.trim().isEmpty()) {
            GameMap current = ctx.heroAPI.getMap();
            logger.logDebug("resolvePvpMap: pvpMap vazio, usando mapa atual: "
                    + (current != null ? current.getName() : "null"));
            return current;
        }
        pvpMapName = pvpMapName.trim();
        Optional<GameMap> map = ctx.starSystemAPI.findMap(pvpMapName);
        if (map.isPresent()) {
            logger.logDebug("resolvePvpMap: mapa PVP configurado = " + map.get().getName());
            return map.get();
        }
        // Fallback: tenta com prefixo da facção (ex: "4-4" -> "2-4" para EIC)
        if (ctx.heroAPI.getEntityInfo() != null && ctx.heroAPI.getEntityInfo().getFaction() != null) {
            String suffix = pvpMapName.contains("-") ? pvpMapName.substring(pvpMapName.indexOf('-')) : pvpMapName;
            String prefix = "1-";
            switch (ctx.heroAPI.getEntityInfo().getFaction()) {
                case EIC: prefix = "2-"; break;
                case VRU: prefix = "3-"; break;
            }
            Optional<GameMap> factionMap = ctx.starSystemAPI.findMap(prefix + suffix);
            if (factionMap.isPresent()) {
                logger.logDebug("resolvePvpMap: usando mapa PVP com prefixo de facção = " + factionMap.get().getName());
                return factionMap.get();
            }
        }
        logger.logDebug("resolvePvpMap: mapa PVP '" + pvpMapName + "' nao encontrado. Usando mapa atual.");
        return ctx.heroAPI.getMap();
    }

    /**
     * Padrões para extrair o mapa-alvo citado na descrição da quest.
     *
     * CORREÇÃO DO BUG "não troca de mapa após matar NPCs":
     * O padrão antigo tinha DOIS defeitos que impediam a troca de mapa em clientes
     * PT-BR e em qualquer mapa citado como "X-4":
     *   1) Só reconhecia a palavra "map" (inglês). Em português a descrição usa
     *      "mapa" ("no seu mapa X-4", "viaje até o mapa 4-5"), então nada casava.
     *   2) Exigia DOIS números "(\d)-(\d)", mas "X-4" tem apenas UM traço
     *      (prefixo X + número). "X-4 map" / "mapa X-4" nunca casavam -> o bot
     *      ignorava o mapa citado e caía nas regras hardcoded erradas.
     *
     * Agora suportamos PT e EN, e o código pode vir ANTES ou DEPOIS da palavra
     * "map"/"mapa":
     *   - EN geralmente: "X-4 map", "4-5 map", "your X-4 map"
     *   - PT geralmente: "mapa X-4", "mapa 4-5", "no seu mapa X-4", "viaje ate o mapa 4-5"
     * O prefixo "X" (genérico da própria companhia) é um único caractere seguido de
     * traço e do número do mapa (ex: "X-4"), não dois números.
     */
    // Código ANTES da palavra map/mapa: "X-4 map", "4-5 map", "your X-4 map"
    private static final Pattern MAP_AFTER_PATTERN = Pattern.compile(
            "(?i)(?:(?:your|enemy|home|own\\s+company|own|sua|seu|pr[óo]pria|propria)\\s+)?" +
            "([xX\\d])\\s*-\\s*(\\d{1,2}|[bB][lL])\\s*(?:mapa|map)\\b");
    // Código DEPOIS da palavra map/mapa: "mapa X-4", "mapa 4-5", "no seu mapa X-4",
    // "mapa inimigo X-5" (a palavra inimigo/enemy pode ficar entre mapa e o código).
    private static final Pattern MAP_BEFORE_PATTERN = Pattern.compile(
            "(?i)(?:(?:your|enemy|home|own\\s+company|own|sua|seu|pr[óo]pria|propria)\\s+)?" +
            "(?:mapa|map)\\s+(?:inimigo|enemy\\s+)?([xX\\d])\\s*-\\s*(\\d{1,2}|[bB][lL])");
    // Forma normalizada (sem hífens, usada em textos já passados por MissionMapLoader.normalize):
    // "mapa x 4", "x 4 map"
    private static final Pattern MAP_SPACE_PATTERN = Pattern.compile(
            "(?i)(?:(?:your|enemy|home|own\\s+company|own|sua|seu|pr[óo]pria|propria)\\s+)?" +
            "(?:mapa|map)\\s+(?:inimigo|enemy\\s+)?([xX\\d])\\s+(\\d{1,2}|[bB][lL])");
    // Código PURO (sem palavra "map"/"mapa"): usado em listas "maps: 1-3, 2-3, 3-3"
    // e em textos normalizados onde o "map" foi removido. Só casa quando cercado
    // por separadores (início, espaço, vírgula, barra, parêntese) para não confundir
    // com quantidades como "0/30" ou coordenadas "107/67".
    private static final Pattern MAP_BARE_PATTERN = Pattern.compile(
            "(?<!\\d)([xX\\d])\\s*-\\s*(\\d{1,2}|[bB][lL])(?![a-zA-Z\\d])");
    private static final Pattern MAP_LIST_PATTERN = Pattern.compile(
            "(?i)(?:mapas?\\s*:?\\s*)((?:[xX\\d]\\s*-\\s*(?:\\d{1,2}|[bB][lL])\\s*(?:,\\s*)?)+)");

    /**
     * Tenta casar um código de mapa (X-4, 4-5, 1-3, ...) em qualquer posição do
     * texto, testando os padrões (código antes/depois da palavra map/mapa, a forma
     * normalizada sem hífen, e o código puro). Retorna o Matcher do primeiro
     * padrão que casar, ou null. Os grupos 1 e 2 são sempre (prefixo, número).
     */
    private Matcher findMapCodeMatcher(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher after = MAP_AFTER_PATTERN.matcher(text);
        if (after.find()) return after;
        Matcher before = MAP_BEFORE_PATTERN.matcher(text);
        if (before.find()) return before;
        Matcher space = MAP_SPACE_PATTERN.matcher(text);
        if (space.find()) return space;
        Matcher bare = MAP_BARE_PATTERN.matcher(text);
        if (bare.find()) return bare;
        return null;
    }

    private GameMap extractMapFromDescription(String desc) {
        if (desc == null || desc.isEmpty()) return null;

        // 1) Lista explícita "maps: 1-3, 2-3, 3-3" / "mapas: 1-3, 2-3, 3-3" -> pega o primeiro
        Matcher listM = MAP_LIST_PATTERN.matcher(desc);
        if (listM.find()) {
            String[] parts = listM.group(1).split(",");
            for (String p : parts) {
                Matcher m = findMapCodeMatcher(p);
                if (m != null) {
                    GameMap g = tryResolveMapCode(m.group(1), m.group(2), desc);
                    if (g != null) return g;
                }
            }
        }

        // 2) Mapa único citado na descrição (PT ou EN, código antes ou depois de "map"/"mapa")
        Matcher m = findMapCodeMatcher(desc);
        if (m != null) {
            GameMap g = tryResolveMapCode(m.group(1), m.group(2), desc);
            if (g != null) return g;
        }

        return null;
    }

    /**
     * Tenta resolver um código de mapa "X-Y" (ex: 4-5, 1-3, X-4). Se o prefixo for
     * "X" (genérico), substitui pelo prefixo da facção do jogador (1-/2-/3-). Se a
     * descrição indicar mapa INIMIGO ("inimigo"/"enemy"), usa a facção oposta.
     */
    private GameMap tryResolveMapCode(String a, String b, String desc) {
        String code = a.trim() + "-" + b.trim();
        String descLower = desc.toLowerCase();
        boolean isEnemy = descLower.contains("inimigo") || descLower.contains("enemy");
        // "X" (genérico) => mapa da própria companhia (ou da facção inimiga, se indicado)
        if (code.toLowerCase().startsWith("x-")) {
            String prefix = "1-";
            if (ctx.heroAPI.getEntityInfo() != null && ctx.heroAPI.getEntityInfo().getFaction() != null) {
                Faction faction = ctx.heroAPI.getEntityInfo().getFaction();
                if (isEnemy) {
                    // Mapa inimigo: usa a facção OPOSTA à do jogador
                    switch (faction) {
                        case MMO: prefix = "2-"; break;   // inimigo de MMO = EIC
                        case EIC: prefix = "3-"; break;   // inimigo de EIC = VRU
                        case VRU: prefix = "1-"; break;   // inimigo de VRU = MMO
                        default: prefix = "2-";
                    }
                } else {
                    switch (faction) {
                        case EIC: prefix = "2-"; break;
                        case VRU: prefix = "3-"; break;
                        default: prefix = "1-";
                    }
                }
            }
            code = prefix + b.trim();
        }
        Optional<GameMap> map = ctx.starSystemAPI.findMap(code);
        if (map.isPresent()) return map.get();
        // Fallback: tenta como id numérico (alguns builds usam id em vez de nome)
        try {
            int id = Integer.parseInt(code.replace("-", ""));
            Optional<GameMap> byId = ctx.starSystemAPI.findMap(id);
            if (byId.isPresent()) return byId.get();
        } catch (NumberFormatException ignored) {}
        return null;
    }

    private GameMap resolveMapFromNormalizedText(String normalized, String rawReq, boolean enforceBlacklist) {
        if (normalized == null || normalized.isEmpty()) return null;

        // 1. Direct Ore-to-Map resolution
        if (normalized.contains("promerium") || normalized.contains("seprom") || normalized.contains("osmium")) {
            GameMap oreMap = getCompanyMap("1-6", "2-6", "3-6");
            if (oreMap != null && (!enforceBlacklist || !isMapBlacklisted(oreMap))) return oreMap;
        }
        if (normalized.contains("duranium") || normalized.contains("prometid") || normalized.contains("xenomit")) {
            GameMap oreMap = getCompanyMap("1-3", "2-3", "3-3");
            if (oreMap != null && (!enforceBlacklist || !isMapBlacklisted(oreMap))) return oreMap;
        }
        if (normalized.contains("prometium") || normalized.contains("endurium") || normalized.contains("terbium")) {
            GameMap oreMap = getCompanyMap("1-2", "2-2", "3-2");
            if (oreMap != null && (!enforceBlacklist || !isMapBlacklisted(oreMap))) return oreMap;
        }

        // 2. NPC hardcoded rules
        GameMap hardcoded = getHardcodedNpcMap(normalized, rawReq);
        if (hardcoded != null && (!enforceBlacklist || !isMapBlacklisted(hardcoded))) {
            return hardcoded;
        }

        return null;
    }

    private GameMap getHardcodedNpcMap(String cleanReq, String rawReq) {
        // Regra especial para StreuneR / Boss StreuneR (deve ir para o mapa X-8)
        if (cleanReq.contains("streuner")) {
            boolean isStreunerR = rawReq != null && (rawReq.contains("StreuneR") || rawReq.contains("StreunerR") || rawReq.toLowerCase().contains("streuner-r") || rawReq.toLowerCase().contains("streuner r"));
            if (isStreunerR) {
                if (cleanReq.contains("boss")) {
                    return getCompanyMap("1-8", "2-8", "3-8");
                }
                return getCompanyMap("1-8", "2-8", "3-8");
            }
        }

        if (cleanReq.contains("uber")) {
            if (cleanReq.contains("interceptor") || cleanReq.contains("barracuda") ||
                cleanReq.contains("saboteur") || cleanReq.contains("annihilator")) {
                return ctx.starSystemAPI.findMap("5-2").orElse(null);
            }
            return ctx.starSystemAPI.findMap("4-5").orElse(null);
        }
        // Bosses
        if (cleanReq.contains("boss kristallon")) return getCompanyMap("1-7", "2-7", "3-7");
        if (cleanReq.contains("boss kristallin")) return getCompanyMap("1-7", "2-7", "3-7");
        if (cleanReq.contains("boss protegit"))   return getCompanyMap("1-7", "2-7", "3-7");
        if (cleanReq.contains("boss cubikon"))    return getCompanyMap("1-7", "2-7", "3-7");
        if (cleanReq.contains("boss de vol"))     return getCompanyMap("1-7", "2-7", "3-7");
        // CORREÇÃO DO BUG "boss Lordakia foi para o mapa 1-7": a regra antiga
        // "boss lord" casava com "boss lordakia" (e "boss lordakium") porque
        // "lordakia"/"lordakium" contêm "lord", mandando o bot para o mapa 1-7
        // (mapa de bosses) onde esses NPCs NÃO existem. Lordakia é NPC do mapa
        // 1-2 e Lordakium do 1-5 — ambos já têm regra específica abaixo, então
        // removemos essa regra genérica e deixamos as específicas resolverem.

        // Map X-1 (Low maps)
        GameMap homeMap = resolveHomeMap();
        // Boss / Uber specific maps (X-2 / X-4 etc.)
        if (cleanReq.contains("boss streuner")) return getCompanyMap("1-2", "2-2", "3-2");
        if (cleanReq.contains("uber streuner") || cleanReq.contains("uberstreuner")) return getCompanyMap("1-4", "2-4", "3-4");

        if (cleanReq.contains("streuner") && homeMap != null)    return ctx.starSystemAPI.findMap(homeMap.getName()).orElse(null);

        // Map X-2 / 1-2
        if (cleanReq.contains("lordakia"))    return getCompanyMap("1-2", "2-2", "3-2");

        // Map X-3 / 1-3
        if (cleanReq.contains("saimon"))      return getCompanyMap("1-3", "2-3", "3-3");
        if (cleanReq.contains("mordon"))      return getCompanyMap("1-3", "2-3", "3-3");
        if (cleanReq.contains("devolarium"))  return getCompanyMap("1-3", "2-3", "3-3");

        // Map X-5 / 1-5 (avaliado ANTES de sibelon para evitar que "sibelonit"
        // case com a substring "sibelon" e vá para o 1-4 por engano)
        if (cleanReq.contains("sibelonit"))   return getCompanyMap("1-5", "2-5", "3-5");
        if (cleanReq.contains("lordakium"))   return getCompanyMap("1-5", "2-5", "3-5");

        // Map X-4 / 1-4
        if (cleanReq.contains("sibelon"))     return getCompanyMap("1-4", "2-4", "3-4");

        // Map X-6 / 1-6
        if (cleanReq.contains("cubikon"))     return getCompanyMap("1-6", "2-6", "3-6");
        if (cleanReq.contains("protegit"))    return getCompanyMap("1-6", "2-6", "3-6");

        // Map X-7 / 1-7
        if (cleanReq.contains("kristallin"))  return getCompanyMap("1-7", "2-7", "3-7");
        if (cleanReq.contains("kristallon"))  return getCompanyMap("1-7", "2-7", "3-7");

        // Map 4-2
        if (cleanReq.contains("ogatra"))      return ctx.starSystemAPI.findMap("4-2").orElse(null);
        if (cleanReq.contains("of course"))   return ctx.starSystemAPI.findMap("4-2").orElse(null);
        if (cleanReq.contains("noch"))        return ctx.starSystemAPI.findMap("4-2").orElse(null);
        if (cleanReq.contains("focker"))      return ctx.starSystemAPI.findMap("4-2").orElse(null);

        // Map 4-3
        if (cleanReq.contains("obsidian"))    return ctx.starSystemAPI.findMap("4-3").orElse(null);
        if (cleanReq.contains("red star"))    return ctx.starSystemAPI.findMap("4-3").orElse(null);
        if (cleanReq.contains("flint"))       return ctx.starSystemAPI.findMap("4-3").orElse(null);

        // Map 4-4
        if (cleanReq.contains("rageless"))    return ctx.starSystemAPI.findMap("4-4").orElse(null);
        if (cleanReq.contains("vengeance"))   return ctx.starSystemAPI.findMap("4-4").orElse(null);
        if (cleanReq.contains("fury"))        return ctx.starSystemAPI.findMap("4-4").orElse(null);

        // Map 5-2 (Pirate map 2)
        if (cleanReq.contains("interceptor")) return ctx.starSystemAPI.findMap("5-2").orElse(null);
        if (cleanReq.contains("barracuda"))   return ctx.starSystemAPI.findMap("5-2").orElse(null);
        if (cleanReq.contains("saboteur"))    return ctx.starSystemAPI.findMap("5-2").orElse(null);
        if (cleanReq.contains("annihilator")) return ctx.starSystemAPI.findMap("5-2").orElse(null);

        // Map 5-3 (Pirate map 3)
        if (cleanReq.contains("battleray"))   return ctx.starSystemAPI.findMap("5-3").orElse(null);
        if (cleanReq.contains("palladium"))   return ctx.starSystemAPI.findMap("5-3").orElse(null);

        // Map X-BL (Blacklight maps)
        if (cleanReq.contains("impulse"))  return getCompanyMap("1-BL", "2-BL", "3-BL");
        if (cleanReq.contains("attend"))   return getCompanyMap("1-BL", "2-BL", "3-BL");
        if (cleanReq.contains("observe"))  return getCompanyMap("1-BL", "2-BL", "3-BL");
        if (cleanReq.contains("invoke"))   return getCompanyMap("1-BL", "2-BL", "3-BL");
        if (cleanReq.contains("mindfire")) return getCompanyMap("1-BL", "2-BL", "3-BL");

        return null;
    }

    private GameMap getCompanyMap(String mmo, String eic, String vru) {
        String target = mmo;
        if (ctx.heroAPI.getEntityInfo() != null && ctx.heroAPI.getEntityInfo().getFaction() != null) {
            switch (ctx.heroAPI.getEntityInfo().getFaction()) {
                case EIC: target = eic; break;
                case VRU: target = vru; break;
            }
        }
        return ctx.starSystemAPI.findMap(target).orElse(null);
    }

    public GameMap getUberMapForQuest(Quest quest) {
        if (quest == null) return null;

        // Check title first
        String titleNorm = MissionMapLoader.normalize(quest.getTitle());
        if (titleNorm.contains("uber")) {
            if (titleNorm.contains("interceptor") || titleNorm.contains("barracuda") ||
                titleNorm.contains("saboteur") || titleNorm.contains("annihilator")) {
                return ctx.starSystemAPI.findMap("5-2").orElse(null);
            }
            return ctx.starSystemAPI.findMap("4-5").orElse(null);
        }

        // Check requirements
        java.util.List<? extends Requirement> reqs = quest.getRequirements();
        if (reqs != null) {
            for (Requirement r : reqs) {
                if (r.getDescription() != null) {
                    String descNorm = MissionMapLoader.normalize(r.getDescription());
                    if (descNorm.contains("uber")) {
                        if (descNorm.contains("interceptor") || descNorm.contains("barracuda") ||
                            descNorm.contains("saboteur") || descNorm.contains("annihilator")) {
                            return ctx.starSystemAPI.findMap("5-2").orElse(null);
                        }
                        return ctx.starSystemAPI.findMap("4-5").orElse(null);
                    }
                }
            }
        }
        return null;
    }

    public boolean isMapBlacklisted(GameMap map) {
        if (map == null || ctx.config == null) return false;
        String blacklist = QuestConfig.NavigationConfig.MAP_BLACKLIST;
        if (blacklist == null || blacklist.isEmpty()) return false;
        String mapName = map.getName();
        if (mapName == null) return false;
        for (String entry : blacklist.split(",")) {
            if (entry.trim().equalsIgnoreCase(mapName.trim())) {
                return true;
            }
        }
        return false;
    }

    public GameMap resolveHomeMap() {
        return resolveHomeMap(false);
    }

    public GameMap resolveHomeMap(boolean preferHighBase) {
        // CORREÇÃO DO BUG "foi ao Quest Giver de outra companhia (ex: 3-5/3-1)":
        // antes, se a facção do jogador ainda não tivesse carregado (getFaction()==null)
        // no primeiro tick, o prefixo era "adivinhado" pelo nome do mapa atual e
        // GUARDADO PARA SEMPRE em cachedFactionPrefix. Se o bot estivesse em 3-5 nesse
        // instante, ele travava em "3-" e ia ao Quest Giver da VRU, que não funciona
        // para a companhia do jogador. Agora só cacheamos o prefixo quando a facção
        // REAL é conhecida; o fallback pelo nome do mapa é temporário (não cacheado),
        // então assim que a facção carrega o prefixo se corrige sozinho.
        //
        // CORREÇÃO ADICIONAL: se a facção ainda é desconhecida, retornamos null
        // em vez de adivinhar pelo mapa atual. Isso evita que o bot navegue para
        // o QuestGiver de outra companhia (ex: 3-1 quando é MMO). Quem chama
        // resolveHomeMap() já trata null adequadamente (aguarda e tenta de novo).
        String prefix = "1-"; // Default MMO
        if (ctx.heroAPI.getEntityInfo() != null && ctx.heroAPI.getEntityInfo().getFaction() != null) {
            Faction faction = ctx.heroAPI.getEntityInfo().getFaction();
            if (faction == Faction.EIC) prefix = "2-";
            else if (faction == Faction.VRU) prefix = "3-";
            else if (faction == Faction.MMO) prefix = "1-";
            // Só cacheia quando a facção é conhecida com certeza.
            ctx.cachedFactionPrefix = prefix;
        } else {
            // Facção ainda desconhecida: retorna null para evitar navegar para
            // o mapa errado. O chamador vai aguardar e tentar de novo no próximo tick.
            logger.logDebug("[MapResolver] Facao ainda desconhecida; retornando null para evitar ir ao mapa errado.");
            return null;
        }

        if (preferHighBase) {
            // Para venda: always use X-8 (has Refinery)
            return ctx.starSystemAPI.findMap(prefix + "8").orElse(null);
        }

        // Escolhe entre X-1 e X-8 baseado no mapa que estiver mais perto do atual
        GameMap current = ctx.heroAPI.getMap();
        if (current != null) {
            String name = current.getName();
            if (name != null) {
                // Se o mapa atual termina com 5, 6, 7 ou 8 (ex: 1-7, 2-6, 3-5), X-8 está mais perto.
                if (name.endsWith("5") || name.endsWith("6") || name.endsWith("7") || name.endsWith("8")) {
                    return ctx.starSystemAPI.findMap(prefix + "8").orElse(null);
                }
            }
        }

        // Caso contrário, usa X-1 por padrão
        return ctx.starSystemAPI.findMap(prefix + "1").orElse(null);
    }

    public void navigateToMap(GameMap dest, long now) {
        GameMap oldTarget = ctx.targetMap;
        ctx.targetMap = dest;
        ctx.traceTargetMapChange(oldTarget, dest, 
            ctx.questAPI.getDisplayedQuest() != null ? ctx.questAPI.getDisplayedQuest().getTitle() : "null",
            ctx.currentReq != null ? ctx.currentReq.getDescription() : "null",
            "navigateToMap", "MapResolver", 694);
        GameMap logCurrentMap = ctx.heroAPI.getMap();
        
        // DIAGNÓSTICO: Log completo do estado da navegação
        System.out.println("[NAV_DIAG] navigateToMap chamado:"
                + " | dest=" + (dest != null ? dest.getName() : "null")
                + " | currentMap=" + (logCurrentMap != null ? logCurrentMap.getName() : "null")
                + " | currentMapId=" + (logCurrentMap != null ? logCurrentMap.getId() : "null")
                + " | destId=" + (dest != null ? dest.getId() : "null")
                + " | needsNav=" + (logCurrentMap != null && dest != null && logCurrentMap.getId() != dest.getId())
                + " | lastPortalJump=" + ctx.lastPortalJumpTime
                + " | now=" + now
                + " | cooldownRemaining=" + (ctx.lastPortalJumpTime > 0 ? Math.max(0, QuestConfig.NavigationConfig.PORTAL_JUMP_COOLDOWN_MS - (now - ctx.lastPortalJumpTime)) : 0)
                + " | heroExists=" + (ctx.heroAPI != null && ctx.heroAPI.getEntityInfo() != null)
                + " | heroMoving=" + (ctx.heroAPI != null && ctx.heroAPI.isMoving())
                + " | targetMap=" + (ctx.targetMap != null ? ctx.targetMap.getName() : "null"));
        
        // CORREÇÃO DO TRAVAMENTO "Aguardando mapa carregar...":
        // Se o herói JÁ está no mapa de destino, não há portal a pular e o cooldown
        // de portal é irrelevante. Limpamos targetMap e retornamos ANTES da checagem
        // de cooldown, para que o bot não fique preso nessa mensagem quando já chegou.
        // Sem isso, enquanto a API relata o mapa antigo durante o loading (ou oscila),
        // tickLogic chama navigateToMap todo tick e o cooldown de 10s bloqueia a
        // verificação de "já cheguei", reiniciando o ciclo indefinidamente.
        if (logCurrentMap != null && dest != null && logCurrentMap.getId() == dest.getId()) {
            GameMap oldT = ctx.targetMap;
            ctx.targetMap = null;
            ctx.traceTargetMapChange(oldT, null,
                ctx.questAPI.getDisplayedQuest() != null ? ctx.questAPI.getDisplayedQuest().getTitle() : "null",
                "Chegou no destino (pre-check)", "navigateToMap", "MapResolver", 695);
            ctx.currentAction = "[Nav] Chegou em: " + dest.getName();
            return;
        }
        
        if (now - ctx.lastPortalJumpTime < QuestConfig.NavigationConfig.PORTAL_JUMP_COOLDOWN_MS) {
            ctx.setShipMode("roam");
            ctx.currentAction = "[Nav] Aguardando mapa carregar... -> " + dest.getName();
            
            // LOG DETALHADO PARA DIAGNÓSTICO
            System.out.println("[WAIT_MAP]"
                    + "\norigem: MapResolver.java:708 (navigateToMap)"
                    + "\nmotivo: portal cooldown"
                    + "\ncurrentMap=" + (logCurrentMap != null ? logCurrentMap.getName() : "null")
                    + "\ntargetMap=" + (dest != null ? dest.getName() : "null")
                    + "\ncurrentMap == targetMap: " + (logCurrentMap != null && dest != null && logCurrentMap.getId() == dest.getId())
                    + "\nheroExists=" + (ctx.heroAPI != null && ctx.heroAPI.getEntityInfo() != null)
                    + "\nheroMoving=" + (ctx.heroAPI != null && ctx.heroAPI.isMoving())
                    + "\nlastPortalJumpTime=" + ctx.lastPortalJumpTime
                    + "\nnow=" + now
                    + "\ncooldown=" + (QuestConfig.NavigationConfig.PORTAL_JUMP_COOLDOWN_MS - (now - ctx.lastPortalJumpTime))
                    + "\nreturn executado=true");
            
            return;
        }

        Portal nextPortal = ctx.starSystemAPI.findNext(dest);

        // Generic fallback: search all visible portals for one leading directly to the
        // destination map. Covers company maps (1-x/2-x/3-x) and any case where
        // starSystemAPI.findNext() failed to compute a route.
        if (nextPortal == null && ctx.heroAPI.getMap() != null) {
            Collection<? extends Portal> portals = ctx.entitiesAPI.getPortals();
            if (portals != null) {
                for (Portal p : portals) {
                    if (p.getTargetMap().isPresent() && p.getTargetMap().get().getId() == dest.getId()) {
                        nextPortal = p;
                        logger.logDebug("Rota generica: portal para " + dest.getName() + " encontrado manualmente.");
                        break;
                    }
                }
            }
        }

        if (nextPortal == null) {
            ctx.currentAction = "[Nav] Sem rota para: " + dest.getName();
            ctx.movementAPI.moveRandom();
            return;
        }

        double distToPortal = nextPortal.distanceTo(ctx.heroAPI);
        ctx.currentAction = "[Nav] Indo para portal -> " + dest.getName()
                + " (dist: " + (int) distToPortal + ")";

        if (distToPortal < QuestConfig.NavigationConfig.PORTAL_JUMP_DISTANCE) {
            GameMap currentMap = ctx.heroAPI.getMap();
            if (currentMap != null && currentMap.getId() == dest.getId()) {
                ctx.targetMap = null;
                ctx.traceTargetMapChange(oldTarget, null, 
                    ctx.questAPI.getDisplayedQuest() != null ? ctx.questAPI.getDisplayedQuest().getTitle() : "null",
                    "Chegou no destino", "navigateToMap", "MapResolver", 734);
                ctx.currentAction = "[Nav] Chegou em: " + dest.getName();
                return;
            }
            // LOG PORTAL_TRACE: rastreia todas as vezes que o cooldown é iniciado
            long oldPortalTime = ctx.lastPortalJumpTime;
            boolean jumpCalled = true;
            boolean jumpReturned = true; // Se chegou aqui, jumpPortal foi chamado (assumimos sucesso)
            GameMap mapBeforeJump = ctx.heroAPI.getMap();
            
            ctx.movementAPI.jumpPortal(nextPortal);
            ctx.lastPortalJumpTime = now;
            
            System.out.println("[PORTAL_TRACE]"
                    + "\noldValue=" + (oldPortalTime > 0 ? new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(oldPortalTime) : "null")
                    + "\nnewValue=" + new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(now)
                    + "\ncaller=navigateToMap (MapResolver.java:751)"
                    + "\ncurrentMap=" + (mapBeforeJump != null ? mapBeforeJump.getName() : "null")
                    + "\ntargetMap=" + (dest != null ? dest.getName() : "null")
                    + "\nportal=" + (nextPortal != null ? nextPortal.getTargetMap().map(m -> m.getName()).orElse("null") : "null")
                    + "\njumpPortal chamado=true"
                    + "\njumpPortal retornou=true");
            
            ctx.currentAction = "[Nav] Pulando para: " + dest.getName() + "...";
        } else {
            ctx.setShipMode("roam");
            ctx.movementAPI.moveTo(nextPortal);
        }
    }

    /**
     * Sincroniza o mapa de trabalho do bot (general.working_map) com o mapa alvo da quest.
     * O LootCollectorModule (via LootModule.checkMap) lê general.working_map como um
     * Integer (id do mapa) para decidir onde operar; se for diferente do mapa atual, ele
     * navega de volta. Sem atualizar esse valor, o bot voltaria para casa (oscilação).
     * IMPORTANTE: na API do DarkBot, general.working_map é Integer (id), NÃO GameMap.
     */
    private ConfigSetting<Integer> getWorkingMapSetting() {
        // Tenta os paths possíveis (case-sensitive na ConfigAPI). O DarkBot registra o
        // campo como "general.working_map"; alguns builds antigos usam "GENERAL.WORKING_MAP".
        String[] paths = {"general.working_map", "GENERAL.WORKING_MAP", "General.working_map"};
        for (String path : paths) {
            try {
                return ctx.configAPI.requireConfig(path);
            } catch (Exception ignored) {}
        }
        return null;
    }

    public void updateBotWorkingMap(GameMap target) {
        if (target == null) return;
        int targetId = target.getId();
        if (ctx.lastSetWorkingMapId != null && ctx.lastSetWorkingMapId == targetId) return; // já definido, evita spam de escrita

        ConfigSetting<Integer> setting = getWorkingMapSetting();
        if (setting == null) {
            System.err.println("[QuestModule] Nao foi possivel obter o ConfigSetting de general.working_map "
                    + "(caminho nao encontrado). O mapa de trabalho nao sera trocado.");
            return;
        }

        // [FLOW] 6) Detecção de sobrescrita de working_map
        Integer antes = setting.getValue();
        // NOTA: não usar Thread.currentThread() — o PluginClassLoader do DarkBot
        // bloqueia java.lang.Thread, causando NoClassDefFoundError no load da classe.
        String caller = "updateBotWorkingMap() em com.eventchanger.quest.MapResolver";
        System.out.println("[FLOW] working_map SOBRESCRITA:"
                + "\n  caller=" + caller
                + "\n  de=" + (antes != null ? antes : "null")
                + "\n  para=" + targetId + " (" + target.getName() + ")"
                + "\n  ctx.targetMap=" + (ctx.targetMap != null ? ctx.targetMap.getName() : "null"));

        try {
            // Salva o valor original (id) na primeira vez (para restaurar depois)
            if (ctx.originalWorkingMapId == null) {
                Integer current = setting.getValue();
                if (current != null) {
                    ctx.originalWorkingMapId = current;
                }
            }
            setting.setValue(targetId);
            ctx.lastSetWorkingMapId = targetId;
            logger.logDebug("WORKING_MAP do bot atualizado para " + target.getName() + " (id=" + targetId + ")");
        } catch (Exception e) {
            System.err.println("[QuestModule] Falha ao setar general.working_map para "
                    + target.getName() + " (id=" + targetId + "): " + e);
        }
    }

    /**
     * [FLOW] Lê o valor atual de general.working_map de forma segura (apenas leitura,
     * sem alterar estado). Usado para logs de diagnóstico de fluxo.
     */
    public Integer getCurrentWorkingMapId() {
        ConfigSetting<Integer> setting = getWorkingMapSetting();
        if (setting == null) return null;
        try {
            return setting.getValue();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Restaura o mapa de trabalho original do bot quando não há quest ativa (targetMap nulo).
     */
    public void restoreBotWorkingMap() {
        if (ctx.originalWorkingMapId == null || ctx.lastSetWorkingMapId == null) return;
        if (ctx.lastSetWorkingMapId.equals(ctx.originalWorkingMapId)) return;

        ConfigSetting<Integer> setting = getWorkingMapSetting();
        if (setting == null) return;

        try {
            setting.setValue(ctx.originalWorkingMapId);
            GameMap orig = ctx.starSystemAPI.findMap(ctx.originalWorkingMapId).orElse(null);
            String name = orig != null ? orig.getName() : String.valueOf(ctx.originalWorkingMapId);
            logger.logDebug("WORKING_MAP do bot restaurado para " + name + " (id=" + ctx.originalWorkingMapId + ")");
        } catch (Exception e) {
            System.err.println("[QuestModule] Falha ao restaurar general.working_map: " + e);
        }
        ctx.lastSetWorkingMapId = ctx.originalWorkingMapId;
    }

    private boolean isLootType(Requirement.RequirementType t) {
        return t == Requirement.RequirementType.COLLECT_LOOT
                || t == Requirement.RequirementType.COLLECT_BONUS_BOX
                || t == Requirement.RequirementType.COLLECT_BONUS_BOX_TYPE
                || t == Requirement.RequirementType.COLLECT
                || t == Requirement.RequirementType.CARGO;
    }
}