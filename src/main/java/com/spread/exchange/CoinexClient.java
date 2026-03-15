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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public class CoinexClient extends WebSocketListener implements ExchangeClient {

    private static final String URL = "wss://socket.coinex.com/v2/spot";

    private static final ScheduledExecutorService RECONNECT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor();

    private final OkHttpClient client;
    private final PriceAggregator aggregator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSocket webSocket;
    private List<String> currentSymbols;
    private int reconnectAttempts;

    public CoinexClient(OkHttpClient client, PriceAggregator aggregator) {
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
        return Exchange.COINEX.name();
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        if (currentSymbols == null || currentSymbols.isEmpty()) {
            return;
        }
        try {
            var root = objectMapper.createObjectNode();
            root.put("method", "depth.subscribe");
            var params = objectMapper.createObjectNode();
            var marketList = params.putArray("market_list");
            for (String symbol : currentSymbols) {
                marketList.add(symbol);
            }
            params.put("limit", 1);
            root.set("params", params);
            root.put("id", System.currentTimeMillis());
            webSocket.send(root.toString());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        try {
            JsonNode root = objectMapper.readTree(text);
            String method = root.path("method").asText(null);
            if (!"depth.update".equals(method)) {
                return;
            }
            JsonNode data = root.get("data");
            if (data == null) {
                return;
            }
            String market = data.path("market").asText(null);
            if (market == null) {
                return;
            }
            JsonNode bids = data.get("bids");
            JsonNode asks = data.get("asks");
            if (bids == null || !bids.isArray() || bids.isEmpty() || asks == null || !asks.isArray() || asks.isEmpty()) {
                return;
            }
            double bid = parsePriceLevel(bids.get(0));
            double ask = parsePriceLevel(asks.get(0));
            long ts = root.has("params") && root.get("params").has(1) ? root.get("params").get(1).asLong() : System.currentTimeMillis();
            if (bid <= 0 || ask <= 0) {
                return;
            }
            Ticker ticker = new Ticker(getName(), market, bid, ask, ts);
            aggregator.updateTicker(ticker, Exchange.COINEX);
        } catch (IOException e) {
            System.err.println("CoinexClient parse error: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        String text = decompressCoinEx(bytes);
        if (text != null) {
            onMessage(webSocket, text);
        }
    }

    /** CoinEx присылает сообщения в gzip/deflate или иногда plain JSON. */
    private String decompressCoinEx(ByteString bytes) {
        if (bytes == null || bytes.size() == 0) {
            return null;
        }
        byte[] arr = bytes.toByteArray();
        if (arr[0] == '{') {
            return bytes.utf8();
        }
        if (arr.length < 2) {
            return bytes.utf8();
        }
        try {
            if (arr[0] == (byte) 0x1f && arr[1] == (byte) 0x8b) {
                try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(arr));
                     Reader r = new InputStreamReader(gzip, StandardCharsets.UTF_8)) {
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[4096];
                    int n;
                    while ((n = r.read(buf)) > 0) {
                        sb.append(buf, 0, n);
                    }
                    return sb.toString();
                }
            }
            try (InflaterInputStream inf = new InflaterInputStream(new ByteArrayInputStream(arr), new Inflater(true));
                 Reader r = new InputStreamReader(inf, StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return bytes.utf8();
        }
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        System.err.println("CoinexClient failure: " + (t != null ? t.getMessage() : "unknown"));
        synchronized (this) {
            if (this.webSocket == webSocket && currentSymbols != null && !currentSymbols.isEmpty()) {
                int attempt = ++reconnectAttempts;
                long delaySeconds = Math.min(60, 1L << Math.min(attempt, 5));
                RECONNECT_SCHEDULER.schedule(() -> connect(currentSymbols), delaySeconds, TimeUnit.SECONDS);
            }
        }
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
