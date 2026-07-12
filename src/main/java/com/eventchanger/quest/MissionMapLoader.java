package com.eventchanger.quest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Carrega e consulta os mapas externos de título->descrição de missão
 * (EN / PT / PT overrides) a partir de arquivos .properties, e resolve o
 * texto de objetivo de uma quest a partir do título.
 */
public class MissionMapLoader {

    private final QuestContext ctx;
    private final QuestLogger logger;

    public MissionMapLoader(QuestContext ctx, QuestLogger logger) {
        this.ctx = ctx;
        this.logger = logger;
    }

    public void loadExternalMissionMap() {
        try {
            Path base = resolvePluginDataDir();
            loadMissionMapFile(base.resolve("missions_npc_map.properties"), ctx.externalMissionMap);
            loadMissionMapFile(base.resolve("missions_npc_map_pt.properties"), ctx.externalMissionMapPt);
            loadMissionMapFile(base.resolve("missions_npc_map_pt_overrides.properties"), ctx.externalMissionMapPtOverrides);

            System.out.println("[QuestModule] Loaded external mission map from " + base
                    + " | EN=" + ctx.externalMissionMap.size()
                    + " PT=" + ctx.externalMissionMapPt.size()
                    + " PT_OVR=" + ctx.externalMissionMapPtOverrides.size());
        } catch (Exception e) {
            System.err.println("[QuestModule] Failed to load external mission map: " + e.getMessage());
        }
    }

    public Path resolvePluginDataDir() {
        Path cwd = Path.of(System.getProperty("user.dir", "."));
        Path[] candidates = {
                cwd.resolve("plugins"),
                cwd.resolve("plugins").resolve("EventConfigChangerPlugin"),
                cwd,
                Path.of(".").toAbsolutePath().normalize()
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)
                    && (Files.exists(candidate.resolve("missions_npc_map.properties"))
                    || Files.exists(candidate.resolve("missions_npc_map_pt.properties"))
                    || Files.exists(candidate.resolve("missions_npc_map_pt_overrides.properties")))) {
                return candidate;
            }
        }
        return candidates[0];
    }

    private void loadMissionMapFile(Path file, Map<String, String> target) throws java.io.IOException {
        if (!Files.exists(file)) return;
        for (String line : Files.readAllLines(file)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 1) continue;
            String key = normalize(line.substring(0, eq).trim());
            String val = line.substring(eq + 1).trim();
            if (!key.isEmpty() && !val.isEmpty()) {
                target.put(key, val);
            }
        }
    }

    public String resolveQuestObjectiveText(String questTitle) {
        return resolveQuestObjectiveText(questTitle, false);
    }

    public String resolveQuestObjectiveText(String questTitle, boolean strict) {
        if (questTitle == null || questTitle.isEmpty()) return "";

        String normalizedTitle = normalize(questTitle);

        String overriddenTitle = ctx.externalMissionMapPtOverrides.get(normalizedTitle);
        if (overriddenTitle != null && !overriddenTitle.isEmpty()) {
            normalizedTitle = normalize(overriddenTitle);
        }

        String description = ctx.externalMissionMapPt.get(normalizedTitle);
        if (description == null || description.isEmpty()) {
            description = ctx.externalMissionMap.get(normalizedTitle);
        }

        if (description == null || description.isEmpty()) {
            String bestKey = findBestMappingKey(normalizedTitle);
            if (bestKey != null && !bestKey.isEmpty()) {
                description = ctx.externalMissionMapPt.get(bestKey);
                if (description == null || description.isEmpty()) {
                    description = ctx.externalMissionMap.get(bestKey);
                }
            }
        }

        if ((description == null || description.isEmpty()) && !strict) {
            description = findApproximateMissionDescription(normalizedTitle);
        }

        if (description == null || description.isEmpty()) {
            description = questTitle;
        }

        return description;
    }

    private String findApproximateMissionDescription(String normalizedTitle) {
        String best = findApproximateMissionDescriptionInMap(ctx.externalMissionMapPt, normalizedTitle);
        if (best != null) return best;
        return findApproximateMissionDescriptionInMap(ctx.externalMissionMap, normalizedTitle);
    }

    private String findApproximateMissionDescriptionInMap(Map<String, String> map, String normalizedTitle) {
        String bestDescription = null;
        int bestScore = 0;

        for (Map.Entry<String, String> entry : map.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isEmpty()) continue;

            int score = 0;
            if (key.equals(normalizedTitle)) {
                score = 1000;
            } else if (key.contains(normalizedTitle) || normalizedTitle.contains(key)) {
                score = Math.min(key.length(), normalizedTitle.length());
            }

            if (score > bestScore) {
                bestScore = score;
                bestDescription = entry.getValue();
            }
        }

        return bestDescription;
    }

    // Try to find best matching key from external maps using token intersection
    public String findBestMappingKey(String titleKey) {
        if (titleKey == null || titleKey.isEmpty()) return null;
        java.util.Set<String> stop = new java.util.HashSet<>(java.util.Arrays.asList(
                "mission","missions","level","map","daily","weekly","special","elite","the","a","an","of","in","season","pass"));
        java.util.Set<String> titleTokens = new java.util.HashSet<>();
        for (String t : titleKey.split("\\s+")) {
            if (t.length() <= 2) continue;
            if (stop.contains(t)) continue;
            titleTokens.add(t);
        }
        if (titleTokens.isEmpty()) return null;

        String bestKey = null;
        int bestScore = 0;

        java.util.List<String> candidates = new java.util.ArrayList<>();
        candidates.addAll(ctx.externalMissionMap.keySet());
        candidates.addAll(ctx.externalMissionMapPt.keySet());

        for (String k : candidates) {
            if (k == null || k.isEmpty()) continue;
            java.util.Set<String> keyTokens = new java.util.HashSet<>();
            for (String t : k.split("\\s+")) {
                if (t.length() <= 2) continue;
                if (stop.contains(t)) continue;
                keyTokens.add(t);
            }
            if (keyTokens.isEmpty()) continue;
            java.util.Set<String> copy = new java.util.HashSet<>(titleTokens);
            copy.retainAll(keyTokens);
            int inter = copy.size();
            if (inter <= 0) continue;
            int minLen = Math.min(titleTokens.size(), keyTokens.size());
            int minRequired = Math.max(2, (int)Math.ceil(minLen * 0.5));
            if (inter >= minRequired) {
                if (inter > bestScore) {
                    bestScore = inter;
                    bestKey = k;
                } else if (inter == bestScore && bestKey != null) {
                    if (k.contains(titleKey) || titleKey.contains(k)) {
                        bestKey = k;
                    }
                }
            }
        }
        if (bestScore < 2) return null;
        return bestKey;
    }

    public static String normalize(String text) {
        if (text == null) return "";
        try {
            String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
            normalized = normalized.replaceAll("\\p{M}", "");
            normalized = normalized.toLowerCase();
            // Translate Portuguese NPC plurals/variants to DB English singulars
            normalized = normalized.replaceAll("interceptador(es)?|interceptor(es)?", "interceptor");
            normalized = normalized.replaceAll("sabotador(es)?|saboteur(s)?", "saboteur");
            normalized = normalized.replaceAll("aniquilador(es)?|annihilator(s)?", "annihilator");
            normalized = normalized.replaceAll("cristalino(s)?|kristallin(s)?", "kristallin");
            normalized = normalized.replaceAll("cristalon(s)?|kristallon(s)?", "kristallon");
            normalized = normalized.replaceAll("protegit(s)?", "protegit");
            normalized = normalized.replaceAll("saimon(s)?", "saimon");
            normalized = normalized.replaceAll("mordon(s)?", "mordon");
            normalized = normalized.replaceAll("devolariun(s)?|devolarium(s)?", "devolarium");
            normalized = normalized.replaceAll("sibelon(s)?", "sibelon");
            normalized = normalized.replaceAll("sibelonit(s)?", "sibelonit");
            normalized = normalized.replaceAll("lordakium(s)?", "lordakium");
            normalized = normalized.replaceAll("cubikon(s)?", "cubikon");
            normalized = normalized.replaceAll("lordakia(s)?", "lordakia");

            normalized = normalized.replaceAll("[^a-z0-9]", " ");
            normalized = normalized.replaceAll("\\s+", " ").trim();
            return normalized;
        } catch (Exception e) {
            return text.toLowerCase();
        }
    }
}