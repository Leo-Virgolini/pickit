package ar.com.leo.ml;

import ar.com.leo.AppLogger;
import ar.com.leo.HttpRetryHandler;
import ar.com.leo.ml.model.MLCredentials;
import ar.com.leo.ml.model.TokensML;
import ar.com.leo.pickit.model.Venta;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javafx.application.Platform;
import javafx.scene.control.TextInputDialog;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static ar.com.leo.HttpRetryHandler.BASE_SECRET_DIR;

public class MercadoLibreAPI {

    private static final Path MERCADOLIBRE_FILE = BASE_SECRET_DIR.resolve("ml_credentials.json");
    private static final Path TOKEN_FILE = BASE_SECRET_DIR.resolve("ml_tokens.json");
    private static final Object TOKEN_LOCK = new Object();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final HttpRetryHandler retryHandler = new HttpRetryHandler(httpClient, 30000L, 5, MercadoLibreAPI::verificarTokens);
    private static MLCredentials mlCredentials;
    private static volatile TokensML tokens;

    public static String getUserId() throws IOException {
        MercadoLibreAPI.verificarTokens();
        final String url = "https://api.mercadolibre.com/users/me";

        final Supplier<HttpRequest> requestBuilder = () -> HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + tokens.accessToken)
                .GET()
                .build();

        HttpResponse<String> response = retryHandler.sendWithRetry(requestBuilder);

        if (response.statusCode() != 200) {
            throw new IOException("Error al obtener el user ID de ML: " + response.body());
        }

        return mapper.readTree(response.body()).get("id").asString();
    }

    /**
     * Obtiene las ventas de ML con etiqueta lista para imprimir (ready_to_print).
     */
    public static List<Venta> obtenerVentasReadyToPrint(String userId) {
        verificarTokens();

        List<Venta> ventas = new ArrayList<>();
        Set<Long> orderIdsSeen = new HashSet<>();
        int offset = 0;
        final int limit = 50;
        boolean hasMore = true;

        while (hasMore) {
            final int currentOffset = offset;
            String url = String.format(
                    "https://api.mercadolibre.com/orders/search?seller=%s&shipping.status=ready_to_ship&shipping.substatus=ready_to_print&sort=date_asc&offset=%d&limit=%d",
                    userId, currentOffset, limit);

            Supplier<HttpRequest> requestBuilder = () -> HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + tokens.accessToken)
                    .GET()
                    .build();

            HttpResponse<String> response = retryHandler.sendWithRetry(requestBuilder);

            if (response == null || response.statusCode() != 200) {
                String body = response != null ? response.body() : "sin respuesta";
                AppLogger.warn("ML - Error al obtener órdenes ready_to_print (offset " + currentOffset + "): " + body);
                break;
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode results = root.path("results");

            if (!results.isArray() || results.isEmpty()) {
                break;
            }

            for (JsonNode order : results) {
                long orderId = order.path("id").asLong();
                if (!orderIdsSeen.add(orderId)) continue;

                JsonNode orderItems = order.path("order_items");
                if (!orderItems.isArray()) continue;

                for (JsonNode orderItem : orderItems) {
                    JsonNode item = orderItem.path("item");
                    String sku = item.path("seller_sku").asString("");
                    if (sku.isBlank()) {
                        sku = item.path("seller_custom_field").asString("");
                    }
                    double quantity = orderItem.path("quantity").asDouble(0);

                    if (!sku.isBlank() && quantity > 0) {
                        ventas.add(new Venta(sku, quantity, "ML"));
                    }
                }
            }

            JsonNode paging = root.path("paging");
            int total = paging.path("total").asInt(0);
            offset += limit;
            hasMore = offset < total;

            AppLogger.info(String.format("ML - Obtenidas %d/%d órdenes ready_to_print", Math.min(offset, total), total));
        }

        AppLogger.info("ML - Ventas ready_to_print: " + ventas.size());
        return ventas;
    }

    /**
     * Obtiene las ventas de ML con envío "Acuerdo con el vendedor" que NO tengan la nota "impreso".
     */
    public static List<Venta> obtenerVentasSellerAgreement(String userId) {
        verificarTokens();

        List<Venta> ventas = new ArrayList<>();
        Set<Long> orderIdsSeen = new HashSet<>();
        int offset = 0;
        final int limit = 50;
        boolean hasMore = true;
        int omitidas = 0;

        while (hasMore) {
            final int currentOffset = offset;
            String url = String.format(
                    "https://api.mercadolibre.com/orders/search?seller=%s&shipping.status=to_be_agreed&sort=date_asc&offset=%d&limit=%d",
                    userId, currentOffset, limit);

            Supplier<HttpRequest> requestBuilder = () -> HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + tokens.accessToken)
                    .GET()
                    .build();

            HttpResponse<String> response = retryHandler.sendWithRetry(requestBuilder);

            if (response == null || response.statusCode() != 200) {
                String body = response != null ? response.body() : "sin respuesta";
                AppLogger.warn("ML - Error al obtener órdenes seller_agreement (offset " + currentOffset + "): " + body);
                break;
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode results = root.path("results");

            if (!results.isArray() || results.isEmpty()) {
                break;
            }

            for (JsonNode order : results) {
                long orderId = order.path("id").asLong();
                if (!orderIdsSeen.add(orderId)) continue;

                // Verificar si la orden tiene la nota "impreso"
                if (tieneNotaImpreso(orderId)) {
                    omitidas++;
                    continue;
                }

                JsonNode orderItems = order.path("order_items");
                if (!orderItems.isArray()) continue;

                for (JsonNode orderItem : orderItems) {
                    JsonNode item = orderItem.path("item");
                    String sku = item.path("seller_sku").asString("");
                    if (sku.isBlank()) {
                        sku = item.path("seller_custom_field").asString("");
                    }
                    double quantity = orderItem.path("quantity").asDouble(0);

                    if (!sku.isBlank() && quantity > 0) {
                        ventas.add(new Venta(sku, quantity, "ML Acuerdo"));
                    }
                }
            }

            JsonNode paging = root.path("paging");
            int total = paging.path("total").asInt(0);
            offset += limit;
            hasMore = offset < total;

            AppLogger.info(String.format("ML - Obtenidas %d/%d órdenes seller_agreement", Math.min(offset, total), total));
        }

        AppLogger.info("ML - Ventas seller_agreement: " + ventas.size() + " (omitidas con nota 'impreso': " + omitidas + ")");
        return ventas;
    }

    /**
     * Verifica si una orden tiene alguna nota que contenga "impreso" (case-insensitive).
     */
    private static boolean tieneNotaImpreso(long orderId) {
        Supplier<HttpRequest> requestBuilder = () -> HttpRequest.newBuilder()
                .uri(URI.create("https://api.mercadolibre.com/orders/" + orderId + "/notes"))
                .header("Authorization", "Bearer " + tokens.accessToken)
                .GET()
                .build();

        HttpResponse<String> response = retryHandler.sendWithRetry(requestBuilder);

        if (response == null || response.statusCode() != 200) {
            return false;
        }

        try {
            JsonNode notes = mapper.readTree(response.body());
            if (!notes.isArray()) return false;

            for (JsonNode note : notes) {
                String texto = note.path("note").asString("");
                if (texto.toLowerCase().contains("impreso")) {
                    return true;
                }
            }
        } catch (Exception e) {
            AppLogger.warn("ML - Error al leer notas de orden " + orderId + ": " + e.getMessage());
        }

        return false;
    }

    // TOKENS
    // -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    public static boolean inicializar() {
        mlCredentials = cargarMLCredentials();
        if (mlCredentials == null) {
            AppLogger.warn("ML - No se encontró el archivo de credenciales.");
            return false;
        }

        tokens = cargarTokens();
        if (tokens == null) {
            // No hay tokens → pedir autorización
            AppLogger.info("ML - No hay tokens de ML, solicitando autorización...");
            final String code = pedirCodeManual();
            tokens = obtenerAccessToken(code);
            guardarTokens(tokens);
        }

        return true;
    }

    public static void verificarTokens() {
        // Verificar que tokens no sea null
        if (tokens == null) {
            AppLogger.warn("ML - Tokens no inicializados. Intentando inicializar...");
            if (!inicializar()) {
                throw new IllegalStateException("ML - No se pudieron inicializar los tokens.");
            }
            return;
        }

        // Chequeo rápido SIN bloqueo
        if (!tokens.isExpired()) {
            return;
        }

        // Solo bloquear si realmente está vencido
        synchronized (TOKEN_LOCK) {
            // Chequeo nuevamente dentro del lock (doble chequeo)
            if (tokens == null || !tokens.isExpired()) {
                return; // otro thread ya lo renovó o tokens aún válidos
            }

            AppLogger.info("ML - Access token expirado, renovando...");
            try {
                tokens = refreshAccessToken(tokens.refreshToken);
                tokens.issuedAt = System.currentTimeMillis();
                guardarTokens(tokens);
                AppLogger.info("ML - Token renovado correctamente.");
            } catch (Exception e) {
                AppLogger.warn("ML - Error al renovar token: " + e.getMessage());
                throw new RuntimeException("No se pudo renovar el token de ML", e);
            }
        }
    }

    private static MLCredentials cargarMLCredentials() {
        try {
            File f = MERCADOLIBRE_FILE.toFile();
            return f.exists() ? mapper.readValue(f, MLCredentials.class) : null;
        } catch (Exception e) {
            AppLogger.warn("Error cargando credenciales ML: " + e.getMessage());
            return null;
        }
    }

    private static TokensML cargarTokens() {
        try {
            File f = TOKEN_FILE.toFile();
            return f.exists() ? mapper.readValue(f, TokensML.class) : null;
        } catch (Exception e) {
            AppLogger.warn("Error cargando tokens ML: " + e.getMessage());
            return null;
        }
    }

    private static void guardarTokens(TokensML tokens) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(TOKEN_FILE.toFile(), tokens);
            AppLogger.info("ML - Tokens guardados en " + TOKEN_FILE);
        } catch (Exception e) {
            AppLogger.warn("Error guardando tokens ML: " + e.getMessage());
        }
    }

    private static String pedirCodeManual() {
        String authURL = "https://auth.mercadolibre.com.ar/authorization?response_type=code"
                + "&client_id=" + mlCredentials.clientId
                + "&redirect_uri=" + mlCredentials.redirectUri;

        AppLogger.info("ML - Se necesita autorización manual. Abriendo diálogo...");

        CompletableFuture<String> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("Autorización MercadoLibre");
            dialog.setHeaderText("Autorizá la app en esta URL y pegá el code:");
            dialog.setContentText(authURL);
            dialog.getDialogPane().setPrefWidth(600);
            dialog.showAndWait().ifPresentOrElse(
                    code -> future.complete(code.trim()),
                    () -> future.complete(null)
            );
        });

        try {
            String code = future.get();
            if (code == null || code.isBlank()) {
                throw new RuntimeException("ML - Autorización cancelada por el usuario.");
            }
            return code;
        } catch (Exception e) {
            throw new RuntimeException("ML - Error al obtener code de autorización.", e);
        }
    }

    private static TokensML obtenerAccessToken(String code) {

        Supplier<HttpRequest> requestBuilder = () -> HttpRequest.newBuilder()
                .uri(URI.create("https://api.mercadolibre.com/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=authorization_code" +
                                "&client_id=" + mlCredentials.clientId +
                                "&client_secret=" + mlCredentials.clientSecret +
                                "&code=" + code +
                                "&redirect_uri=" + mlCredentials.redirectUri))
                .build();

        HttpResponse<String> response = retryHandler.sendWithRetry(requestBuilder);
        if (response.statusCode() != 200) {
            throw new RuntimeException("Error al obtener access_token: " + response.body());
        }

        TokensML tokens = mapper.readValue(response.body(), TokensML.class);
        tokens.issuedAt = System.currentTimeMillis();
        return tokens;
    }

    private static TokensML refreshAccessToken(String refreshToken) {
        Supplier<HttpRequest> requestBuilder = () -> HttpRequest.newBuilder()
                .uri(URI.create("https://api.mercadolibre.com/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=refresh_token" +
                                "&client_id=" + mlCredentials.clientId +
                                "&client_secret=" + mlCredentials.clientSecret +
                                "&refresh_token=" + refreshToken))
                .build();

        HttpResponse<String> response = retryHandler.sendWithRetry(requestBuilder);
        if (response.statusCode() != 200) {
            throw new RuntimeException("Error al refrescar access_token: " + response.body());
        }

        TokensML tokens = mapper.readValue(response.body(), TokensML.class);
        tokens.issuedAt = System.currentTimeMillis();
        return tokens;
    }
}
