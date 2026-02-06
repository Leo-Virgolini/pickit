package ar.com.leo;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

public class AppLogger {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static volatile Consumer<String> uiLogger;

    public static void setUiLogger(Consumer<String> logger) {
        uiLogger = logger;
    }

    public static void info(String message) {
        log(message);
    }

    public static void success(String message) {
        log("[OK] " + message);
    }

    public static void warn(String message) {
        log("[WARN] " + message);
    }

    public static void error(String message, Throwable throwable) {
        log("[ERROR] " + message);
        if (throwable != null) {
            log("[ERROR] " + throwable.getMessage());
        }
    }

    private static void log(String message) {
        String timestamped = "[" + LocalTime.now().format(TIME_FMT) + "] " + message;
        System.out.println(timestamped);
        Consumer<String> logger = uiLogger;
        if (logger != null) {
            logger.accept(timestamped);
        }
    }
}
