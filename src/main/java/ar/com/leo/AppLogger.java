package ar.com.leo;

import java.util.function.Consumer;

public class AppLogger {

    private static volatile Consumer<String> uiLogger;

    public static void setUiLogger(Consumer<String> logger) {
        uiLogger = logger;
    }

    public static void info(String message) {
        log(message);
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
        System.out.println(message);
        Consumer<String> logger = uiLogger;
        if (logger != null) {
            logger.accept(message);
        }
    }
}
