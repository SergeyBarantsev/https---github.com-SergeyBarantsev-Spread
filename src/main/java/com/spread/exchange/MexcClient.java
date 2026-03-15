package com.spread.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spread.core.model.Settings.Exchange;
import com.spread.core.model.Ticker;
import com.spread.core.service.PriceAggregator;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MEXC: получение bid/ask через REST (WebSocket у MEXC в protobuf).
 */
public class MexcClient implements ExchangeClient {

    private static final String TICKER_URL = "https://api.mexc.com/api/v3/ticker/bookTicker";
    private static final long POLL_INTERVAL_SEC = 5;

    private final OkHttpClient client;
    private final PriceAggregator aggregator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile List<String> currentSymbols = List.of();
    private ScheduledExecutorService scheduler;

    public MexcClient(OkHttpClient client, PriceAggregator aggregator) {
        this.client = client;
        this.aggregator = aggregator;
    }

    @Override
    public synchronized void connect(List<String> symbols) {
        disconnect();
        if (symbols == null || symbols.isEmpty()) {
            return;
        }
        currentSymbols = List.copyOf(symbols);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mexc-poll");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::pollTickers, 1, POLL_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void disconnect() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        currentSymbols = List.of();
    }

    @Override
    public String getName() {
        return Exchange.MEXC.name();
    }

    private void pollTickers() {
        List<String> syms = currentSymbols;
        if (syms.isEmpty()) {
            return;
        }
        try {
            Request request = new Request.Builder().url(TICKER_URL).get().build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return;
                }
                JsonNode arr = objectMapper.readTree(response.body().string());
                if (!arr.isArray()) {
                    return;
                }
                long ts = System.currentTimeMillis();
                for (JsonNode node : arr) {
                    String symbol = node.path("symbol").asText(null);
                    if (symbol == null || !syms.contains(symbol)) {
                        continue;
                    }
                    double bid = parseDouble(node, "bidPrice");
                    double ask = parseDouble(node, "askPrice");
                    if (bid <= 0 || ask <= 0) {
                        continue;
                    }
                    Ticker ticker = new Ticker(getName(), symbol, bid, ask, ts);
                    aggregator.updateTicker(ticker, Exchange.MEXC);
                }
            }
        } catch (IOException e) {
            System.err.println("MexcClient poll error: " + e.getMessage());
        }
    }

    private static double parseDouble(JsonNode node, String field) {
        JsonNode f = node.get(field);
        if (f == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(f.asText());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
