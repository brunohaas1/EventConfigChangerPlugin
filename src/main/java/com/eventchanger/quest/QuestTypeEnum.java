package com.eventchanger.quest;

import eu.darkbot.api.config.annotations.Configuration;

@Configuration("quest_module.quest_type")
public enum QuestTypeEnum {
    /** Quests normais / diárias / semanais que já estão ativas no personagem */
    NORMAL,
    /** Quests urgentes (time-limited) — serão tratadas com prioridade */
    URGENT
}
