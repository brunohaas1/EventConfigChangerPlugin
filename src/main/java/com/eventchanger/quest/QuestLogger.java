package com.eventchanger.quest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centraliza os utilitários de log do QuestModule: log verboso (via config) e
 * escrita de entradas de diagnóstico em arquivo.
 */
public class QuestLogger {

    private final QuestContext ctx;

    // Throttle de logs repetitivos: evita spam quando o mesmo log é emitido a
    // cada tick (~40ms). Chave = mensagem, valor = timestamp da última emissão.
    private final Map<String, Long> lastThrottledLog = new ConcurrentHashMap<>();

    public QuestLogger(QuestContext ctx) {
        this.ctx = ctx;
    }

    public boolean isVerboseLoggingEnabled() {
        return ctx.config != null && ctx.config.logging != null && ctx.config.logging.verbose;
    }

    public void logDebug(String msg) {
        if (!isVerboseLoggingEnabled()) return;
        System.out.println("[QuestModule] DEBUG " + msg);
        appendPluginLog("[DEBUG] " + msg);
    }

    /**
     * Log de debug com throttle por mensagem: só imprime se já se passaram pelo
     * menos {@code minIntervalMs} desde a última vez que a MESMA mensagem foi
     * emitida. Usado para logs que são chamados todo tick (ex: getNpcInfos,
     * processamento de quests secundárias) e gerariam spam no console.
     */
    public void logDebugThrottled(String msg, long minIntervalMs) {
        if (!isVerboseLoggingEnabled()) return;
        long now = System.currentTimeMillis();
        Long last = lastThrottledLog.get(msg);
        if (last != null && now - last < minIntervalMs) return;
        lastThrottledLog.put(msg, now);
        System.out.println("[QuestModule] DEBUG " + msg);
        appendPluginLog("[DEBUG] " + msg);
    }

    /**
     * Log de diagnóstico SEMPRE ligado (não depende de verbose). Escreve no console
     * e no arquivo EventConfigChanger.log para permitir depurar o fluxo de aceite
     * de quest sem precisar ativar o modo verboso. Usado para resolver de uma vez
     * problemas de clique/coordenadas do QuestGiver.
     */
    public void logDiagnostic(String msg) {
        System.out.println("[QuestModule][DIAG] " + msg);
        appendPluginLog("[DIAG] " + msg);
    }

    /**
     * Append plugin-specific log entries to a file for easier diagnostics.
     */
    public void appendPluginLog(String msg) {
        if (ctx.config != null && ctx.config.logging != null && !ctx.config.logging.logToFile) {
            return;
        }
        try {
            Path logFile = Path.of(System.getProperty("user.dir", ".")).resolve("EventConfigChanger.log");
            Files.writeString(logFile, Instant.now() + " " + msg + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    /**
     * Igual a {@link #appendPluginLog(String)} porém com throttle por mensagem:
     * só escreve no arquivo se já se passaram pelo menos {@code minIntervalMs}
     * desde a última vez que a MESMA mensagem foi registrada. Usado para logs
     * emitidos a cada tick / para cada variante de NPC (ex: MATCH PRINCIPAL),
     * que gerariam spam no arquivo de log.
     */
    public void appendPluginLogThrottled(String msg, long minIntervalMs) {
        appendPluginLogThrottled(msg, msg, minIntervalMs);
    }

    /**
     * Variante que usa uma {@code throttleKey} separada da {@code msg} gravada.
     * Permite agrupar variantes de uma mesma missão (ex: "Destrói Lordakia"
     * casando com 20 variantes de NPC) sob uma única chave estável (a descrição
     * da missão), assim o log aparece no máximo 1 vez a cada {@code minIntervalMs}
     * para a missão inteira, em vez de 1 vez por variante de NPC.
     */
    public void appendPluginLogThrottled(String throttleKey, String msg, long minIntervalMs) {
        long now = System.currentTimeMillis();
        // Prefixo "F:" para não colidir com as chaves do logDebugThrottled (console)
        Long last = lastThrottledLog.get("F:" + throttleKey);
        if (last != null && now - last < minIntervalMs) return;
        lastThrottledLog.put("F:" + throttleKey, now);
        appendPluginLog(msg);
    }
}
