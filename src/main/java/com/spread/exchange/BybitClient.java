package com.spread.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spread.core.model.Settings.Exchange;
import com.spread.core.model.Ticker;
import com.spread.core.service.PriceAggregator;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BybitClient extends WebSocketListener implements ExchangeClient {

    private static final String URL = "wss://stream.bybit.com/v5/public/spot";

    private static final ScheduledExecutorService RECONNECT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor();

    private final OkHttpClient client;
    private final PriceAggregator aggregator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSocket webSocket;
    private List<String> currentSymbols;
    private int reconnectAttempts;

    public BybitClient(OkHttpClient client, PriceAggregator aggregator) {
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
        Request request = new Request.Builder().url(URL).build();
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
        return Exchange.BYBIT.name();
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        if (currentSymbols == null || currentSymbols.isEmpty()) {
            return;
        }
        try {
            // Bybit topic: tickers.SYMBOL (e.g., tickers.BTCUSDT)
            var root = objectMapper.createObjectNode();
            root.put("op", "subscribe");
            var args = root.putArray("args");
            for (String symbol : currentSymbols) {
                args.add("tickers." + symbol);
            }
            webSocket.send(root.toString());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        try {
            JsonNode root = objectMapper.readTree(text);
            if (!"tickers".equals(root.path("topic").asText())) {
                return;
            }
            JsonNode dataArr = root.get("data");
            if (dataArr == null || !dataArr.isArray()) {
                return;
            }
            for (JsonNode data : dataArr) {
                String symbol = data.path("s").asText(null);
                double bid = parseDouble(data, "bid1Price");
                double ask = parseDouble(data, "ask1Price");
                long ts = data.has("ts") ? data.get("ts").asLong() : System.currentTimeMillis();
                if (symbol == null || bid <= 0 || ask <= 0) {
                    continue;
                }
                Ticker ticker = new Ticker(getName(), symbol, bid, ask, ts);
                aggregator.updateTicker(ticker, Exchange.BYBIT);
            }
        } catch (IOException e) {
            System.err.println("BybitClient parse error: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        onMessage(webSocket, bytes.utf8());
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        System.err.println("BybitClient failure: " + (t != null ? t.getMessage() : "unknown"));
        synchronized (this) {
            if (this.webSocket == webSocket && currentSymbols != null && !currentSymbols.isEmpty()) {
                int attempt = ++reconnectAttempts;
                long delaySeconds = Math.min(60, 1L << Math.min(attempt, 5));
                RECONNECT_SCHEDULER.schedule(() -> connect(currentSymbols), delaySeconds, TimeUnit.SECONDS);
            }
        }
    }

    private double parseDouble(JsonNode node, String field) {
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

