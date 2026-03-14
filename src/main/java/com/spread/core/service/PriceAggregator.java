package com.spread.core.service;

import com.spread.core.model.Settings.Exchange;
import com.spread.core.model.Ticker;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class PriceAggregator {

    private final ConcurrentMap<String, ConcurrentMap<Exchange, Ticker>> tickersBySymbol =
            new ConcurrentHashMap<>();

    public void updateTicker(Ticker ticker, Exchange exchange) {
        tickersBySymbol
                .computeIfAbsent(ticker.getSymbol(), s -> new ConcurrentHashMap<>())
                .put(exchange, ticker);
    }

    public Map<Exchange, Ticker> getTickersForSymbol(String symbol) {
        ConcurrentMap<Exchange, Ticker> inner = tickersBySymbol.get(symbol);
        if (inner == null || inner.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(inner);
    }

    public Map<String, Map<Exchange, Ticker>> getAll() {
        Map<String, Map<Exchange, Ticker>> copy = new HashMap<>();
        tickersBySymbol.forEach((symbol, inner) -> {
            if (inner != null && !inner.isEmpty()) {
                copy.put(symbol, Map.copyOf(inner));
            }
        });
        return copy;
    }
}

