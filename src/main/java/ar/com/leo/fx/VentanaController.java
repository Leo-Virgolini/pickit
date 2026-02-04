package ar.com.leo.fx;

import ar.com.leo.AppLogger;
import ar.com.leo.fx.services.PickitService;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.media.AudioClip;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;

public class VentanaController implements Initializable {

    @FXML
    private TextField ubicacionPickitExcel;
    @FXML
    private Button pickitButton;
    @FXML
    private TextArea logTextArea;
    @FXML
    private ProgressIndicator progressIndicator;

    private File pickitExcel;

    private AudioClip errorSound;
    private AudioClip successSound;

    public void initialize(URL url, ResourceBundle rb) {
        errorSound = new AudioClip(getClass().getResource("/audios/error.mp3").toExternalForm());
        successSound = new AudioClip(getClass().getResource("/audios/success.mp3").toExternalForm());
        errorSound.setVolume(0.1);
        successSound.setVolume(0.1);

        loadPreferences();

        Main.stage.setOnCloseRequest(event -> savePreferences());
    }

    private void loadPreferences() {
        Preferences prefs = Preferences.userRoot().node("pickit");
        String pathPickit = prefs.get("pathPickitExcel", null);
        if (pathPickit != null && !pathPickit.isBlank()) {
            pickitExcel = new File(pathPickit);
            if (pickitExcel.isFile()) {
                ubicacionPickitExcel.setText(pickitExcel.getAbsolutePath());
            } else {
                pickitExcel = null;
            }
        }
    }

    private void savePreferences() {
        Preferences prefs = Preferences.userRoot().node("pickit");
        if (ubicacionPickitExcel.getText() != null && !ubicacionPickitExcel.getText().isBlank()) {
            prefs.put("pathPickitExcel", ubicacionPickitExcel.getText());
        }
    }

    @FXML
    public void buscarPickitExcel(ActionEvent event) {
        logTextArea.clear();

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Elige archivo PICKIT.xlsm");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Archivo XLSM", "*.xlsm"));

        String currentPath = ubicacionPickitExcel.getText();
        File lastFile = (currentPath != null && !currentPath.isBlank()) ? new File(currentPath) : null;
        File initialDir = (lastFile != null && lastFile.exists() && lastFile.getParentFile() != null)
                ? lastFile.getParentFile() : new File(System.getProperty("user.dir"));

        fileChooser.setInitialDirectory(initialDir);

        pickitExcel = fileChooser.showOpenDialog(Main.stage);

        if (pickitExcel != null) {
            ubicacionPickitExcel.setText(pickitExcel.getAbsolutePath());
        } else {
            ubicacionPickitExcel.clear();
        }
    }

    @FXML
    public void generarPickit(ActionEvent event) {
        logTextArea.clear();

        if (pickitExcel == null || !pickitExcel.isFile()) {
            logTextArea.setStyle("-fx-text-fill: firebrick;");
            logTextArea.setText("Error: seleccionar el archivo PICKIT.xlsm primero.");
            return;
        }

        PickitService service = new PickitService(pickitExcel, logTextArea);
        service.setOnRunning(e -> {
            pickitButton.setDisable(true);
            progressIndicator.setVisible(true);
            logTextArea.setStyle("-fx-text-fill: darkblue;");
        });
        service.setOnSucceeded(e -> {
            successSound.play();
            logTextArea.setStyle("-fx-text-fill: darkgreen;");
            AppLogger.info("Pickit generado: " + service.getValue().getAbsolutePath());
            AppLogger.info("Proceso Pickit finalizado.");
            pickitButton.setDisable(false);
            progressIndicator.setVisible(false);
        });
        service.setOnFailed(e -> {
            errorSound.play();
            logTextArea.setStyle("-fx-text-fill: firebrick;");
            AppLogger.error("Error Pickit: " + service.getException().getLocalizedMessage(), service.getException());
            pickitButton.setDisable(false);
            progressIndicator.setVisible(false);
        });
        service.start();
    }
}
