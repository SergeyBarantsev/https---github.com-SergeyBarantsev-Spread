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

public class BingxClient extends WebSocketListener implements ExchangeClient {

    /** Spot WebSocket (документация: open-api.bingx.com, путь для spot stream). */
    private static final String URL = "wss://open-api.bingx.com/openApi/spot";

    private static final ScheduledExecutorService RECONNECT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor();

    private final OkHttpClient client;
    private final PriceAggregator aggregator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSocket webSocket;
    private List<String> currentSymbols;
    private int reconnectAttempts;

    public BingxClient(OkHttpClient client, PriceAggregator aggregator) {
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
        return Exchange.BINGX.name();
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        if (currentSymbols == null || currentSymbols.isEmpty()) {
            return;
        }
        try {
            for (String symbol : currentSymbols) {
                String bingxSymbol = toBingxSymbol(symbol);
                var root = objectMapper.createObjectNode();
                root.put("id", "ticker-" + symbol);
                root.put("reqType", "sub");
                root.put("dataType", "spot@ticker");
                root.put("symbol", bingxSymbol);
                webSocket.send(root.toString());
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        try {
            JsonNode root = objectMapper.readTree(text);
            if (root.has("dataType") && "spot@ticker".equals(root.path("dataType").asText(null))) {
                JsonNode data = root.get("data");
                if (data == null) {
                    return;
                }
                String symbol = fromBingxSymbol(data.path("symbol").asText(null));
                if (symbol == null) {
                    return;
                }
                double bid = parseDouble(data, "bidPrice");
                double ask = parseDouble(data, "askPrice");
                long ts = data.has("time") ? data.get("time").asLong() : System.currentTimeMillis();
                if (bid <= 0 || ask <= 0) {
                    return;
                }
                Ticker ticker = new Ticker(getName(), symbol, bid, ask, ts);
                aggregator.updateTicker(ticker, Exchange.BINGX);
            }
        } catch (IOException e) {
            System.err.println("BingxClient parse error: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        onMessage(webSocket, bytes.utf8());
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        String msg = t != null ? t.getMessage() : "unknown";
        if (msg == null && t != null) {
            msg = t.getClass().getSimpleName();
        }
        System.err.println("BingxClient failure: " + msg);
        synchronized (this) {
            if (this.webSocket == webSocket && currentSymbols != null && !currentSymbols.isEmpty()) {
                int attempt = ++reconnectAttempts;
                long delaySeconds = Math.min(60, 1L << Math.min(attempt, 5));
                RECONNECT_SCHEDULER.schedule(() -> connect(currentSymbols), delaySeconds, TimeUnit.SECONDS);
            }
        }
    }

    private static String toBingxSymbol(String symbol) {
        if (symbol == null || symbol.length() < 4) {
            return symbol;
        }
        return symbol.substring(0, symbol.length() - 4) + "-USDT";
    }

    private static String fromBingxSymbol(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("-", "").toUpperCase();
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
