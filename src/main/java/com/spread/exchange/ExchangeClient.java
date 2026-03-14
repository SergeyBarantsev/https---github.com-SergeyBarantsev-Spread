package com.spread.exchange;

import java.util.List;

public interface ExchangeClient {

    void connect(List<String> symbols);

    void disconnect();

    String getName();
}

