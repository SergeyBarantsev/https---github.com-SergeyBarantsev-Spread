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
import java.util.stream.Collectors;

/**
 * Утилита для разового получения списка монет в паре к USDT с бирж Binance, Bybit, OKX, KuCoin.
 * Каждая биржа опрашивается отдельно; при таймауте или ошибке она пропускается.
 * Требуется минимум 2 успешных биржи для формирования списка (монеты, торгуемые хотя бы на двух).
 *
 * Результат пишет в assets/coins.json в формате массива строк, например:
 * ["BTCUSDT","ETHUSDT", ...]
 */
public class CoinListGenerator {

    private static final int MIN_EXCHANGES_REQUIRED = 2;
    private static final int CALL_TIMEOUT_SECONDS = 45;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(CALL_TIMEOUT_SECONDS))
            .build();

    public static List<String> generateAndSave() throws IOException {
        AppLog.info("Generate coin list started");
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        int okCount = 0;

        try {
            Set<String> s = fetchBinanceUsdtSymbols();
            incrementCounts(counts, s);
            okCount++;
            AppLog.info("Binance OK: {} symbols", s.size());
        } catch (Exception e) {
            AppLog.warn("Binance failed: {}", e.getMessage());
        }
        try {
            Set<String> s = fetchBybitUsdtSymbols();
            incrementCounts(counts, s);
            okCount++;
            AppLog.info("Bybit OK: {} symbols", s.size());
        } catch (Exception e) {
            AppLog.warn("Bybit failed: {}", e.getMessage());
        }
        try {
            Set<String> s = fetchOkxUsdtSymbols();
            incrementCounts(counts, s);
            okCount++;
            AppLog.info("OKX OK: {} symbols", s.size());
        } catch (Exception e) {
            AppLog.warn("OKX failed: {}", e.getMessage());
        }
        try {
            Set<String> s = fetchKucoinUsdtSymbols();
            incrementCounts(counts, s);
            okCount++;
            AppLog.info("KuCoin OK: {} symbols", s.size());
        } catch (Exception e) {
            AppLog.warn("KuCoin failed: {}", e.getMessage());
        }

        if (okCount < MIN_EXCHANGES_REQUIRED) {
            AppLog.error("Generate coin list failed: only {} exchanges responded (min {})", okCount, MIN_EXCHANGES_REQUIRED);
            throw new IOException("Удалось получить данные только с " + okCount + " бирж(и). Нужно минимум " + MIN_EXCHANGES_REQUIRED + " для формирования списка монет.");
        }

        Set<String> atLeastTwo = counts.entrySet().stream()
                .filter(e -> e.getValue() >= MIN_EXCHANGES_REQUIRED)
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());

        List<String> sorted = atLeastTwo.stream()
                .sorted()
                .collect(Collectors.toList());

        Path assetsDir = AppPaths.assetsDir();
        Files.createDirectories(assetsDir);
        Path out = AppPaths.baseCoinsFile();
        byte[] json = MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(sorted);
        Files.write(out, json);

        AppLog.info("Generate coin list done: {} symbols from {} exchanges -> {}", sorted.size(), okCount, out.toAbsolutePath());
        return sorted;
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

        java.util.EnumMap<Settings.Exchange, Boolean> result = new java.util.EnumMap<>(Settings.Exchange.class);
        result.put(Settings.Exchange.BINANCE, binance.contains(normalized));
        result.put(Settings.Exchange.BYBIT, bybit.contains(normalized));
        result.put(Settings.Exchange.OKX, okx.contains(normalized));
        result.put(Settings.Exchange.KUCOIN, kucoin.contains(normalized));
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

