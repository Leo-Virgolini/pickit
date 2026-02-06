package ar.com.leo.fx;

import ar.com.leo.AppLogger;
import ar.com.leo.excel.ExcelManager;
import ar.com.leo.fx.model.ProductoManual;
import ar.com.leo.fx.services.PickitService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Pane;
import javafx.scene.media.AudioClip;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;

public class VentanaController implements Initializable {

    @FXML
    private TextField ubicacionStockExcel;
    @FXML
    private TextField ubicacionCombosExcel;
    @FXML
    private Button pickitButton;
    @FXML
    private ScrollPane logScrollPane;
    @FXML
    private TextFlow logTextFlow;
    @FXML
    private ProgressIndicator progressIndicator;
    @FXML
    private TextField skuManualField;
    @FXML
    private TextField cantidadManualField;
    @FXML
    private TableView<ProductoManual> productosManualTable;
    @FXML
    private TableColumn<ProductoManual, String> colSku;
    @FXML
    private TableColumn<ProductoManual, Double> colCantidad;
    @FXML
    private Button btnAgregarModificar;
    @FXML
    private Pane paneStock;
    @FXML
    private Pane paneCombos;
    @FXML
    private Pane paneManuales;

    private File stockExcel;
    private File combosExcel;
    private File ultimoImportDir;
    private final ObservableList<ProductoManual> productosManualList = FXCollections.observableArrayList();
    private ProductoManual productoEnEdicion = null;

    private AudioClip errorSound;
    private AudioClip successSound;

    public void initialize(URL url, ResourceBundle rb) {
        errorSound = new AudioClip(getClass().getResource("/audios/error.mp3").toExternalForm());
        successSound = new AudioClip(getClass().getResource("/audios/success.mp3").toExternalForm());
        errorSound.setVolume(0.1);
        successSound.setVolume(0.1);

        // Configurar tabla de productos manuales
        colSku.setCellValueFactory(new PropertyValueFactory<>("sku"));
        colCantidad.setCellValueFactory(new PropertyValueFactory<>("cantidad"));

        // Centrar contenido de las columnas
        colSku.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                    setStyle("-fx-alignment: CENTER;");
                }
            }
        });
        colCantidad.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item == Math.floor(item) ? String.valueOf(item.intValue()) : String.valueOf(item));
                    setStyle("-fx-alignment: CENTER;");
                }
            }
        });

        productosManualTable.setItems(productosManualList);

        // Listener para cargar datos al seleccionar una fila
        productosManualTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                productoEnEdicion = newVal;
                skuManualField.setText(newVal.getSku());
                double cant = newVal.getCantidad();
                cantidadManualField.setText(cant == Math.floor(cant) ? String.valueOf((int) cant) : String.valueOf(cant));
                btnAgregarModificar.setText("Modificar");
            } else {
                productoEnEdicion = null;
                btnAgregarModificar.setText("Agregar");
            }
        });

        // Auto-scroll al fondo cuando se agrega contenido
        logTextFlow.heightProperty().addListener((obs, oldVal, newVal) -> logScrollPane.setVvalue(1.0));

        // Menú contextual para copiar el log
        ContextMenu logContextMenu = new ContextMenu();
        MenuItem copiarTodo = new MenuItem("Copiar todo");
        copiarTodo.setOnAction(e -> {
            StringBuilder sb = new StringBuilder();
            for (var node : logTextFlow.getChildren()) {
                if (node instanceof Text t) {
                    sb.append(t.getText());
                }
            }
            ClipboardContent content = new ClipboardContent();
            content.putString(sb.toString());
            Clipboard.getSystemClipboard().setContent(content);
        });
        logContextMenu.getItems().add(copiarTodo);
        logScrollPane.setContextMenu(logContextMenu);

        loadPreferences();

        Main.stage.setOnCloseRequest(event -> savePreferences());
    }

    private void setUIDisabled(boolean disabled) {
        pickitButton.setDisable(disabled);
        paneStock.setDisable(disabled);
        paneCombos.setDisable(disabled);
        paneManuales.setDisable(disabled);
    }

    private void clearLog() {
        logTextFlow.getChildren().clear();
    }

    private void appendLog(String message, Color color) {
        Text text = new Text(message + "\n");
        text.setFill(color);
        text.setFont(Font.font("Roboto", 13));
        logTextFlow.getChildren().add(text);
    }

    private void loadPreferences() {
        Preferences prefs = Preferences.userRoot().node("pickit");
        String pathStock = prefs.get("pathStockExcel", null);
        if (pathStock != null && !pathStock.isBlank()) {
            stockExcel = new File(pathStock);
            if (stockExcel.isFile()) {
                ubicacionStockExcel.setText(stockExcel.getAbsolutePath());
            } else {
                stockExcel = null;
            }
        }
        String pathCombos = prefs.get("pathCombosExcel", null);
        if (pathCombos != null && !pathCombos.isBlank()) {
            combosExcel = new File(pathCombos);
            if (combosExcel.isFile()) {
                ubicacionCombosExcel.setText(combosExcel.getAbsolutePath());
            } else {
                combosExcel = null;
            }
        }
        String pathImportDir = prefs.get("pathImportDir", null);
        if (pathImportDir != null && !pathImportDir.isBlank()) {
            File dir = new File(pathImportDir);
            if (dir.isDirectory()) {
                ultimoImportDir = dir;
            }
        }
    }

    private void savePreferences() {
        Preferences prefs = Preferences.userRoot().node("pickit");
        if (ubicacionStockExcel.getText() != null && !ubicacionStockExcel.getText().isBlank()) {
            prefs.put("pathStockExcel", ubicacionStockExcel.getText());
        }
        if (ubicacionCombosExcel.getText() != null && !ubicacionCombosExcel.getText().isBlank()) {
            prefs.put("pathCombosExcel", ubicacionCombosExcel.getText());
        }
        if (ultimoImportDir != null) {
            prefs.put("pathImportDir", ultimoImportDir.getAbsolutePath());
        }
    }

    @FXML
    public void buscarStockExcel(ActionEvent event) {
        clearLog();

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Elige archivo Stock.xlsx");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Archivo XLSX", "*.xlsx"));

        String currentPath = ubicacionStockExcel.getText();
        File lastFile = (currentPath != null && !currentPath.isBlank()) ? new File(currentPath) : null;
        File initialDir = (lastFile != null && lastFile.exists() && lastFile.getParentFile() != null)
                ? lastFile.getParentFile() : new File(System.getProperty("user.dir"));

        fileChooser.setInitialDirectory(initialDir);

        stockExcel = fileChooser.showOpenDialog(Main.stage);

        if (stockExcel != null) {
            ubicacionStockExcel.setText(stockExcel.getAbsolutePath());
        } else {
            ubicacionStockExcel.clear();
        }
    }

    @FXML
    public void buscarCombosExcel(ActionEvent event) {
        clearLog();

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Elige archivo Combos");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Archivo Excel", "*.xls", "*.xlsx"));

        String currentPath = ubicacionCombosExcel.getText();
        File lastFile = (currentPath != null && !currentPath.isBlank()) ? new File(currentPath) : null;
        File initialDir = (lastFile != null && lastFile.exists() && lastFile.getParentFile() != null)
                ? lastFile.getParentFile() : new File(System.getProperty("user.dir"));

        fileChooser.setInitialDirectory(initialDir);

        combosExcel = fileChooser.showOpenDialog(Main.stage);

        if (combosExcel != null) {
            ubicacionCombosExcel.setText(combosExcel.getAbsolutePath());
        } else {
            ubicacionCombosExcel.clear();
        }
    }

    @FXML
    public void generarPickit(ActionEvent event) {
        clearLog();

        if (stockExcel == null || !stockExcel.isFile()) {
            appendLog("Error: seleccionar el archivo Stock.xlsx primero.", Color.FIREBRICK);
            return;
        }
        if (combosExcel == null || !combosExcel.isFile()) {
            appendLog("Error: seleccionar el archivo Combos.xlsx primero.", Color.FIREBRICK);
            return;
        }

        PickitService service = new PickitService(stockExcel, combosExcel, productosManualList, logTextFlow, logScrollPane);
        service.setOnRunning(e -> {
            setUIDisabled(true);
            progressIndicator.setVisible(true);
        });
        service.setOnSucceeded(e -> {
            successSound.play();
            setUIDisabled(false);
            progressIndicator.setVisible(false);
        });
        service.setOnFailed(e -> {
            errorSound.play();
            Throwable ex = service.getException();
            String mensaje = ex != null ? ex.getLocalizedMessage() : "Error desconocido";
            appendLog("\nERROR: " + mensaje, Color.FIREBRICK);
            AppLogger.error("Error Pickit: " + mensaje, ex);
            setUIDisabled(false);
            progressIndicator.setVisible(false);
        });
        service.start();
    }

    @FXML
    public void agregarProductoManual(ActionEvent event) {
        String sku = skuManualField.getText();
        if (sku == null || sku.isBlank()) {
            return;
        }
        sku = sku.trim();

        // Validar que SKU sea numérico
        if (!sku.matches("\\d+")) {
            clearLog();
            appendLog("Error: el SKU debe ser numérico.", Color.FIREBRICK);
            return;
        }

        double cantidad = 1;
        String cantidadText = cantidadManualField.getText();
        if (cantidadText != null && !cantidadText.isBlank()) {
            try {
                cantidad = Double.parseDouble(cantidadText.trim());
            } catch (NumberFormatException e) {
                clearLog();
                appendLog("Error: cantidad inválida.", Color.FIREBRICK);
                return;
            }
        }

        if (cantidad <= 0) {
            clearLog();
            appendLog("Error: la cantidad debe ser mayor a 0.", Color.FIREBRICK);
            return;
        }

        // Verificar SKU duplicado
        final String skuFinal = sku;
        boolean duplicado = productosManualList.stream()
                .anyMatch(p -> p.getSku().equalsIgnoreCase(skuFinal) && p != productoEnEdicion);

        if (duplicado) {
            clearLog();
            appendLog("Error: ya existe un producto con SKU " + sku, Color.FIREBRICK);
            return;
        }

        if (productoEnEdicion != null) {
            // Modo edición: actualizar producto existente
            productoEnEdicion.setSku(sku);
            productoEnEdicion.setCantidad(cantidad);
            productosManualTable.refresh();
            productoEnEdicion = null;
        } else {
            // Modo agregar: crear nuevo producto
            productosManualList.add(new ProductoManual(sku, cantidad));
        }

        productosManualTable.getSelectionModel().clearSelection();
        btnAgregarModificar.setText("Agregar");
        skuManualField.clear();
        cantidadManualField.clear();
        skuManualField.requestFocus();
    }

    @FXML
    public void eliminarProductoManual(ActionEvent event) {
        ProductoManual selected = productosManualTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            productosManualList.remove(selected);
            if (selected == productoEnEdicion) {
                productoEnEdicion = null;
            }
            productosManualTable.getSelectionModel().clearSelection();
            btnAgregarModificar.setText("Agregar");
            skuManualField.clear();
            cantidadManualField.clear();
        }
    }

    @FXML
    public void limpiarProductosManuales(ActionEvent event) {
        productosManualList.clear();
        productoEnEdicion = null;
        productosManualTable.getSelectionModel().clearSelection();
        btnAgregarModificar.setText("Agregar");
        skuManualField.clear();
        cantidadManualField.clear();
    }

    @FXML
    public void importarProductosExcel(ActionEvent event) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Importar productos manuales desde Excel");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Archivo XLSX", "*.xlsx"));

        File initialDir = (ultimoImportDir != null && ultimoImportDir.isDirectory())
                ? ultimoImportDir : new File(System.getProperty("user.dir"));
        fileChooser.setInitialDirectory(initialDir);

        File file = fileChooser.showOpenDialog(Main.stage);
        if (file == null) return;

        ultimoImportDir = file.getParentFile();
        clearLog();

        try {
            List<ProductoManual> importados = ExcelManager.obtenerProductosManualesDesdeExcel(file);
            int nuevos = 0;
            int sumados = 0;

            for (ProductoManual importado : importados) {
                ProductoManual existente = productosManualList.stream()
                        .filter(p -> p.getSku().equalsIgnoreCase(importado.getSku()))
                        .findFirst().orElse(null);

                if (existente != null) {
                    existente.setCantidad(existente.getCantidad() + importado.getCantidad());
                    sumados++;
                } else {
                    productosManualList.add(importado);
                    nuevos++;
                }
            }

            productosManualTable.refresh();
            appendLog("Importación completada: " + nuevos + " nuevos, " + sumados + " sumados a existentes.", Color.LIGHTGREEN);
            AppLogger.info("IMPORT - " + nuevos + " nuevos, " + sumados + " sumados de " + file.getName());
        } catch (Exception e) {
            appendLog("Error al importar: " + e.getMessage(), Color.FIREBRICK);
            AppLogger.error("Error al importar Excel: " + e.getMessage(), e);
        }
    }
}
