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

import com.spread.core.config.AppPaths;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class KucoinClient extends WebSocketListener implements ExchangeClient {

    private static final String URL = "wss://ws-api.kucoin.com/endpoint";

    private static final ScheduledExecutorService RECONNECT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor();

    private final OkHttpClient client;
    private final PriceAggregator aggregator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSocket webSocket;
    private List<String> currentSymbols;
    private int reconnectAttempts;

    public KucoinClient(OkHttpClient client, PriceAggregator aggregator) {
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
        return Exchange.KUCOIN.name();
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        if (currentSymbols == null || currentSymbols.isEmpty()) {
            return;
        }
        try {
            // KuCoin topic: /market/ticker:BTC-USDT
            for (String symbol : currentSymbols) {
                String kuSymbol = toDashSymbol(symbol);
                var sub = objectMapper.createObjectNode();
                sub.put("id", System.currentTimeMillis());
                sub.put("type", "subscribe");
                sub.put("topic", "/market/ticker:" + kuSymbol);
                sub.put("response", true);
                webSocket.send(sub.toString());
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        try {
            JsonNode root = objectMapper.readTree(text);
            if (!"/market/ticker".equals(root.path("topic").asText().split(":")[0])) {
                return;
            }
            JsonNode data = root.get("data");
            if (data == null) {
                return;
            }
            String topic = root.path("topic").asText();
            String[] parts = topic.split(":");
            if (parts.length < 2) {
                return;
            }
            String instId = parts[1];
            String symbol = fromDashSymbol(instId);
            double bid = parseDouble(data, "bestBid");
            double ask = parseDouble(data, "bestAsk");
            long ts = data.has("time") ? data.get("time").asLong() : System.currentTimeMillis();
            if (symbol == null || bid <= 0 || ask <= 0) {
                return;
            }
            Ticker ticker = new Ticker(getName(), symbol, bid, ask, ts);
            aggregator.updateTicker(ticker, Exchange.KUCOIN);
        } catch (IOException e) {
            System.err.println("KucoinClient parse error: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        onMessage(webSocket, bytes.utf8());
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        logFailureToFile(t);
        String msg = toFailureMessage(t);
        System.err.println("KucoinClient failure: " + msg + " (details in log/kucoin-error.log)");
        synchronized (this) {
            if (this.webSocket == webSocket && currentSymbols != null && !currentSymbols.isEmpty()) {
                int attempt = ++reconnectAttempts;
                long delaySeconds = Math.min(60, 1L << Math.min(attempt, 5));
                RECONNECT_SCHEDULER.schedule(() -> connect(currentSymbols), delaySeconds, TimeUnit.SECONDS);
            }
        }
    }

    /** Пишет полный текст ошибки в UTF-8 файл, чтобы прочитать сообщение в любой кодировке (в т.ч. кириллицу). */
    private static void logFailureToFile(Throwable t) {
        if (t == null) {
            return;
        }
        try {
            Path logDir = AppPaths.logDir();
            Files.createDirectories(logDir);
            Path logFile = AppPaths.kucoinErrorLogFile();
            StringWriter sw = new StringWriter();
            sw.append("--- ").append(Instant.now().toString()).append(" ---\n");
            sw.append("Message: ").append(t.getMessage() != null ? t.getMessage() : "(null)").append("\n");
            t.printStackTrace(new PrintWriter(sw));
            sw.append("\n");
            Files.write(logFile, sw.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("Could not write KucoinClient error to log: " + e.getMessage());
        }
    }

    /** Сообщение для консоли без кириллицы, чтобы не ломаться в другой кодировке. */
    private static String toFailureMessage(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String type = t.getClass().getSimpleName();
        String m = t.getMessage();
        if (m == null || m.isBlank()) {
            return type;
        }
        if (m.codePoints().allMatch(c -> c < 128)) {
            return m;
        }
        return type + " (check encoding)";
    }

    private String toDashSymbol(String s) {
        if (s == null || s.length() < 7) {
            return s;
        }
        return s.substring(0, s.length() - 4) + "-" + s.substring(s.length() - 4);
    }

    private String fromDashSymbol(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("-", "");
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

