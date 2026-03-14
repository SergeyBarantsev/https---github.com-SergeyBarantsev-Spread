package com.spread.core.model;

public class ArbitrageOpportunity {

    private final String symbol;
    private final String buyExchange;
    private final double buyPrice;
    private final String sellExchange;
    private final double sellPrice;
    private final double spreadPercent;
    private final double expectedProfit;

    public ArbitrageOpportunity(String symbol,
                                String buyExchange,
                                double buyPrice,
                                String sellExchange,
                                double sellPrice,
                                double spreadPercent,
                                double expectedProfit) {
        this.symbol = symbol;
        this.buyExchange = buyExchange;
        this.buyPrice = buyPrice;
        this.sellExchange = sellExchange;
        this.sellPrice = sellPrice;
        this.spreadPercent = spreadPercent;
        this.expectedProfit = expectedProfit;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getBuyExchange() {
        return buyExchange;
    }

    public double getBuyPrice() {
        return buyPrice;
    }

    public String getSellExchange() {
        return sellExchange;
    }

    public double getSellPrice() {
        return sellPrice;
    }

    public double getSpreadPercent() {
        return spreadPercent;
    }

    public double getExpectedProfit() {
        return expectedProfit;
    }
}

