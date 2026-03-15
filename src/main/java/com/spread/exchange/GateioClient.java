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

public class GateioClient extends WebSocketListener implements ExchangeClient {

    private static final String URL = "wss://api.gateio.ws/ws/v4/";

    private static final ScheduledExecutorService RECONNECT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor();

    private final OkHttpClient client;
    private final PriceAggregator aggregator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSocket webSocket;
    private List<String> currentSymbols;
    private int reconnectAttempts;

    public GateioClient(OkHttpClient client, PriceAggregator aggregator) {
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
        return Exchange.GATEIO.name();
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        if (currentSymbols == null || currentSymbols.isEmpty()) {
            return;
        }
        try {
            var root = objectMapper.createObjectNode();
            root.put("time", System.currentTimeMillis() / 1000);
            root.put("channel", "spot.tickers");
            root.put("event", "subscribe");
            var payload = root.putArray("payload");
            for (String symbol : currentSymbols) {
                payload.add(toGateSymbol(symbol));
            }
            webSocket.send(root.toString());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        try {
            JsonNode root = objectMapper.readTree(text);
            if (!"update".equals(root.path("event").asText(null))) {
                return;
            }
            if (!"spot.tickers".equals(root.path("channel").asText(null))) {
                return;
            }
            JsonNode result = root.get("result");
            if (result == null) {
                return;
            }
            String currencyPair = result.path("currency_pair").asText(null);
            if (currencyPair == null) {
                return;
            }
            String symbol = fromGateSymbol(currencyPair);
            double bid = parseDouble(result, "highest_bid");
            double ask = parseDouble(result, "lowest_ask");
            long ts = result.has("time_ms") ? result.get("time_ms").asLong() : System.currentTimeMillis();
            if (symbol == null || bid <= 0 || ask <= 0) {
                return;
            }
            Ticker ticker = new Ticker(getName(), symbol, bid, ask, ts);
            aggregator.updateTicker(ticker, Exchange.GATEIO);
        } catch (IOException e) {
            System.err.println("GateioClient parse error: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        onMessage(webSocket, bytes.utf8());
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        System.err.println("GateioClient failure: " + (t != null ? t.getMessage() : "unknown"));
        synchronized (this) {
            if (this.webSocket == webSocket && currentSymbols != null && !currentSymbols.isEmpty()) {
                int attempt = ++reconnectAttempts;
                long delaySeconds = Math.min(60, 1L << Math.min(attempt, 5));
                RECONNECT_SCHEDULER.schedule(() -> connect(currentSymbols), delaySeconds, TimeUnit.SECONDS);
            }
        }
    }

    private static String toGateSymbol(String symbol) {
        if (symbol == null || symbol.length() < 4) {
            return symbol;
        }
        return symbol.substring(0, symbol.length() - 4) + "_USDT";
    }

    private static String fromGateSymbol(String pair) {
        if (pair == null) {
            return null;
        }
        return pair.replace("_", "").toUpperCase();
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
