package com.spread.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spread.app.AppLog;
import com.spread.core.config.AppPaths;
import com.spread.core.model.Settings;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Утилита для разового получения списка монет в паре к USDT с бирж Binance, Bybit, OKX, KuCoin, Gate.io, Bitget, Kraken, HTX.
 * Каждая биржа опрашивается отдельно; при таймауте или ошибке она пропускается.
 * Требуется минимум 2 успешных биржи для формирования списка (монеты, торгуемые хотя бы на двух).
 *
 * Результат пишет в assets/coins.json в формате массива строк.
 * Возвращает список символов с информацией, на каких биржах торгуется каждая монета.
 */
public class CoinListGenerator {

    /** Символ и множество бирж, на которых он торгуется. */
    public record SymbolExchanges(String symbol, Set<Settings.Exchange> exchanges) {}

    private static final int MIN_EXCHANGES_REQUIRED = 2;
    private static final int CALL_TIMEOUT_SECONDS = 45;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(CALL_TIMEOUT_SECONDS))
            .build();

    public static List<SymbolExchanges> generateAndSave() throws IOException {
        AppLog.info("Generate coin list started");
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        java.util.Map<String, Set<Settings.Exchange>> symbolToExchanges = new java.util.HashMap<>();
        int okCount = 0;

        try {
            Set<String> s = fetchBinanceUsdtSymbols();
            incrementCounts(counts, s);
            addExchanges(symbolToExchanges, s, Settings.Exchange.BINANCE);
            okCount++;
            AppLog.info("Binance OK: {} symbols", s.size());
        } catch (Exception e) {
            AppLog.warn("Binance failed: {}", e.getMessage());
        }
        try {
            Set<String> s = fetchBybitUsdtSymbols();
            incrementCounts(counts, s);
            addExchanges(symbolToExchanges, s, Settings.Exchange.BYBIT);
            okCount++;
            AppLog.info("Bybit OK: {} symbols", s.size());
        } catch (Exception e) {
            AppLog.warn("Bybit failed: {}", e.getMessage());
        }
        try {
            Set<String> s = fetchOkxUsdtSymbols();
            incrementCounts(counts, s);
            addExchanges(symbolToExchanges, s, Settings.Exchange.OKX);
            okCount++;
            AppLog.info("OKX OK: {} symbols", s.size());
        } catch (Exception e) {
            AppLog.warn("OKX failed: {}", e.getMessage());
        }
        try {
            Set<String> s = fetchKucoinUsdtSymbols();
            incrementCounts(counts, s);
            addExchanges(symbolToExchanges, s, Settings.Exchange.KUCOIN);
            okCount++;
            AppLog.info("KuCoin OK: {} symbols", s.size());
        } catch (Exception e) {
            AppLog.warn("KuCoin failed: {}", e.getMessage());
        }
        try {
            Set<String> s = fetchGateioUsdtSymbols();
            incrementCounts(counts, s);
            addExchanges(symbolToExchanges, s, Settings.Exchange.GATEIO);
            okCount++;
            AppLog.info("Gate.io OK: {} symbols", s.size());
        } catch (Exception e) {
            AppLog.warn("Gate.io failed: {}", e.getMessage());
        }
        try {
            Set<String> s = fetchBitgetUsdtSymbols();
            incrementCounts(counts, s);
            addExchanges(symbolToExchanges, s, Settings.Exchange.BITGET);
            okCount++;
            AppLog.info("Bitget OK: {} symbols", s.size());
        } catch (Exception e) {
            AppLog.warn("Bitget failed: {}", e.getMessage());
        }
        try {
            Set<String> s = fetchKrakenUsdtSymbols();
            incrementCounts(counts, s);
            addExchanges(symbolToExchanges, s, Settings.Exchange.KRAKEN);
            okCount++;
            AppLog.info("Kraken OK: {} symbols", s.size());
        } catch (Exception e) {
            AppLog.warn("Kraken failed: {}", e.getMessage());
        }
        try {
            Set<String> s = fetchHtxUsdtSymbols();
            incrementCounts(counts, s);
            addExchanges(symbolToExchanges, s, Settings.Exchange.HTX);
            okCount++;
            AppLog.info("HTX OK: {} symbols", s.size());
        } catch (Exception e) {
            AppLog.warn("HTX failed: {}", e.getMessage());
        }

        if (okCount < MIN_EXCHANGES_REQUIRED) {
            AppLog.error("Generate coin list failed: only {} exchanges responded (min {})", okCount, MIN_EXCHANGES_REQUIRED);
            throw new IOException("Удалось получить данные только с " + okCount + " бирж(и). Нужно минимум " + MIN_EXCHANGES_REQUIRED + " для формирования списка монет.");
        }

        Set<String> atLeastTwo = counts.entrySet().stream()
                .filter(e -> e.getValue() >= MIN_EXCHANGES_REQUIRED)
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());

        List<SymbolExchanges> result = atLeastTwo.stream()
                .sorted()
                .map(sym -> new SymbolExchanges(sym, symbolToExchanges.getOrDefault(sym, Set.of())))
                .collect(Collectors.toList());

        List<String> sortedSymbols = result.stream().map(SymbolExchanges::symbol).collect(Collectors.toList());
        Path assetsDir = AppPaths.assetsDir();
        Files.createDirectories(assetsDir);
        Path out = AppPaths.baseCoinsFile();
        byte[] json = MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(sortedSymbols);
        Files.write(out, json);

        AppLog.info("Generate coin list done: {} symbols from {} exchanges -> {}", result.size(), okCount, out.toAbsolutePath());
        return result;
    }

    private static void addExchanges(java.util.Map<String, Set<Settings.Exchange>> symbolToExchanges,
                                    Set<String> symbols, Settings.Exchange exchange) {
        for (String sym : symbols) {
            symbolToExchanges.computeIfAbsent(sym, k -> new TreeSet<>(java.util.Comparator.comparing(Settings.Exchange::name)))
                    .add(exchange);
        }
    }

    /**
     * Проверка, на каких биржах торгуется конкретная монета (SYMBOLUSDT).
     * Возвращает карту Exchange -> true/false.
     */
    public static java.util.Map<Settings.Exchange, Boolean> checkSymbolSupport(String symbol) throws IOException {
        String normalized = symbol == null ? "" : symbol.trim().toUpperCase();
        if (normalized.isEmpty()) {
            return java.util.Map.of();
        }

        Set<String> binance = fetchBinanceUsdtSymbols();
        Set<String> bybit = fetchBybitUsdtSymbols();
        Set<String> okx = fetchOkxUsdtSymbols();
        Set<String> kucoin = fetchKucoinUsdtSymbols();
        Set<String> gateio = fetchGateioUsdtSymbols();
        Set<String> bitget = fetchBitgetUsdtSymbols();
        Set<String> kraken = fetchKrakenUsdtSymbols();
        Set<String> htx = fetchHtxUsdtSymbols();

        java.util.EnumMap<Settings.Exchange, Boolean> result = new java.util.EnumMap<>(Settings.Exchange.class);
        result.put(Settings.Exchange.BINANCE, binance.contains(normalized));
        result.put(Settings.Exchange.BYBIT, bybit.contains(normalized));
        result.put(Settings.Exchange.OKX, okx.contains(normalized));
        result.put(Settings.Exchange.KUCOIN, kucoin.contains(normalized));
        result.put(Settings.Exchange.GATEIO, gateio.contains(normalized));
        result.put(Settings.Exchange.BITGET, bitget.contains(normalized));
        result.put(Settings.Exchange.KRAKEN, kraken.contains(normalized));
        result.put(Settings.Exchange.HTX, htx.contains(normalized));
        return result;
    }

    private static void incrementCounts(java.util.Map<String, Integer> counts, Set<String> symbols) {
        for (String s : symbols) {
            counts.merge(s, 1, Integer::sum);
        }
    }

    public static void main(String[] args) throws IOException {
        generateAndSave();
    }

    private static Set<String> fetchBinanceUsdtSymbols() throws IOException {
        String url = "https://api.binance.com/api/v3/exchangeInfo";
        JsonNode root = getJson(url);
        Set<String> result = new HashSet<>();
        for (JsonNode sym : root.path("symbols")) {
            String symbol = sym.path("symbol").asText(null);
            String status = sym.path("status").asText("");
            String quote = sym.path("quoteAsset").asText("");
            if (symbol == null) {
                continue;
            }
            if (!"TRADING".equalsIgnoreCase(status)) {
                continue;
            }
            if (!"USDT".equalsIgnoreCase(quote)) {
                continue;
            }
            result.add(symbol.toUpperCase());
        }
        return result;
    }

    private static Set<String> fetchBybitUsdtSymbols() throws IOException {
        String url = "https://api.bybit.com/v5/market/instruments-info?category=spot";
        JsonNode root = getJson(url);
        Set<String> result = new HashSet<>();
        for (JsonNode sym : root.path("result").path("list")) {
            String symbol = sym.path("symbol").asText(null);
            String quote = sym.path("quoteCoin").asText("");
            String status = sym.path("status").asText("");
            if (symbol == null) {
                continue;
            }
            if (!"USDT".equalsIgnoreCase(quote)) {
                continue;
            }
            if (!"Trading".equalsIgnoreCase(status) && !"1".equals(status)) {
                continue;
            }
            result.add(symbol.toUpperCase());
        }
        return result;
    }

    private static Set<String> fetchOkxUsdtSymbols() throws IOException {
        String url = "https://www.okx.com/api/v5/public/instruments?instType=SPOT";
        JsonNode root = getJson(url);
        Set<String> result = new HashSet<>();
        for (JsonNode sym : root.path("data")) {
            String instId = sym.path("instId").asText(null); // BTC-USDT
            String quote = sym.path("quoteCcy").asText("");
            String state = sym.path("state").asText("");
            if (instId == null) {
                continue;
            }
            if (!"USDT".equalsIgnoreCase(quote)) {
                continue;
            }
            if (!"live".equalsIgnoreCase(state)) {
                continue;
            }
            String normalized = instId.replace("-", "").toUpperCase(); // BTCUSDT
            result.add(normalized);
        }
        return result;
    }

    private static Set<String> fetchKucoinUsdtSymbols() throws IOException {
        String url = "https://api.kucoin.com/api/v2/symbols";
        JsonNode root = getJson(url);
        Set<String> result = new HashSet<>();
        for (JsonNode sym : root.path("data")) {
            String name = sym.path("symbol").asText(null); // BTC-USDT
            String quote = sym.path("quoteCurrency").asText("");
            String enableTrading = sym.path("enableTrading").asText("");
            if (name == null) {
                continue;
            }
            if (!"USDT".equalsIgnoreCase(quote)) {
                continue;
            }
            if (!"true".equalsIgnoreCase(enableTrading)) {
                continue;
            }
            String normalized = name.replace("-", "").toUpperCase();
            result.add(normalized);
        }
        return result;
    }

    private static Set<String> fetchGateioUsdtSymbols() throws IOException {
        String url = "https://api.gateio.ws/api/v4/spot/currency_pairs";
        JsonNode root = getJson(url);
        Set<String> result = new HashSet<>();
        for (JsonNode sym : root) {
            String id = sym.path("id").asText(null);
            String quote = sym.path("quote").asText("");
            String status = sym.path("trade_status").asText("");
            if (id == null) {
                continue;
            }
            if (!"USDT".equalsIgnoreCase(quote)) {
                continue;
            }
            if (!"tradable".equalsIgnoreCase(status)) {
                continue;
            }
            result.add(id.replace("_", "").toUpperCase());
        }
        return result;
    }

    private static Set<String> fetchBitgetUsdtSymbols() throws IOException {
        String url = "https://api.bitget.com/api/v2/spot/public/symbols";
        JsonNode root = getJson(url);
        Set<String> result = new HashSet<>();
        for (JsonNode sym : root.path("data")) {
            String symbol = sym.path("symbol").asText(null);
            String quote = sym.path("quoteCoin").asText("");
            String status = sym.path("status").asText("");
            if (symbol == null) {
                continue;
            }
            if (!"USDT".equalsIgnoreCase(quote)) {
                continue;
            }
            if (!"online".equalsIgnoreCase(status)) {
                continue;
            }
            result.add(symbol.toUpperCase());
        }
        return result;
    }

    private static Set<String> fetchKrakenUsdtSymbols() throws IOException {
        String url = "https://api.kraken.com/0/public/AssetPairs";
        JsonNode root = getJson(url);
        Set<String> result = new HashSet<>();
        JsonNode pairs = root.path("result");
        if (!pairs.isObject()) {
            return result;
        }
        for (java.util.Iterator<String> it = pairs.fieldNames(); it.hasNext(); ) {
            JsonNode p = pairs.get(it.next());
            String quote = p.path("quote").asText("");
            if (!"usdt".equalsIgnoreCase(quote)) {
                continue;
            }
            String base = p.path("base").asText("");
            if (base.isEmpty()) {
                continue;
            }
            String normBase = "XXBT".equals(base) || "XBT".equals(base) ? "BTC" : base.startsWith("X") ? base.substring(1) : base;
            result.add((normBase + "USDT").toUpperCase());
        }
        return result;
    }

    private static Set<String> fetchHtxUsdtSymbols() throws IOException {
        String url = "https://api.huobi.pro/v1/common/symbols";
        JsonNode root = getJson(url);
        Set<String> result = new HashSet<>();
        for (JsonNode sym : root.path("data")) {
            String base = sym.path("base-currency").asText("");
            String quote = sym.path("quote-currency").asText("");
            String state = sym.path("state").asText("");
            if (base.isEmpty()) {
                continue;
            }
            if (!"usdt".equalsIgnoreCase(quote)) {
                continue;
            }
            if (!"online".equalsIgnoreCase(state)) {
                continue;
            }
            result.add((base + "usdt").toUpperCase());
        }
        return result;
    }

    private static JsonNode getJson(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for " + url);
            }
            if (response.body() == null) {
                throw new IOException("Empty body for " + url);
            }
            String body = response.body().string();
            return MAPPER.readValue(body, new TypeReference<JsonNode>() {});
        }
    }
}

