package com.spread.exchange;

import com.spread.core.model.Settings.Exchange;
import com.spread.core.service.PriceAggregator;
import okhttp3.OkHttpClient;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class ExchangeManager {

    private final OkHttpClient httpClient = new OkHttpClient();
    private final PriceAggregator aggregator;
    private final Map<Exchange, ExchangeClient> clients = new EnumMap<>(Exchange.class);

    public ExchangeManager(PriceAggregator aggregator) {
        this.aggregator = aggregator;
        clients.put(Exchange.BINANCE, new BinanceClient(httpClient, aggregator));
        clients.put(Exchange.BYBIT, new BybitClient(httpClient, aggregator));
        clients.put(Exchange.OKX, new OkxClient(httpClient, aggregator));
        clients.put(Exchange.KUCOIN, new KucoinClient(httpClient, aggregator));
        clients.put(Exchange.GATEIO, new GateioClient(httpClient, aggregator));
        clients.put(Exchange.BITGET, new BitgetClient(httpClient, aggregator));
        clients.put(Exchange.KRAKEN, new KrakenClient(httpClient, aggregator));
        clients.put(Exchange.HTX, new HtxClient(httpClient, aggregator));
        clients.put(Exchange.MEXC, new MexcClient(httpClient, aggregator));
        clients.put(Exchange.BINGX, new BingxClient(httpClient, aggregator));
        clients.put(Exchange.LBANK, new LbankClient(httpClient, aggregator));
        clients.put(Exchange.COINEX, new CoinexClient(httpClient, aggregator));
    }

    public void connectAll(List<String> symbols) {
        List<String> safeSymbols = symbols != null ? new ArrayList<>(symbols) : List.of();
        for (ExchangeClient client : clients.values()) {
            client.connect(safeSymbols);
        }
    }

    public void disconnectAll() {
        aggregator.clear();
        for (ExchangeClient client : clients.values()) {
            client.disconnect();
        }
    }
}

