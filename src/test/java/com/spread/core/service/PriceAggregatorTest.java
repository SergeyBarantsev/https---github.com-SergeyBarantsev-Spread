package com.spread.core.service;

import com.spread.core.model.Settings.Exchange;
import com.spread.core.model.Ticker;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class PriceAggregatorTest {

    @Test
    void storesAndReturnsTickersBySymbolAndExchange() {
        PriceAggregator aggregator = new PriceAggregator();
        Ticker ticker = new Ticker("BINANCE", "ETHUSDT", 1500.0, 1501.0, System.currentTimeMillis());
        aggregator.updateTicker(ticker, Exchange.BINANCE);

        Map<Exchange, Ticker> map = aggregator.getTickersForSymbol("ETHUSDT");
        assertNotNull(map);
        assertEquals(1, map.size());
        assertEquals(1500.0, map.get(Exchange.BINANCE).getBestBid(), 1e-9);
    }
}

