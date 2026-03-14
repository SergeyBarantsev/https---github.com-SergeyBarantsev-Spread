package com.spread.app;

import com.spread.core.config.AppPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Простое логирование в файл log/app.log (UTF-8).
 * Ротация: при размере файла &gt; MAX_LOG_BYTES текущий лог переименовывается в app.log.old, запись идёт в новый app.log.
 */
public final class AppLog {

    private static final long MAX_LOG_BYTES = 5L * 1024 * 1024; // 5 МБ
    private static final Path LOG_DIR = AppPaths.logDir();
    private static final Path LOG_FILE = AppPaths.appLogFile();
    private static final Path LOG_FILE_OLD = LOG_DIR.resolve("app.log.old");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private AppLog() {
    }

    public static void info(String message) {
        log("INFO", message, null);
    }

    public static void info(String message, Object... args) {
        log("INFO", format(message, args), null);
    }

    public static void warn(String message) {
        log("WARN", message, null);
    }

    public static void warn(String message, Object... args) {
        log("WARN", format(message, args), null);
    }

    public static void error(String message) {
        log("ERROR", message, null);
    }

    public static void error(String message, Throwable t) {
        log("ERROR", message, t);
    }

    public static void error(String message, Object... args) {
        log("ERROR", format(message, args), null);
    }

    private static String format(String template, Object... args) {
        if (args == null || args.length == 0) {
            return template;
        }
        String s = template;
        for (Object a : args) {
            s = s.replaceFirst("\\{\\}", a == null ? "null" : a.toString());
        }
        return s;
    }

    private static synchronized void log(String level, String message, Throwable t) {
        try {
            Files.createDirectories(LOG_DIR);
            if (Files.exists(LOG_FILE) && Files.size(LOG_FILE) >= MAX_LOG_BYTES) {
                Files.move(LOG_FILE, LOG_FILE_OLD, StandardCopyOption.REPLACE_EXISTING);
            }
            String ts = FORMAT.format(Instant.now());
            StringBuilder line = new StringBuilder();
            line.append("[").append(ts).append("] [").append(level).append("] ").append(message);
            if (t != null) {
                line.append(" - ").append(t.getClass().getSimpleName());
                if (t.getMessage() != null && !t.getMessage().isBlank()) {
                    line.append(": ").append(t.getMessage());
                }
                line.append("\n");
                for (StackTraceElement e : t.getStackTrace()) {
                    line.append("  at ").append(e.toString()).append("\n");
                }
            } else {
                line.append("\n");
            }
            Files.write(LOG_FILE, line.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("AppLog write failed: " + e.getMessage());
        }
    }
}
