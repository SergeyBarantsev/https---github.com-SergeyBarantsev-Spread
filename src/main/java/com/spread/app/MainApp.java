package com.spread.app;

import com.spread.core.model.ArbitrageOpportunity;
import com.spread.core.model.Settings;
import com.spread.core.model.Settings.Exchange;
import com.spread.core.service.ArbitrageCalculator;
import com.spread.core.service.PriceAggregator;
import com.spread.core.storage.CoinStorage;
import com.spread.core.storage.SettingsStorage;
import com.spread.exchange.ExchangeManager;
import com.spread.tools.CoinListGenerator;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;
import java.text.DecimalFormat;
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
        loadCoins();

        // Главная вкладка: настройки (депозит, комиссии, кнопки) + таблица арбитража
        BorderPane mainContent = new BorderPane();
        mainContent.setTop(createSettingsPanel());
        mainContent.setCenter(createArbitragePanel());
        Tab mainTab = new Tab("Мониторинг", mainContent);
        mainTab.setClosable(false);

        // Вкладка управления монетами
        Tab coinsTab = new Tab("Монеты", createCoinsPanel());
        coinsTab.setClosable(false);

        TabPane tabPane = new TabPane(mainTab, coinsTab);
        root.setCenter(tabPane);

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

    private static final double LABEL_MIN_WIDTH = 90;
    private static final double DEPOSIT_FIELD_WIDTH = 140;
    private static final double FEE_FIELD_WIDTH = 72;

    private VBox createSettingsPanel() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(12, 16, 12, 16));

        HBox depositBox = new HBox(10);
        depositBox.setAlignment(Pos.CENTER_LEFT);
        Label depositLabel = new Label("Депозит (USDT):");
        depositLabel.setMinWidth(LABEL_MIN_WIDTH);
        TextField depositField = new TextField();
        depositField.setPromptText("Например, 1000");
        depositField.setPrefWidth(DEPOSIT_FIELD_WIDTH);
        depositField.setMaxWidth(DEPOSIT_FIELD_WIDTH);
        if (settings.getDeposit() > 0) {
            depositField.setText(Double.toString(settings.getDeposit()));
        }
        depositFieldRef = depositField;
        depositBox.getChildren().addAll(depositLabel, depositField);

        HBox feesHeader = new HBox(10);
        feesHeader.setAlignment(Pos.CENTER_LEFT);
        feesHeader.getChildren().add(new Label("Комиссии бирж (% покупка / продажа):"));

        HBox binanceBox = createExchangeFeeRow("Binance", Exchange.BINANCE);
        HBox bybitBox = createExchangeFeeRow("Bybit", Exchange.BYBIT);
        HBox okxBox = createExchangeFeeRow("OKX", Exchange.OKX);
        HBox kucoinBox = createExchangeFeeRow("KuCoin", Exchange.KUCOIN);

        HBox buttonsBox = new HBox(12);
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
        HBox box = new HBox(10);
        box.setAlignment(Pos.CENTER_LEFT);
        Label nameLabel = new Label(exchangeName + ":");
        nameLabel.setMinWidth(LABEL_MIN_WIDTH);
        TextField buyFeeField = new TextField();
        buyFeeField.setPromptText("покупка %");
        buyFeeField.setPrefWidth(FEE_FIELD_WIDTH);
        buyFeeField.setMaxWidth(FEE_FIELD_WIDTH);
        TextField sellFeeField = new TextField();
        sellFeeField.setPromptText("продажа %");
        sellFeeField.setPrefWidth(FEE_FIELD_WIDTH);
        sellFeeField.setMaxWidth(FEE_FIELD_WIDTH);
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
        VBox box = new VBox(10);
        box.setPadding(new Insets(12, 16, 12, 16));

        Label title = new Label("Список монет для отслеживания");

        TableView<TrackedCoin> table = new TableView<>(trackedCoins);
        table.setPrefHeight(250);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<TrackedCoin, String> symbolCol = new TableColumn<>("Монета");
        symbolCol.setCellValueFactory(new PropertyValueFactory<>("symbol"));
        symbolCol.setPrefWidth(200);
        symbolCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                setAlignment(Pos.CENTER_LEFT);
            }
        });

        TableColumn<TrackedCoin, Boolean> enabledCol = new TableColumn<>("Включена");
        enabledCol.setCellValueFactory(param -> param.getValue().enabledProperty());
        enabledCol.setCellFactory(CheckBoxTableCell.forTableColumn(enabledCol));
        enabledCol.setPrefWidth(100);

        table.getColumns().addAll(symbolCol, enabledCol);
        VBox.setVgrow(table, Priority.ALWAYS);

        HBox addBox = new HBox(10);
        addBox.setAlignment(Pos.CENTER_LEFT);
        TextField newCoinField = new TextField();
        HBox.setHgrow(newCoinField, Priority.ALWAYS);
        newCoinField.setPromptText("Например, BTCUSDT");
        Button addButton = new Button("Добавить");
        addButton.setOnAction(e -> {
            String symbol = newCoinField.getText();
            if (symbol == null || symbol.isBlank()) {
                return;
            }
            String normalized = symbol.trim().toUpperCase();
            // Проверяем монету в отдельном потоке, чтобы не блокировать UI
            Thread t = new Thread(() -> {
                try {
                    var support = CoinListGenerator.checkSymbolSupport(normalized);
                    long supportedCount = support.values().stream().filter(Boolean::booleanValue).count();
                    Platform.runLater(() -> {
                        if (supportedCount == 0) {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setTitle("Монета не найдена");
                            alert.setHeaderText("Монета " + normalized + " не поддерживается ни одной из бирж");
                            alert.setContentText("Проверьте, правильно ли введён тикер, и не была ли монета делистнута.");
                            alert.showAndWait();
                            return;
                        }

                        var unsupported = support.entrySet().stream()
                                .filter(e2 -> !Boolean.TRUE.equals(e2.getValue()))
                                .map(e2 -> e2.getKey().name())
                                .toList();

                        if (!unsupported.isEmpty()) {
                            String msg = "Монета " + normalized + " не поддерживается на биржах: "
                                    + String.join(", ", unsupported)
                                    + ".\nПродолжить добавление?";
                            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                            alert.setTitle("Частичная поддержка монеты");
                            alert.setHeaderText("Монета не поддержана на части бирж");
                            alert.setContentText(msg);
                            var result = alert.showAndWait();
                            if (result.isEmpty() || result.get() != ButtonType.OK) {
                                return;
                            }
                        }

                        trackedCoins.add(new TrackedCoin(normalized, true));
                        coinStorage.addUserCoin(normalized);
                        newCoinField.clear();
                    });
                } catch (IOException ex) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("Ошибка проверки монеты");
                        alert.setHeaderText("Не удалось проверить монету " + normalized);
                        alert.setContentText("Ошибка сети или API биржи: " + ex.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "manual-coin-check-" + normalized);
            t.setDaemon(true);
            t.start();
        });

        Button refreshButton = new Button("Обновить с бирж");
        refreshButton.setOnAction(e -> {
            // Запуск в отдельном потоке, чтобы не блокировать UI
            Thread t = new Thread(() -> {
                try {
                    CoinListGenerator.generateAndSave();
                    List<String> symbols = coinStorage.loadMergedCoins();
                    if (symbols.isEmpty()) {
                        symbols = List.of("BTCUSDT", "ETHUSDT", "BNBUSDT");
                    }
                    List<String> finalSymbols = symbols;
                    Platform.runLater(() -> {
                        trackedCoins.clear();
                        for (String s : finalSymbols) {
                            trackedCoins.add(new TrackedCoin(s, true));
                        }
                    });
                } catch (IOException ex) {
                    String msg = ex.getMessage() != null ? ex.getMessage() : "Неизвестная ошибка";
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("Ошибка обновления списка монет");
                        alert.setHeaderText("Не удалось обновить список монет с бирж");
                        alert.setContentText("Возможно, сеть недоступна или API бирж не отвечает вовремя.\n\nДетали: " + msg);
                        alert.showAndWait();
                    });
                }
            }, "coin-list-refresh");
            t.setDaemon(true);
            t.start();
        });

        Button clearButton = new Button("Очистить список");
        clearButton.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Очистить список монет");
            confirm.setHeaderText("Очистить список монет?");
            confirm.setContentText("Список будет полностью очищен (и базовый, и добавленные вручную). Потом можно снова загрузить монеты кнопкой «Обновить с бирж».");
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                trackedCoins.clear();
                coinStorage.clearAll();
            }
        });

        addBox.getChildren().addAll(newCoinField, addButton, refreshButton, clearButton);

        box.getChildren().addAll(title, table, addBox);
        return box;
    }

    private static final double ARB_SYMBOL_WIDTH = 110;
    private static final double ARB_EXCHANGE_WIDTH = 120;
    private static final double ARB_PRICE_WIDTH = 115;
    private static final double ARB_SPREAD_WIDTH = 90;
    private static final double ARB_PROFIT_WIDTH = 140;

    private VBox createArbitragePanel() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(12, 16, 12, 16));

        Label title = new Label("Наиболее выгодные арбитражные возможности");

        TableView<ArbitrageRow> table = new TableView<>(arbitrageRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<ArbitrageRow, String> symbolCol = new TableColumn<>("Монета");
        symbolCol.setCellValueFactory(new PropertyValueFactory<>("symbol"));
        symbolCol.setPrefWidth(ARB_SYMBOL_WIDTH);
        symbolCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                setAlignment(Pos.CENTER_LEFT);
            }
        });

        TableColumn<ArbitrageRow, String> buyExchangeCol = new TableColumn<>("Биржа покупки");
        buyExchangeCol.setCellValueFactory(new PropertyValueFactory<>("buyExchange"));
        buyExchangeCol.setPrefWidth(ARB_EXCHANGE_WIDTH);
        buyExchangeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                setAlignment(Pos.CENTER_LEFT);
            }
        });

        TableColumn<ArbitrageRow, Double> buyPriceCol = new TableColumn<>("Цена покупки");
        buyPriceCol.setCellValueFactory(new PropertyValueFactory<>("buyPrice"));
        buyPriceCol.setPrefWidth(ARB_PRICE_WIDTH);

        TableColumn<ArbitrageRow, String> sellExchangeCol = new TableColumn<>("Биржа продажи");
        sellExchangeCol.setCellValueFactory(new PropertyValueFactory<>("sellExchange"));
        sellExchangeCol.setPrefWidth(ARB_EXCHANGE_WIDTH);
        sellExchangeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                setAlignment(Pos.CENTER_LEFT);
            }
        });

        TableColumn<ArbitrageRow, Double> sellPriceCol = new TableColumn<>("Цена продажи");
        sellPriceCol.setCellValueFactory(new PropertyValueFactory<>("sellPrice"));
        sellPriceCol.setPrefWidth(ARB_PRICE_WIDTH);

        TableColumn<ArbitrageRow, Double> spreadCol = new TableColumn<>("Спред %");
        spreadCol.setCellValueFactory(new PropertyValueFactory<>("spreadPercent"));
        spreadCol.setPrefWidth(ARB_SPREAD_WIDTH);

        TableColumn<ArbitrageRow, Double> profitCol = new TableColumn<>("Ожидаемая прибыль");
        profitCol.setCellValueFactory(new PropertyValueFactory<>("expectedProfit"));
        profitCol.setPrefWidth(ARB_PROFIT_WIDTH);

        DecimalFormat priceFormat = new DecimalFormat("0.0000");
        DecimalFormat percentFormat = new DecimalFormat("0.00");

        buyPriceCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setText(null);
                } else {
                    setText(Double.toString(value));
                    setAlignment(Pos.CENTER_RIGHT);
                }
            }
        });

        sellPriceCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setText(null);
                } else {
                    setText(Double.toString(value));
                    setAlignment(Pos.CENTER_RIGHT);
                }
            }
        });

        spreadCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setText(null);
                } else {
                    setText(percentFormat.format(value));
                    setAlignment(Pos.CENTER_RIGHT);
                }
            }
        });

        profitCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setText(null);
                } else {
                    setText(priceFormat.format(value));
                    setAlignment(Pos.CENTER_RIGHT);
                }
            }
        });

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

