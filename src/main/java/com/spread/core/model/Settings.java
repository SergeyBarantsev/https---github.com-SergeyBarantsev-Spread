package com.spread.core.model;

import java.util.EnumMap;
import java.util.Map;

public class Settings {

    public enum Exchange {
        BINANCE,
        BYBIT,
        OKX,
        KUCOIN,
        GATEIO,
        BITGET,
        KRAKEN,
        HTX
    }

    public static class FeeConfig {
        private double buyFeePercent;
        private double sellFeePercent;

        public FeeConfig() {
        }

        public FeeConfig(double buyFeePercent, double sellFeePercent) {
            this.buyFeePercent = buyFeePercent;
            this.sellFeePercent = sellFeePercent;
        }

        public double getBuyFeePercent() {
            return buyFeePercent;
        }

        public void setBuyFeePercent(double buyFeePercent) {
            this.buyFeePercent = buyFeePercent;
        }

        public double getSellFeePercent() {
            return sellFeePercent;
        }

        public void setSellFeePercent(double sellFeePercent) {
            this.sellFeePercent = sellFeePercent;
        }
    }

    private double deposit;
    private final Map<Exchange, FeeConfig> fees = new EnumMap<>(Exchange.class);

    public Settings() {
    }

    public double getDeposit() {
        return deposit;
    }

    public void setDeposit(double deposit) {
        this.deposit = deposit;
    }

    public Map<Exchange, FeeConfig> getFees() {
        return fees;
    }
}

