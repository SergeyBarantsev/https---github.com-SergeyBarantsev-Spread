package com.spread.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CoinStorage {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path baseCoinsPath;
    private final Path userCoinsPath;

    public CoinStorage(Path baseCoinsPath, Path userCoinsPath) {
        this.baseCoinsPath = baseCoinsPath;
        this.userCoinsPath = userCoinsPath;
    }

    public List<String> loadMergedCoins() {
        Set<String> merged = new HashSet<>();
        merged.addAll(loadList(baseCoinsPath));
        merged.addAll(loadList(userCoinsPath));
        return new ArrayList<>(merged);
    }

    public void addUserCoin(String symbol) {
        List<String> current = new ArrayList<>(loadList(userCoinsPath));
        String normalized = symbol.trim().toUpperCase();
        if (!normalized.isEmpty() && !current.contains(normalized)) {
            current.add(normalized);
            saveList(userCoinsPath, current);
        }
    }

    private List<String> loadList(Path path) {
        File file = path.toFile();
        if (!file.exists()) {
            return List.of();
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) {
                return List.of();
            }
            return objectMapper.readValue(bytes, new TypeReference<List<String>>() {});
        } catch (IOException e) {
            System.err.println("CoinStorage read error for " + path + ": " + e.getMessage());
            return List.of();
        }
    }

    private void saveList(Path path, List<String> symbols) {
        try {
            Files.createDirectories(path.getParent());
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(symbols);
            Files.write(path, bytes);
        } catch (IOException e) {
            System.err.println("CoinStorage write error for " + path + ": " + e.getMessage());
        }
    }
}

