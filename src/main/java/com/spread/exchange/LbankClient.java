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
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LbankClient extends WebSocketListener implements ExchangeClient {

    private static final String URL = "wss://www.lbkex.net/ws/V2/";

    private static final ScheduledExecutorService RECONNECT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor();

    private final OkHttpClient client;
    private final PriceAggregator aggregator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSocket webSocket;
    private List<String> currentSymbols;
    private int reconnectAttempts;

    public LbankClient(OkHttpClient client, PriceAggregator aggregator) {
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
        return Exchange.LBANK.name();
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        if (currentSymbols == null || currentSymbols.isEmpty()) {
            return;
        }
        try {
            for (String symbol : currentSymbols) {
                String pair = toLbankPair(symbol);
                var root = objectMapper.createObjectNode();
                root.put("action", "subscribe");
                root.put("subscribe", "depth");
                root.put("depth", "5");
                root.put("pair", pair);
                webSocket.send(root.toString());
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        try {
            JsonNode root = objectMapper.readTree(text);
            JsonNode depth = root.get("depth");
            JsonNode pairNode = root.get("pair");
            if (depth == null || pairNode == null) {
                return;
            }
            String symbol = fromLbankPair(pairNode.asText(null));
            if (symbol == null) {
                return;
            }
            JsonNode bids = depth.get("bids");
            JsonNode asks = depth.get("asks");
            if (bids == null || !bids.isArray() || bids.size() < 1 || asks == null || !asks.isArray() || asks.size() < 1) {
                return;
            }
            double bid = parsePriceLevel(bids.get(0));
            double ask = parsePriceLevel(asks.get(0));
            long ts = root.has("timestamp") ? root.get("timestamp").asLong() : System.currentTimeMillis();
            if (bid <= 0 || ask <= 0) {
                return;
            }
            Ticker ticker = new Ticker(getName(), symbol, bid, ask, ts);
            aggregator.updateTicker(ticker, Exchange.LBANK);
        } catch (IOException e) {
            System.err.println("LbankClient parse error: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        onMessage(webSocket, bytes.utf8());
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        System.err.println("LbankClient failure: " + (t != null ? t.getMessage() : "unknown"));
        synchronized (this) {
            if (this.webSocket == webSocket && currentSymbols != null && !currentSymbols.isEmpty()) {
                int attempt = ++reconnectAttempts;
                long delaySeconds = Math.min(60, 1L << Math.min(attempt, 5));
                RECONNECT_SCHEDULER.schedule(() -> connect(currentSymbols), delaySeconds, TimeUnit.SECONDS);
            }
        }
    }

    private static String toLbankPair(String symbol) {
        if (symbol == null || symbol.length() < 4) {
            return symbol;
        }
        return (symbol.substring(0, symbol.length() - 4) + "_usdt").toLowerCase(Locale.ROOT);
    }

    private static String fromLbankPair(String pair) {
        if (pair == null) {
            return null;
        }
        return pair.replace("_", "").toUpperCase(Locale.ROOT);
    }

    private static double parsePriceLevel(JsonNode level) {
        if (level == null || !level.isArray() || level.size() < 1) {
            return 0.0;
        }
        try {
            return Double.parseDouble(level.get(0).asText());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
