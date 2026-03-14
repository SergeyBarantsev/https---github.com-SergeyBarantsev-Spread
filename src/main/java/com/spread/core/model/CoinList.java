package com.spread.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CoinList {

    private final List<String> symbols = new ArrayList<>();

    public List<String> getSymbols() {
        return Collections.unmodifiableList(symbols);
    }

    public void setSymbols(List<String> newSymbols) {
        symbols.clear();
        if (newSymbols != null) {
            for (String s : newSymbols) {
                if (s != null && !s.isBlank()) {
                    symbols.add(s.trim().toUpperCase());
                }
            }
        }
    }

    public void addSymbol(String symbol) {
        if (symbol == null) {
            return;
        }
        String normalized = symbol.trim().toUpperCase();
        if (!normalized.isEmpty() && !symbols.contains(normalized)) {
            symbols.add(normalized);
        }
    }
}

