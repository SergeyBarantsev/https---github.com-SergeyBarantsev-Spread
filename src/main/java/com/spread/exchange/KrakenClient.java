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

public class KrakenClient extends WebSocketListener implements ExchangeClient {

    private static final String URL = "wss://ws.kraken.com/";

    private static final ScheduledExecutorService RECONNECT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor();

    private final OkHttpClient client;
    private final PriceAggregator aggregator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSocket webSocket;
    private List<String> currentSymbols;
    private int reconnectAttempts;

    public KrakenClient(OkHttpClient client, PriceAggregator aggregator) {
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
        return Exchange.KRAKEN.name();
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        if (currentSymbols == null || currentSymbols.isEmpty()) {
            return;
        }
        try {
            var root = objectMapper.createObjectNode();
            root.put("event", "subscribe");
            var pairArr = root.putArray("pair");
            for (String symbol : currentSymbols) {
                pairArr.add(toKrakenPair(symbol));
            }
            var sub = objectMapper.createObjectNode();
            sub.put("name", "ticker");
            root.set("subscription", sub);
            webSocket.send(root.toString());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        try {
            JsonNode root = objectMapper.readTree(text);
            if (!root.isArray()) {
                return;
            }
            if (root.size() < 4) {
                return;
            }
            JsonNode tickerNode = root.get(1);
            String pair = root.path(3).asText(null);
            if (pair == null || tickerNode == null || !tickerNode.isObject()) {
                return;
            }
            String symbol = fromKrakenPair(pair);
            if (symbol == null) {
                return;
            }
            double bid = parseTickerPrice(tickerNode, "b");
            double ask = parseTickerPrice(tickerNode, "a");
            long ts = System.currentTimeMillis();
            if (bid <= 0 || ask <= 0) {
                return;
            }
            Ticker ticker = new Ticker(getName(), symbol, bid, ask, ts);
            aggregator.updateTicker(ticker, Exchange.KRAKEN);
        } catch (IOException e) {
            System.err.println("KrakenClient parse error: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        onMessage(webSocket, bytes.utf8());
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        System.err.println("KrakenClient failure: " + (t != null ? t.getMessage() : "unknown"));
        synchronized (this) {
            if (this.webSocket == webSocket && currentSymbols != null && !currentSymbols.isEmpty()) {
                int attempt = ++reconnectAttempts;
                long delaySeconds = Math.min(60, 1L << Math.min(attempt, 5));
                RECONNECT_SCHEDULER.schedule(() -> connect(currentSymbols), delaySeconds, TimeUnit.SECONDS);
            }
        }
    }

    private String toKrakenPair(String symbol) {
        if (symbol == null || symbol.length() < 4) {
            return symbol;
        }
        String base = symbol.substring(0, symbol.length() - 4);
        if ("BTC".equals(base)) {
            base = "XBT";
        }
        return base + "/USDT";
    }

    private String fromKrakenPair(String pair) {
        if (pair == null) {
            return null;
        }
        String s = pair.replace("/", "").toUpperCase();
        if (s.endsWith("USDT")) {
            String base = s.substring(0, s.length() - 4);
            if ("XBT".equals(base)) {
                base = "BTC";
            }
            return base + "USDT";
        }
        return null;
    }

    private double parseTickerPrice(JsonNode ticker, String key) {
        JsonNode arr = ticker.get(key);
        if (arr == null || !arr.isArray() || arr.size() < 1) {
            return 0.0;
        }
        try {
            return Double.parseDouble(arr.get(0).asText());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
