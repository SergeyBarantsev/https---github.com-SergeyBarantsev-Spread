# План внедрения улучшений (Code Review)

## Уже сделано
- [x] Удалено неиспользуемое поле `depositFieldRef`
- [x] Проверка пустого списка при Start + предупреждение
- [x] Вынесен общий `decimalCellFactory(DecimalFormat)` для таблицы арбитража
- [x] Проверка `path.getParent() != null` в CoinStorage.saveList

---

## Шаг 1. Константы путей ✅
**Цель:** один класс с путями к файлам (assets, config, log).  
**Сделано:** добавлен `com.spread.core.config.AppPaths` (baseCoinsFile, userCoinsFile, settingsFile, logDir, appLogFile, kucoinErrorLogFile, assetsDir). MainApp, CoinListGenerator, KucoinClient, AppLog переведены на AppPaths.

---

## Шаг 2. Общий ExecutorService для фоновых задач ✅
**Цель:** не создавать `new Thread()` в MainApp — использовать один пул.  
**Сделано:** в MainApp добавлено поле `backgroundExecutor` (cached thread pool, daemon-потоки с именем `spread-background-*`). Задачи «Добавить монету» и «Обновить с бирж» запускаются через `backgroundExecutor.execute()`. В `stop()` — `shutdown()` + `awaitTermination(5 сек)` и при необходимости `shutdownNow()`.

---

## Шаг 3. Логирование ошибок записи (Storage) ✅
**Цель:** при ошибке save логировать в AppLog.  
**Сделано:** `SettingsStorage.save()` и `CoinStorage.saveList()`/`addUserCoin()`/`clearAll()` возвращают `boolean`. В MainApp после каждого вызова при `false` вызывается `AppLog.error(...)` (сохранение настроек, добавление монеты, очистка списка). В SettingsStorage добавлена проверка `parent != null` перед созданием директорий.

---

## Шаг 4. Очистка PriceAggregator при disconnect ✅
**Цель:** при disconnect не показывать устаревшие цены после нового Connect.  
**Сделано:** в PriceAggregator добавлен метод `clear()`. В ExchangeManager сохранена ссылка на aggregator; в `disconnectAll()` сначала вызывается `aggregator.clear()`, затем отключение всех клиентов.

---

## Шаг 5. Deprecated TableView.CONSTRAINED_RESIZE_POLICY (откатан)
**Цель:** заменить на новый API JavaFX 20+. Откат: в текущей версии JavaFX метода `constrainedResizePolicy()` нет, оставлен `CONSTRAINED_RESIZE_POLICY` (предупреждение линтера допустимо).

---

## Шаг 6. Ротация логов (AppLog) ✅
**Цель:** не давать app.log расти бесконечно.  
**Сделано:** при размере app.log ≥ 5 МБ перед записью файл переименовывается в app.log.old (старый backup перезаписывается), затем запись идёт в новый app.log.

---

## Шаг 7. CoinListGenerator — цикл по биржам
**Цель:** убрать дублирование try/catch в `generateAndSave()`: список (название, Supplier<Set<String>>), один цикл с учётом okCount и incrementCounts.

---

## Шаг 8. Вынос TrackedCoin и ArbitrageRow
**Цель:** перенести классы из MainApp в `com.spread.app.model` (отдельные файлы), обновить импорты в MainApp.

---

## Шаг 9 (опционально). Базовый класс для клиентов бирж
**Цель:** общая логика connect/disconnect/реконнект в BaseExchangeClient; наследование Binance, Bybit, OKX, KuCoin. Можно отложить.

---

## Шаг 10 (опционально). Кэш для checkSymbolSupport
**Цель:** кэш по символу с TTL 5–10 минут, чтобы не дергать API при повторных проверках. Учесть делистинг.

---

## Шаг 11 (опционально). Тесты CoinStorage
**Цель:** тесты loadMergedCoins, addUserCoin, clearAll, сохранение/чтение в темп-директории.
