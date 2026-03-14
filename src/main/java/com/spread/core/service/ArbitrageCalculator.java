package com.spread.core.service;

import com.spread.core.model.ArbitrageOpportunity;
import com.spread.core.model.Settings;
import com.spread.core.model.Settings.Exchange;
import com.spread.core.model.Ticker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class ArbitrageCalculator {

    public List<ArbitrageOpportunity> calculate(Settings settings,
                                                PriceAggregator aggregator) {
        List<ArbitrageOpportunity> result = new ArrayList<>();
        double deposit = settings.getDeposit();
        if (deposit <= 0) {
            return result;
        }

        Map<String, Map<Exchange, Ticker>> all = aggregator.getAll();
        for (Map.Entry<String, Map<Exchange, Ticker>> entry : all.entrySet()) {
            String symbol = entry.getKey();
            Map<Exchange, Ticker> perExchange = entry.getValue();

            for (Exchange buyEx : perExchange.keySet()) {
                for (Exchange sellEx : perExchange.keySet()) {
                    if (buyEx == sellEx) {
                        continue;
                    }
                    Ticker buyTicker = perExchange.get(buyEx);
                    Ticker sellTicker = perExchange.get(sellEx);
                    if (buyTicker == null || sellTicker == null) {
                        continue;
                    }

                    double buyPrice = buyTicker.getBestAsk();
                    double sellPrice = sellTicker.getBestBid();
                    if (buyPrice <= 0 || sellPrice <= 0) {
                        continue;
                    }

                    Settings.FeeConfig buyFee = settings.getFees().getOrDefault(buyEx, new Settings.FeeConfig());
                    Settings.FeeConfig sellFee = settings.getFees().getOrDefault(sellEx, new Settings.FeeConfig());

                    double buyPriceEff = buyPrice * (1 + buyFee.getBuyFeePercent() / 100.0);
                    double sellPriceEff = sellPrice * (1 - sellFee.getSellFeePercent() / 100.0);

                    double qty = deposit / buyPriceEff;
                    double revenue = qty * sellPriceEff;
                    double profit = revenue - deposit;

                    if (profit <= 0) {
                        continue;
                    }

                    double spreadPercent = (sellPrice - buyPrice) / buyPrice * 100.0;

                    result.add(new ArbitrageOpportunity(
                            symbol,
                            buyEx.name(),
                            buyPrice,
                            sellEx.name(),
                            sellPrice,
                            spreadPercent,
                            profit
                    ));
                }
            }
        }

        result.sort(Comparator.comparingDouble(ArbitrageOpportunity::getExpectedProfit).reversed());
        return result;
    }
}

