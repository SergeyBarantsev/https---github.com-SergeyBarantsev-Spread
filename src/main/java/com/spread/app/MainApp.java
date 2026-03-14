package com.spread.app;

import com.spread.core.model.ArbitrageOpportunity;
import com.spread.core.model.Settings;
import com.spread.core.model.Settings.Exchange;
import com.spread.core.service.ArbitrageCalculator;
import com.spread.core.service.PriceAggregator;
import com.spread.core.storage.CoinStorage;
import com.spread.core.storage.SettingsStorage;
import com.spread.exchange.ExchangeManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainApp extends Application {

    private final ObservableList<TrackedCoin> trackedCoins = FXCollections.observableArrayList();
    private final ObservableList<ArbitrageRow> arbitrageRows = FXCollections.observableArrayList();

    private final Settings settings = new Settings();
    private final PriceAggregator priceAggregator = new PriceAggregator();
    private final ArbitrageCalculator arbitrageCalculator = new ArbitrageCalculator();
    private final ExchangeManager exchangeManager = new ExchangeManager(priceAggregator);
    private final CoinStorage coinStorage = new CoinStorage(
            Path.of("assets", "coins.json"),
            Path.of("config", "user-coins.json")
    );
    private final SettingsStorage settingsStorage = new SettingsStorage(
            Path.of("config", "settings.json")
    );

    private TextField depositFieldRef;
    private final Map<Exchange, TextField> buyFeeFields = new java.util.EnumMap<>(Exchange.class);
    private final Map<Exchange, TextField> sellFeeFields = new java.util.EnumMap<>(Exchange.class);

    private ScheduledExecutorService scheduler;

    @Override
    public void start(Stage primaryStage) {
        BorderPane root = new BorderPane();

        loadSettings();

        VBox topPanel = createSettingsPanel();
        VBox middlePanel = createCoinsPanel();
        VBox bottomPanel = createArbitragePanel();

        loadCoins();

        root.setTop(topPanel);
        root.setCenter(middlePanel);
        root.setBottom(bottomPanel);

        Scene scene = new Scene(root, 1200, 700);
        primaryStage.setTitle("Crypto Spread Monitor");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        exchangeManager.disconnectAll();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private VBox createSettingsPanel() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(10));

        HBox depositBox = new HBox(8);
        depositBox.setAlignment(Pos.CENTER_LEFT);
        Label depositLabel = new Label("Депозит (USDT):");
        TextField depositField = new TextField();
        depositField.setPromptText("Например, 1000");
        if (settings.getDeposit() > 0) {
            depositField.setText(Double.toString(settings.getDeposit()));
        }
        depositFieldRef = depositField;
        depositBox.getChildren().addAll(depositLabel, depositField);

        HBox feesHeader = new HBox(8);
        feesHeader.setAlignment(Pos.CENTER_LEFT);
        feesHeader.getChildren().add(new Label("Комиссии бирж (% покупка / продажа):"));

        HBox binanceBox = createExchangeFeeRow("Binance", Exchange.BINANCE);
        HBox bybitBox = createExchangeFeeRow("Bybit", Exchange.BYBIT);
        HBox okxBox = createExchangeFeeRow("OKX", Exchange.OKX);
        HBox kucoinBox = createExchangeFeeRow("KuCoin", Exchange.KUCOIN);

        HBox buttonsBox = new HBox(10);
        buttonsBox.setAlignment(Pos.CENTER_LEFT);
        Button startButton = new Button("Start / Connect");
        Button stopButton = new Button("Stop / Disconnect");
        buttonsBox.getChildren().addAll(startButton, stopButton);

        startButton.setOnAction(e -> {
            try {
                double deposit = Double.parseDouble(depositField.getText());
                settings.setDeposit(deposit);
            } catch (NumberFormatException ex) {
                settings.setDeposit(0.0);
            }
            if (settings.getDeposit() <= 0) {
                depositField.setStyle("-fx-border-color: red;");
                return;
            } else {
                depositField.setStyle(null);
            }
            List<String> symbols = trackedCoins.stream()
                    .filter(TrackedCoin::isEnabled)
                    .map(TrackedCoin::getSymbol)
                    .toList();
            exchangeManager.connectAll(symbols);
            settingsStorage.save(settings);
            startScheduler();
        });

        stopButton.setOnAction(e -> {
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
            exchangeManager.disconnectAll();
        });

        box.getChildren().addAll(depositBox, feesHeader, binanceBox, bybitBox, okxBox, kucoinBox, buttonsBox);
        return box;
    }

    private HBox createExchangeFeeRow(String exchangeName, Exchange exchange) {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);
        Label nameLabel = new Label(exchangeName + ":");
        TextField buyFeeField = new TextField();
        buyFeeField.setPromptText("buy %");
        buyFeeField.setPrefWidth(70);
        TextField sellFeeField = new TextField();
        sellFeeField.setPromptText("sell %");
        sellFeeField.setPrefWidth(70);
        box.getChildren().addAll(nameLabel, buyFeeField, sellFeeField);

        buyFeeFields.put(exchange, buyFeeField);
        sellFeeFields.put(exchange, sellFeeField);

        Settings.FeeConfig existing = settings.getFees().get(exchange);
        if (existing != null) {
            if (existing.getBuyFeePercent() != 0.0) {
                buyFeeField.setText(Double.toString(existing.getBuyFeePercent()));
            }
            if (existing.getSellFeePercent() != 0.0) {
                sellFeeField.setText(Double.toString(existing.getSellFeePercent()));
            }
        }

        buyFeeField.textProperty().addListener((obs, oldVal, newVal) -> {
            double fee = parsePercent(newVal);
            settings.getFees()
                    .computeIfAbsent(exchange, ex -> new Settings.FeeConfig())
                    .setBuyFeePercent(fee);
        });

        sellFeeField.textProperty().addListener((obs, oldVal, newVal) -> {
            double fee = parsePercent(newVal);
            settings.getFees()
                    .computeIfAbsent(exchange, ex -> new Settings.FeeConfig())
                    .setSellFeePercent(fee);
        });

        return box;
    }

    private double parsePercent(String text) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private VBox createCoinsPanel() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(10));

        Label title = new Label("Список монет для отслеживания");

        TableView<TrackedCoin> table = new TableView<>(trackedCoins);
        table.setPrefHeight(250);

        TableColumn<TrackedCoin, String> symbolCol = new TableColumn<>("Монета");
        symbolCol.setCellValueFactory(new PropertyValueFactory<>("symbol"));
        symbolCol.setPrefWidth(150);

        TableColumn<TrackedCoin, Boolean> enabledCol = new TableColumn<>("Включена");
        enabledCol.setCellValueFactory(param -> param.getValue().enabledProperty());
        enabledCol.setCellFactory(CheckBoxTableCell.forTableColumn(enabledCol));
        enabledCol.setPrefWidth(100);

        table.getColumns().addAll(symbolCol, enabledCol);

        HBox addBox = new HBox(8);
        addBox.setAlignment(Pos.CENTER_LEFT);
        TextField newCoinField = new TextField();
        HBox.setHgrow(newCoinField, Priority.ALWAYS);
        newCoinField.setPromptText("Например, BTCUSDT");
        Button addButton = new Button("Добавить");
        addButton.setOnAction(e -> {
            String symbol = newCoinField.getText();
            if (symbol != null && !symbol.isBlank()) {
                String normalized = symbol.trim().toUpperCase();
                trackedCoins.add(new TrackedCoin(normalized, true));
                coinStorage.addUserCoin(normalized);
                newCoinField.clear();
            }
        });
        addBox.getChildren().addAll(newCoinField, addButton);

        box.getChildren().addAll(title, table, addBox);
        return box;
    }

    private VBox createArbitragePanel() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(10));

        Label title = new Label("Наиболее выгодные арбитражные возможности");

        TableView<ArbitrageRow> table = new TableView<>(arbitrageRows);
        table.setPrefHeight(260);

        TableColumn<ArbitrageRow, String> symbolCol = new TableColumn<>("Монета");
        symbolCol.setCellValueFactory(new PropertyValueFactory<>("symbol"));

        TableColumn<ArbitrageRow, String> buyExchangeCol = new TableColumn<>("Биржа покупки");
        buyExchangeCol.setCellValueFactory(new PropertyValueFactory<>("buyExchange"));

        TableColumn<ArbitrageRow, Double> buyPriceCol = new TableColumn<>("Цена покупки");
        buyPriceCol.setCellValueFactory(new PropertyValueFactory<>("buyPrice"));

        TableColumn<ArbitrageRow, String> sellExchangeCol = new TableColumn<>("Биржа продажи");
        sellExchangeCol.setCellValueFactory(new PropertyValueFactory<>("sellExchange"));

        TableColumn<ArbitrageRow, Double> sellPriceCol = new TableColumn<>("Цена продажи");
        sellPriceCol.setCellValueFactory(new PropertyValueFactory<>("sellPrice"));

        TableColumn<ArbitrageRow, Double> spreadCol = new TableColumn<>("Спред %");
        spreadCol.setCellValueFactory(new PropertyValueFactory<>("spreadPercent"));

        TableColumn<ArbitrageRow, Double> profitCol = new TableColumn<>("Ожидаемая прибыль");
        profitCol.setCellValueFactory(new PropertyValueFactory<>("expectedProfit"));

        table.getColumns().addAll(symbolCol, buyExchangeCol, buyPriceCol, sellExchangeCol, sellPriceCol, spreadCol, profitCol);

        box.getChildren().addAll(title, table);
        return box;
    }

    private void startScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            var opportunities = arbitrageCalculator.calculate(settings, priceAggregator);
            Platform.runLater(() -> {
                arbitrageRows.clear();
                for (ArbitrageOpportunity opp : opportunities) {
                    arbitrageRows.add(new ArbitrageRow(
                            opp.getSymbol(),
                            opp.getBuyExchange(),
                            opp.getBuyPrice(),
                            opp.getSellExchange(),
                            opp.getSellPrice(),
                            opp.getSpreadPercent(),
                            opp.getExpectedProfit()
                    ));
                }
            });
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void loadCoins() {
        List<String> symbols = coinStorage.loadMergedCoins();
        if (symbols.isEmpty()) {
            // default popular coins if no files yet
            symbols = List.of("BTCUSDT", "ETHUSDT", "BNBUSDT");
        }
        for (String s : symbols) {
            trackedCoins.add(new TrackedCoin(s, true));
        }
    }

    private void loadSettings() {
        Settings loaded = settingsStorage.load();
        settings.setDeposit(loaded.getDeposit());
        settings.getFees().clear();
        settings.getFees().putAll(loaded.getFees());
    }

    public static class TrackedCoin {
        private final SimpleStringProperty symbol;
        private final javafx.beans.property.SimpleBooleanProperty enabled;

        public TrackedCoin(String symbol, boolean enabled) {
            this.symbol = new SimpleStringProperty(symbol);
            this.enabled = new javafx.beans.property.SimpleBooleanProperty(enabled);
        }

        public String getSymbol() {
            return symbol.get();
        }

        public void setSymbol(String value) {
            symbol.set(value);
        }

        public javafx.beans.property.BooleanProperty enabledProperty() {
            return enabled;
        }

        public boolean isEnabled() {
            return enabled.get();
        }

        public void setEnabled(boolean value) {
            enabled.set(value);
        }
    }

    public static class ArbitrageRow {
        private final SimpleStringProperty symbol;
        private final SimpleStringProperty buyExchange;
        private final SimpleDoubleProperty buyPrice;
        private final SimpleStringProperty sellExchange;
        private final SimpleDoubleProperty sellPrice;
        private final SimpleDoubleProperty spreadPercent;
        private final SimpleDoubleProperty expectedProfit;

        public ArbitrageRow(String symbol,
                            String buyExchange,
                            double buyPrice,
                            String sellExchange,
                            double sellPrice,
                            double spreadPercent,
                            double expectedProfit) {
            this.symbol = new SimpleStringProperty(symbol);
            this.buyExchange = new SimpleStringProperty(buyExchange);
            this.buyPrice = new SimpleDoubleProperty(buyPrice);
            this.sellExchange = new SimpleStringProperty(sellExchange);
            this.sellPrice = new SimpleDoubleProperty(sellPrice);
            this.spreadPercent = new SimpleDoubleProperty(spreadPercent);
            this.expectedProfit = new SimpleDoubleProperty(expectedProfit);
        }

        public String getSymbol() {
            return symbol.get();
        }

        public String getBuyExchange() {
            return buyExchange.get();
        }

        public double getBuyPrice() {
            return buyPrice.get();
        }

        public String getSellExchange() {
            return sellExchange.get();
        }

        public double getSellPrice() {
            return sellPrice.get();
        }

        public double getSpreadPercent() {
            return spreadPercent.get();
        }

        public double getExpectedProfit() {
            return expectedProfit.get();
        }
    }
}

