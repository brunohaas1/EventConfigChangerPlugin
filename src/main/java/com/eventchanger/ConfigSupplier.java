package com.eventchanger;

import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.config.annotations.Dropdown;
import eu.darkbot.api.managers.ConfigAPI;

import java.util.List;

public class ConfigSupplier implements Dropdown.Options<String> {

    private static ConfigAPI configAPI;

    public static void init(PluginAPI api) {
        configAPI = api.requireAPI(ConfigAPI.class);
    }

    @Override
    public String getText(String string) {
        return string;
    }

    @Override
    public List<String> options() {
        if (configAPI == null) return List.of();
        return configAPI.getConfigProfiles();
    }
}
