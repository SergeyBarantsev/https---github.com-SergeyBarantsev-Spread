package com.spread.core.model;

public class Ticker {

    private final String exchange;
    private final String symbol;
    private final double bestBid;
    private final double bestAsk;
    private final long timestamp;

    public Ticker(String exchange, String symbol, double bestBid, double bestAsk, long timestamp) {
        this.exchange = exchange;
        this.symbol = symbol;
        this.bestBid = bestBid;
        this.bestAsk = bestAsk;
        this.timestamp = timestamp;
    }

    public String getExchange() {
        return exchange;
    }

    public String getSymbol() {
        return symbol;
    }

    public double getBestBid() {
        return bestBid;
    }

    public double getBestAsk() {
        return bestAsk;
    }

    public long getTimestamp() {
        return timestamp;
    }
}

