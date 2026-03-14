package com.spread.core.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spread.core.model.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SettingsStorage {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path settingsPath;

    public SettingsStorage(Path settingsPath) {
        this.settingsPath = settingsPath;
    }

    public Settings load() {
        if (!Files.exists(settingsPath)) {
            return new Settings();
        }
        try {
            byte[] bytes = Files.readAllBytes(settingsPath);
            if (bytes.length == 0) {
                return new Settings();
            }
            return objectMapper.readValue(bytes, Settings.class);
        } catch (IOException e) {
            System.err.println("SettingsStorage read error for " + settingsPath + ": " + e.getMessage());
            return new Settings();
        }
    }

    /** @return true если запись успешна */
    public boolean save(Settings settings) {
        try {
            Path parent = settingsPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(settings);
            Files.write(settingsPath, bytes);
            return true;
        } catch (IOException e) {
            System.err.println("SettingsStorage write error for " + settingsPath + ": " + e.getMessage());
            return false;
        }
    }
}

