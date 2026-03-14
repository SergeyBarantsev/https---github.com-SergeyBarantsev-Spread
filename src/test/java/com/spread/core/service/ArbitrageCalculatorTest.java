package com.spread.core.service;

import com.spread.core.model.ArbitrageOpportunity;
import com.spread.core.model.Settings;
import com.spread.core.model.Settings.Exchange;
import com.spread.core.model.Ticker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ArbitrageCalculatorTest {

    @Test
    void calculatesPositiveProfitWhenSellHigherThanBuy() {
        Settings settings = new Settings();
        settings.setDeposit(1000.0);
        settings.getFees().put(Exchange.BINANCE, new Settings.FeeConfig(0.1, 0.1));
        settings.getFees().put(Exchange.BYBIT, new Settings.FeeConfig(0.1, 0.1));

        PriceAggregator aggregator = new PriceAggregator();
        aggregator.updateTicker(new Ticker("BINANCE", "BTCUSDT", 19950.0, 20000.0, System.currentTimeMillis()), Exchange.BINANCE);
        aggregator.updateTicker(new Ticker("BYBIT", "BTCUSDT", 20500.0, 20550.0, System.currentTimeMillis()), Exchange.BYBIT);

        ArbitrageCalculator calculator = new ArbitrageCalculator();
        List<ArbitrageOpportunity> opportunities = calculator.calculate(settings, aggregator);

        assertFalse(opportunities.isEmpty(), "Expect at least one profitable opportunity");
        assertTrue(opportunities.stream().anyMatch(o -> o.getExpectedProfit() > 0));
    }
}

