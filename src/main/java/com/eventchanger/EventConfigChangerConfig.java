package com.eventchanger;

import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.Dropdown;
import eu.darkbot.api.config.annotations.Number;
import eu.darkbot.api.config.annotations.Option;

@Configuration(value = "configChanger.config")
public class EventConfigChangerConfig {
    public Config escort       = new Config();
    public Config labyrinth    = new Config();
    public Config plutus       = new Config();
    public Config npc_event    = new Config();
    @Number.Disabled
    public Config agatus_event = new Config();
    public Config world_boss_event = new Config();

    public static class Config {
        public boolean activate = false;
        @Dropdown(options = ConfigSupplier.class)
        public String BOT_PROFILE_OPEN   = "";
        @Dropdown(options = ConfigSupplier.class)
        public String BOT_PROFILE_CLOSED = "";
        public boolean jump_out = false;
        @Option.Ignore
        public long lastCheck = 0L;
    }
}
