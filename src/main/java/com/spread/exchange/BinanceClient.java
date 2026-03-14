package com.spread.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spread.core.model.Ticker;
import com.spread.core.model.Settings.Exchange;
import com.spread.core.service.PriceAggregator;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class BinanceClient extends WebSocketListener implements ExchangeClient {

    private static final String BASE_URL = "wss://stream.binance.com:9443/stream?streams=";

    private static final ScheduledExecutorService RECONNECT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor();

    private final OkHttpClient client;
    private final PriceAggregator aggregator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSocket webSocket;
    private List<String> currentSymbols;
    private int reconnectAttempts;

    public BinanceClient(OkHttpClient client, PriceAggregator aggregator) {
        this.client = client;
        this.aggregator = aggregator;
    }

    @Override
    public synchronized void connect(List<String> symbols) {
        disconnect();
        if (symbols == null || symbols.isEmpty()) {
            return;
        }
        reconnectAttempts = 0;
        currentSymbols = List.copyOf(symbols);
        String streams = symbols.stream()
                .map(s -> s.toLowerCase(Locale.ROOT) + "@bookTicker")
                .collect(Collectors.joining("/"));
        String url = BASE_URL + streams;

        Request request = new Request.Builder().url(url).build();
        webSocket = client.newWebSocket(request, this);
    }

    @Override
    public synchronized void disconnect() {
        if (webSocket != null) {
            webSocket.close(1000, "client disconnect");
            webSocket = null;
        }
    }

    @Override
    public String getName() {
        return Exchange.BINANCE.name();
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        try {
            JsonNode root = objectMapper.readTree(text);
            JsonNode dataNode = root.has("data") ? root.get("data") : root;
            if (dataNode == null) {
                return;
            }
            String symbol = getText(dataNode, "s");
            double bid = getDouble(dataNode, "b");
            double ask = getDouble(dataNode, "a");
            long eventTime = dataNode.has("E") ? dataNode.get("E").asLong() : System.currentTimeMillis();

            if (symbol == null || bid <= 0 || ask <= 0) {
                return;
            }

            Ticker ticker = new Ticker(getName(), symbol, bid, ask, eventTime);
            aggregator.updateTicker(ticker, Exchange.BINANCE);
        } catch (IOException e) {
            System.err.println("BinanceClient parse error: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        onMessage(webSocket, bytes.utf8());
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        System.err.println("BinanceClient failure: " + (t != null ? t.getMessage() : "unknown"));
        synchronized (this) {
            if (this.webSocket == webSocket && currentSymbols != null && !currentSymbols.isEmpty()) {
                int attempt = ++reconnectAttempts;
                long delaySeconds = Math.min(60, 1L << Math.min(attempt, 5));
                RECONNECT_SCHEDULER.schedule(() -> connect(currentSymbols), delaySeconds, TimeUnit.SECONDS);
            }
        }
    }

    private String getText(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return f != null ? f.asText() : null;
    }

    private double getDouble(JsonNode node, String field) {
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

