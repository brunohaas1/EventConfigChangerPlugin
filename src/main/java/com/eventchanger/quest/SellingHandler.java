package com.eventchanger.quest;

import eu.darkbot.api.config.types.BoxInfo;
import eu.darkbot.api.config.types.NpcInfo;
import eu.darkbot.api.game.entities.Station;
import eu.darkbot.api.game.other.GameMap;
import eu.darkbot.api.managers.OreAPI;
import eu.darkbot.api.managers.QuestAPI;
import eu.darkbot.api.managers.QuestAPI.Requirement;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * Responsável pela venda de carga (quando o hold está cheio) e pela venda de
 * minério de uma quest do tipo SELL_ORE: navega até a estação base/refinery e
 * abre o trade.
 */
public class SellingHandler {

    private final QuestContext ctx;
    private final QuestLogger logger;
    private final MapResolver mapResolver;

    public SellingHandler(QuestContext ctx, QuestLogger logger, MapResolver mapResolver) {
        this.ctx = ctx;
        this.logger = logger;
        this.mapResolver = mapResolver;
    }

    public void handleSellingCargo(long now) {
        if (ctx.config.sell.useExternalOreSeller) {
            ctx.currentAction = "[Venda] Aguardando Ore Seller (SharedPlugin)...";
            // Reset safety timer and cargo state to avoid infinite loop
            ctx.sellStartTime = 0L;
            ctx.tradeWindowOpen = false;
            // Give up after 30 seconds if external seller doesn't handle it
            if (now - ctx.lastSellAttemptTime > 30_000L) {
                logger.logDebug("Ore Seller externo nao resolveu a venda em 30s. Saindo do modo venda.");
                ctx.isSellingCargo = false;
                ctx.lastSellAttemptTime = now;
            }
            return;
        }

        // Safety: if selling started more than 3 minutes ago, give up to avoid infinite loop
        if (ctx.sellStartTime == 0L) ctx.sellStartTime = now;
        if (now - ctx.sellStartTime > 180_000L) {
            System.err.println("[QuestModule] Venda travada! Resetando estado de venda apos 3 minutos.");
            ctx.isSellingCargo = false;
            ctx.tradeWindowOpen = false;
            ctx.sellStartTime = 0L;
            ctx.oreAPI.showTrade(false, null);
            return;
        }

        // Check if there are actually ores to sell (ignore protected types by default)
        boolean hasOres = false;
        for (OreAPI.Ore ore : OreAPI.Ore.values()) {
            if (ore == OreAPI.Ore.SEPROM)   continue; // Never sell Seprom
            if (ore == OreAPI.Ore.XENOMIT)  continue; // Never sell Xenomit
            if (ore == OreAPI.Ore.PALLADIUM) continue; // Never sell Palladium
            if (ctx.oreAPI.getAmount(ore) > 0) { hasOres = true; break; }
        }

        // Also check if cargo is actually above threshold (safety net)
        if (!hasOres && ctx.statsAPI.getMaxCargo() > 0 && ctx.statsAPI.getCargo() <= ctx.statsAPI.getMaxCargo() - 20) {
            logger.logDebug("Sem minerios para vender e cargo nao esta mais cheio. Saindo do modo de venda.");
            ctx.isSellingCargo = false;
            ctx.tradeWindowOpen = false;
            ctx.sellStartTime = 0L;
            ctx.oreAPI.showTrade(false, null);
            return;
        }

        if (!hasOres) {
            logger.logDebug("Sem minerios para vender (cargo=" + ctx.statsAPI.getCargo() + "/" + ctx.statsAPI.getMaxCargo() + "). Saindo do modo de venda.");
            ctx.isSellingCargo = false;
            ctx.tradeWindowOpen = false;
            ctx.sellStartTime = 0L;
            ctx.oreAPI.showTrade(false, null);
            return;
        }

        // If the trade UI is open and we can sell - sell everything
        if (ctx.oreAPI.canSellOres()) {
            ctx.tradeWindowOpen = true;
            ctx.lastTradeOpenTime = now;
            ctx.currentAction = "[Venda] Vendendo minerios... cargo=" + ctx.statsAPI.getCargo();
            if (now - ctx.lastSellAttemptTime > 800) {
                ctx.lastSellAttemptTime = now;
                for (OreAPI.Ore ore : OreAPI.Ore.values()) {
                    if (ore == OreAPI.Ore.SEPROM)   continue;
                    if (ore == OreAPI.Ore.XENOMIT)  continue;
                    if (ore == OreAPI.Ore.PALLADIUM) continue;
                    if (ctx.oreAPI.getAmount(ore) > 0) {
                        logger.logDebug("Vendendo " + ore.name() + ": " + ctx.oreAPI.getAmount(ore));
                        ctx.oreAPI.sellOre(ore);
                    }
                }
            }
            return;
        }

        // Trade window was opened but canSellOres is momentarily false - wait briefly
        if (ctx.tradeWindowOpen && now - ctx.lastTradeOpenTime < 4000) {
            ctx.currentAction = "[Venda] Aguardando UI de trade... (" + (4000 - (now - ctx.lastTradeOpenTime)) / 1000 + "s)";
            return;
        }
        ctx.tradeWindowOpen = false;

        // Navigate to home map
        GameMap homeMap = mapResolver.resolveHomeMap();
        if (homeMap == null) {
            ctx.currentAction = "[Venda] Nao foi possivel encontrar o mapa base.";
            return;
        }

        GameMap currentMap = ctx.heroAPI.getMap();
        if (currentMap == null || currentMap.getId() != homeMap.getId()) {
            mapResolver.navigateToMap(homeMap, now);
            return;
        }

        // We are on the home map - find ANY station (Refinery preferred, fallback to any)
        Collection<? extends Station> stations = ctx.entitiesAPI.getStations();
        Station bestStation = null;

        if (stations != null && !stations.isEmpty()) {
            // Prefer Refinery
            for (Station s : stations) {
                if (s instanceof Station.Refinery) {
                    if (bestStation == null || s.distanceTo(ctx.heroAPI) < bestStation.distanceTo(ctx.heroAPI)) {
                        bestStation = s;
                    }
                }
            }
            // Fallback: any station
            if (bestStation == null) {
                for (Station s : stations) {
                    if (bestStation == null || s.distanceTo(ctx.heroAPI) < bestStation.distanceTo(ctx.heroAPI)) {
                        bestStation = s;
                    }
                }
            }
        }

        if (bestStation != null) {
            double dist = bestStation.distanceTo(ctx.heroAPI);
            logger.logDebug("Estacao encontrada: " + bestStation.getClass().getSimpleName() + " dist=" + (int)dist + " Refinery=" + (bestStation instanceof Station.Refinery));

            if (dist > 200) {
                ctx.setShipMode("roam");
                ctx.movementAPI.moveTo(bestStation);
                ctx.currentAction = "[Venda] Movendo ate estacao (dist: " + (int) dist + ")";
                return;
            }

            // Close enough - try to open trade
            if (now - ctx.lastTradeOpenTime > 3000) {
                ctx.lastTradeOpenTime = now;
                ctx.tradeWindowOpen = true;
                if (bestStation instanceof Station.Refinery) {
                    ctx.oreAPI.showTrade(true, (Station.Refinery) bestStation);
                    logger.logDebug("showTrade(Refinery) dist=" + (int) dist);
                } else {
                    // Not a refinery object - try interacting via GUI click
                    ctx.oreAPI.showTrade(false, null); // close first
                    logger.logDebug("Estacao nao e Refinery! Tipo: " + bestStation.getClass().getName());
                }
            }
            ctx.currentAction = "[Venda] Na estacao, abrindo trade... (dist: " + (int) dist + ")";
        } else {
            // No station found yet - fly to center to discover entities
            ctx.currentAction = "[Venda] Procurando estacao... voando ao centro";
            ctx.setShipMode("roam");
            ctx.movementAPI.moveTo(10000, 6200);
        }
    }

    public void handleSellOre(Requirement req, long now) {
        if (req == null || req.getDescription() == null) {
            ctx.currentAction = "[Venda] Requisicao invalida.";
            return;
        }

        OreAPI.Ore ore = resolveOreFromDesc(req.getDescription());
        if (ore == null) {
            ctx.currentAction = "[Venda] Nao foi possivel determinar o minerio.";
            return;
        }

        int amount = ctx.oreAPI.getAmount(ore);
        if (amount <= 0) {
            ctx.currentAction = "[Venda] Sem " + ore.name() + " para vender. Coletando...";
            if (ctx.config.autoCollectLoot) {
                ctx.botAPI.setModule(ctx.defaultLootCollectorModule);
            }
            return;
        }

        if (ctx.oreAPI.canSellOres()) {
            ctx.currentAction = "[Venda] Vendendo " + amount + " " + ore.name() + "...";
            if (now - ctx.lastSellAttemptTime > 800) {
                ctx.lastSellAttemptTime = now;
                ctx.oreAPI.sellOre(ore);
            }
        } else {
            GameMap homeMap = mapResolver.resolveHomeMap();
            if (homeMap == null) {
                ctx.currentAction = "[Venda] Nao foi possivel encontrar o mapa base.";
                return;
            }

            GameMap currentMap = ctx.heroAPI.getMap();
            if (currentMap != null && currentMap.getId() == homeMap.getId()) {
                Collection<? extends Station> stations = ctx.entitiesAPI.getStations();
                Optional<? extends Station> station = (stations != null)
                        ? stations.stream()
                            .filter(s -> s instanceof Station.Refinery)
                            .min(Comparator.comparingDouble(s -> s.distanceTo(ctx.heroAPI)))
                        : Optional.empty();

                if (station.isPresent()) {
                    Station s = station.get();
                    double dist = s.distanceTo(ctx.heroAPI);
                    if (dist > 200) {
                        ctx.setShipMode("roam");
                        ctx.movementAPI.moveTo(s);
                        ctx.currentAction = "[Venda] Movendo ate a estacao base (dist: " + (int) dist + ")";
                    } else {
                        if (now - ctx.lastTradeOpenTime > 3000) {
                            ctx.lastTradeOpenTime = now;
                            ctx.tradeWindowOpen = true;
                            ctx.oreAPI.showTrade(true, (Station.Refinery) s);
                        }
                        ctx.currentAction = "[Venda] Abrindo menu de trade da estacao...";
                    }
                } else {
                    ctx.currentAction = "[Venda] Voando para o centro do mapa para descobrir estacao...";
                    ctx.setShipMode("roam");
                    ctx.movementAPI.moveTo(10000, 6200);
                }
            } else {
                mapResolver.navigateToMap(homeMap, now);
            }
        }
    }

    private OreAPI.Ore resolveOreFromDesc(String desc) {
        String normalized = MissionMapLoader.normalize(desc);
        if (normalized.contains("promerium")) return OreAPI.Ore.PROMERIUM;
        if (normalized.contains("duranium"))  return OreAPI.Ore.DURANIUM;
        if (normalized.contains("prometid"))  return OreAPI.Ore.PROMETID;
        if (normalized.contains("xenomit"))   return OreAPI.Ore.XENOMIT;
        if (normalized.contains("seprom"))    return OreAPI.Ore.SEPROM;
        if (normalized.contains("terbium"))   return OreAPI.Ore.TERBIUM;
        if (normalized.contains("endurium"))  return OreAPI.Ore.ENDURIUM;
        if (normalized.contains("prometium")) return OreAPI.Ore.PROMETIUM;
        if (normalized.contains("palladium")) return OreAPI.Ore.PALLADIUM;
        if (normalized.contains("osmium"))    return OreAPI.Ore.OSMIUM;
        return null;
    }
}