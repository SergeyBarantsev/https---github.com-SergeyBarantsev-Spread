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

    /** @return true если монета добавлена и запись успешна (или уже была в списке), false при ошибке записи */
    public boolean addUserCoin(String symbol) {
        List<String> current = new ArrayList<>(loadList(userCoinsPath));
        String normalized = symbol.trim().toUpperCase();
        if (normalized.isEmpty() || current.contains(normalized)) {
            return true;
        }
        current.add(normalized);
        return saveList(userCoinsPath, current);
    }

    /** Очищает оба списка. @return true если обе записи успешны */
    public boolean clearAll() {
        return saveList(baseCoinsPath, List.of()) && saveList(userCoinsPath, List.of());
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

    private boolean saveList(Path path, List<String> symbols) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(symbols);
            Files.write(path, bytes);
            return true;
        } catch (IOException e) {
            System.err.println("CoinStorage write error for " + path + ": " + e.getMessage());
            return false;
        }
    }
}

