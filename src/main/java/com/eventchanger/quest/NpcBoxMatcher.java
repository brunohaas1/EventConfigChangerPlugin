package com.eventchanger.quest;

import eu.darkbot.api.config.types.BoxInfo;
import eu.darkbot.api.config.types.NpcInfo;
import eu.darkbot.api.game.entities.Box;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Responsável por todo o casamento (matching) de nomes de NPC/box contra a
 * descrição da quest: normalização, aliases customizados, regras de box e
 * leitura do nome configurado do NPC.
 */
public class NpcBoxMatcher {

    private final QuestContext ctx;
    private final QuestLogger logger;

    public NpcBoxMatcher(QuestContext ctx, QuestLogger logger) {
        this.ctx = ctx;
        this.logger = logger;
    }

    public String getNpcName(Map.Entry<String, NpcInfo> entry) {
        if (entry == null) return null;
        NpcInfo info = entry.getValue();
        String name = info != null ? info.getName() : null;
        if (name == null || name.isEmpty()) {
            name = entry.getKey();
        }
        return name;
    }

    public void refreshCustomAliasesIfNeeded() {
        if (ctx.config == null || ctx.config.npc == null) return;
        String raw = ctx.config.npc.customAliases != null ? ctx.config.npc.customAliases.trim() : "";
        if (raw.equals(ctx.lastCustomAliasesRaw)) return;

        ctx.customNpcAliases.clear();
        ctx.lastCustomAliasesRaw = raw;
        if (raw.isEmpty()) return;

        // Formato esperado: "annihilator=aniquilador,anihilator;interceptor=interceptador"
        String[] pairs = raw.split(";");
        for (String pair : pairs) {
            if (pair == null || pair.isBlank() || !pair.contains("=")) continue;
            String[] kv = pair.split("=", 2);
            String npcKey = MissionMapLoader.normalize(kv[0]);
            if (npcKey.isEmpty()) continue;

            Set<String> aliases = ctx.customNpcAliases.computeIfAbsent(npcKey, k -> new HashSet<>());
            aliases.add(npcKey);

            String rhs = kv.length > 1 ? kv[1] : "";
            for (String aliasRaw : rhs.split(",")) {
                String alias = MissionMapLoader.normalize(aliasRaw);
                if (!alias.isEmpty()) aliases.add(alias);
            }
        }

        logger.logDebug("customAliases carregados: " + ctx.customNpcAliases.size());
    }

    public boolean matchesCustomAlias(String normalizedNpc, String normalizedQuestDesc) {
        if (normalizedNpc == null || normalizedQuestDesc == null || ctx.customNpcAliases.isEmpty()) return false;

        for (Map.Entry<String, Set<String>> e : ctx.customNpcAliases.entrySet()) {
            String npcKey = e.getKey();
            Set<String> aliases = e.getValue();
            if (aliases == null || aliases.isEmpty()) continue;

            boolean npcSideMatches = normalizedNpc.contains(npcKey)
                    || aliases.stream().anyMatch(a -> !a.isEmpty() && normalizedNpc.contains(a));
            if (!npcSideMatches) continue;

            boolean descSideMatches = aliases.stream().anyMatch(a -> !a.isEmpty() && normalizedQuestDesc.contains(a));
            if (descSideMatches) return true;
        }

        return false;
    }

    public boolean npcMatchesQuestDesc(String npcName, String questDesc) {
        return npcMatchesQuestDesc(npcName, questDesc, false);
    }

    public boolean npcMatchesQuestDesc(String npcName, String questDesc, boolean strict) {
        if (npcName == null || questDesc == null) return false;

        String normNpc = MissionMapLoader.normalize(npcName);
        String normDesc = MissionMapLoader.normalize(questDesc);

        if (normNpc.isEmpty() || normDesc.isEmpty()) return false;
        if (matchesCustomAlias(normNpc, normDesc)) return true;

        // Remove common prefix/suffix symbols from NPC name (e.g. -=[, ]=-, ..::, ::..)
        normNpc = normNpc.replaceAll("^[ -=\\.:\\*]+|[ -=\\.:\\*]+$", "").trim();

        // Split NPC name into keywords
        String[] npcWords = normNpc.split(" ");

        java.util.List<String> npcKeywords = new java.util.ArrayList<>();
        for (String w : npcWords) {
            if (w.length() >= 3 && !w.equals("boss") && !w.equals("uber")) {
                npcKeywords.add(w);
            }
        }
        if (npcKeywords.isEmpty()) {
            for (String w : npcWords) {
                if (w.length() >= 2) npcKeywords.add(w);
            }
        }

        if (npcKeywords.isEmpty()) return false;

        if (strict) {
            // Strict mode: require the longest keyword (min 4 chars) to be present
            String primaryKw = npcKeywords.stream()
                    .max(java.util.Comparator.comparingInt(String::length))
                    .orElse("");
            if (primaryKw.length() < 4 || !normDesc.contains(primaryKw)) {
                return false;
            }
        } else {
            // Non-strict mode: require at least ONE meaningful keyword (length >= 4) to match
            // This prevents matching generic/short words that could match multiple NPCs
            boolean hasLongMatch = false;
            for (String kw : npcKeywords) {
                if (kw.length() >= 4 && normDesc.contains(kw)) {
                    hasLongMatch = true;
                    break;
                }
            }
            if (!hasLongMatch) return false;
        }

        if (normDesc.contains("boss") != normNpc.contains("boss")) return false;
        if (normDesc.contains("uber") != normNpc.contains("uber")) return false;
        if (normDesc.contains("blighted") != normNpc.contains("blighted")) return false;
        return true;
    }

    public boolean matchesBoxName(String boxName, String questDesc) {
        if (questDesc.contains("bonus") && boxName.contains("bonus")) return true;
        if (questDesc.contains("cargo") && (boxName.contains("cargo") || boxName.contains("from_ship"))) return true;
        if (questDesc.contains("minerio") && (boxName.contains("cargo") || boxName.contains("from_ship"))) return true;
        // CORREÇÃO: Prometid, Duranium e Promerium só podem ser coletados de caixas
        // from_ship (carga dropada por NPCs), NÃO das caixas ore_*. Esses minérios
        // não têm mapeamento ore_N, então sem esta regra a missão não marcava nada.
        if ((questDesc.contains("prometid") || questDesc.contains("duranium") || questDesc.contains("promerium"))
                && (boxName.contains("from_ship") || boxName.contains("cargo"))) {
            return true;
        }
        // CORREÇÃO: as caixas de minério no DarkBot têm chaves como ore_0, ore_1,
        // ore_2, ore_8 (Prometium, Endurium, Terbium, Palladium). A descrição da
        // missão diz "collect x prometium", então o contains() direto nunca casaria
        // ("ore_0" não contém "prometium"). Mapeamos cada ore_N para o nome do
        // minério para marcar a caixa certa para coleta.
        if (boxName.contains("ore_0") && questDesc.contains("prometium")) return true;
        if (boxName.contains("ore_1") && questDesc.contains("endurium")) return true;
        if (boxName.contains("ore_2") && questDesc.contains("terbium")) return true;
        if (boxName.contains("ore_8") && questDesc.contains("palladium")) return true;
        // CORREÇÃO DO "MARCA TODOS OS ORES": o fallback genérico abaixo NÃO deve ser
        // usado quando a missão cita um ore ESPECÍFICO (prometium/endurium/terbium/
        // palladium). Antes, "Colete 30 Prometium" casava TODAS as caixas ore_* porque
        // questDesc.contains("prometium") era verdadeiro e o fallback marcava qualquer
        // ore_. Agora, se a descrição cita um ore específico, só a caixa daquele ore
        // (mapeada acima) é marcada; o fallback genérico só roda para missões GENÉRICAS
        // ("colete ore"/"colete minerio" sem ore específico).
        boolean specificOre = questDesc.contains("prometium") || questDesc.contains("endurium")
                || questDesc.contains("terbium") || questDesc.contains("palladium");
        if (!specificOre && boxName.contains("ore_")
                && (questDesc.contains("ore") || questDesc.contains("minerio"))) {
            return true;
        }
        // Fallback genérico SÓ para caixas NÃO-ore (bonus, etc.) ou quando a descrição
        // casa diretamente com o nome da caixa. Nunca marca todas as ores de uma vez.
        if (!boxName.contains("ore_")) {
            return boxName.contains(questDesc) || questDesc.contains(boxName);
        }
        return false;
    }

    public boolean matchesQuestRequirement(Box box, String questDesc) {
        String type = box.getTypeName() != null ? box.getTypeName().toLowerCase() : "";
        String desc = MissionMapLoader.normalize(questDesc);

        if (desc.contains("cargo") || desc.contains("minerio") || desc.contains("ore") || desc.contains("materia") || desc.contains("promerium") || desc.contains("duranium") || desc.contains("palladium")) {
            return type.contains("cargo") || type.contains("ore") || type.contains("solus") || type.contains("mineral") || type.contains("terrabia") || type.contains("from_ship") || type.contains("palladium") || type.contains("ore_8");
        }
        if (desc.contains("agatus")) {
            return type.contains("agatus") || type.contains("alien_egg");
        }
        if (desc.contains("plutus")) {
            return type.contains("plutus");
        }
        if (desc.contains("pumpkin") || desc.contains("abobora") || desc.contains("curcubitor")) {
            return type.contains("pumpkin") || type.contains("abobora") || type.contains("halloween");
        }
        if (desc.contains("gift") || desc.contains("presente") || desc.contains("candy") || desc.contains("doces")) {
            return type.contains("gift") || type.contains("candy") || type.contains("christmas") || type.contains("presente");
        }
        if (desc.contains("green") || desc.contains("verde") || desc.contains("booty") || desc.contains("chave") || desc.contains("key") || desc.contains("pirata")) {
            return type.contains("booty") || type.contains("green") || type.contains("pirate") || type.contains("gold") || type.contains("red") || type.contains("blue");
        }
        if (desc.contains("bonus") || desc.contains("caixa de b")) {
            return type.contains("bonus") || type.equals("box_d") || type.equals("box_c") || type.equals("box_b") || type.equals("box_a") || type.equals("gift");
        }
        return type.contains("bonus") || type.equals("box_d") || type.equals("box_c") || type.equals("box_b") || type.equals("box_a");
    }
}