package ar.com.leo.fx.services;

import ar.com.leo.AppLogger;
import ar.com.leo.fx.model.ProductoManual;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.scene.control.TextArea;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class PickitService extends Service<File> {

    private final File stockExcel;
    private final File combosExcel;
    private final List<ProductoManual> productosManuales;
    private final TextArea logTextArea;

    public PickitService(File stockExcel, File combosExcel, ObservableList<ProductoManual> productosManuales, TextArea logTextArea) {
        this.stockExcel = stockExcel;
        this.combosExcel = combosExcel;
        this.productosManuales = new ArrayList<>(productosManuales);
        this.logTextArea = logTextArea;
    }

    @Override
    protected Task<File> createTask() {
        return new Task<>() {
            private final ConcurrentLinkedQueue<String> pendingMessages = new ConcurrentLinkedQueue<>();
            private final AtomicBoolean flushing = new AtomicBoolean(false);

            @Override
            protected File call() throws Exception {
                AppLogger.setUiLogger(message -> {
                    pendingMessages.add(message);
                    scheduleFlush();
                });

                try {
                    return PickitGenerator.generarPickit(stockExcel, combosExcel, productosManuales);
                } finally {
                    AppLogger.setUiLogger(null);
                }
            }

            private void scheduleFlush() {
                if (flushing.compareAndSet(false, true)) {
                    Platform.runLater(() -> {
                        List<String> batch = new ArrayList<>();
                        String msg;
                        while ((msg = pendingMessages.poll()) != null) {
                            batch.add(msg);
                        }
                        flushing.set(false);
                        if (!batch.isEmpty()) {
                            logTextArea.appendText(String.join("\n", batch) + "\n");
                        }
                        if (!pendingMessages.isEmpty()) {
                            scheduleFlush();
                        }
                    });
                }
            }
        };
    }
}
