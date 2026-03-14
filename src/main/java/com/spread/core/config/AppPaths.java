package com.spread.core.config;

import java.nio.file.Path;

/**
 * Централизованные пути к файлам и каталогам приложения.
 */
public final class AppPaths {

    private static final String ASSETS_DIR = "assets";
    private static final String CONFIG_DIR = "config";
    private static final String LOG_DIR = "log";

    private AppPaths() {
    }

    public static Path assetsDir() {
        return Path.of(ASSETS_DIR);
    }

    /** Базовый список монет (результат «Обновить с бирж»). */
    public static Path baseCoinsFile() {
        return Path.of(ASSETS_DIR, "coins.json");
    }

    /** Пользовательский список монет (добавленные вручную). */
    public static Path userCoinsFile() {
        return Path.of(CONFIG_DIR, "user-coins.json");
    }

    /** Файл настроек (депозит, комиссии). */
    public static Path settingsFile() {
        return Path.of(CONFIG_DIR, "settings.json");
    }

    public static Path logDir() {
        return Path.of(LOG_DIR);
    }

    public static Path appLogFile() {
        return logDir().resolve("app.log");
    }

    public static Path kucoinErrorLogFile() {
        return logDir().resolve("kucoin-error.log");
    }
}
