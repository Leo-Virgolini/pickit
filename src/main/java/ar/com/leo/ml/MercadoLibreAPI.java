package ar.com.leo.ml;

import ar.com.leo.AppLogger;
import ar.com.leo.HttpRetryHandler;
import ar.com.leo.ml.model.MLCredentials;
import ar.com.leo.ml.model.TokensML;
import ar.com.leo.pickit.model.OrdenML;
import ar.com.leo.pickit.model.Venta;
import javafx.application.Platform;
import javafx.scene.control.TextInputDialog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static ar.com.leo.HttpRetryHandler.BASE_SECRET_DIR;

public class MercadoLibreAPI {

    public record MLOrderResult(List<Venta> ventas, List<OrdenML> ordenes) {
    }

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
    public static MLOrderResult obtenerVentasReadyToPrint(String userId) {
        verificarTokens();

        List<Venta> ventas = new ArrayList<>();
        List<OrdenML> ordenes = new ArrayList<>();
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

                // Excluir órdenes con tag "delivered"
                JsonNode tagsNode = order.path("tags");
                if (tagsNode.isArray()) {
                    boolean esEntregada = false;
                    for (JsonNode tag : tagsNode) {
                        if ("delivered".equals(tag.asString())) {
                            esEntregada = true;
                            break;
                        }
                    }
                    if (esEntregada) continue;
                }

                String dateCreated = order.path("date_created").asString("");
                OffsetDateTime fecha = null;
                if (!dateCreated.isBlank()) {
                    try {
                        fecha = OffsetDateTime.parse(dateCreated);
                    } catch (Exception e) {
                        AppLogger.warn("ML - Error al parsear fecha de orden " + orderId + ": " + dateCreated);
                    }
                }
                JsonNode packNode = order.path("pack_id");
                Long packId = packNode.isNull() || packNode.isMissingNode() ? null : packNode.asLong();
                OrdenML ordenML = new OrdenML(orderId, packId, fecha);

                JsonNode orderItems = order.path("order_items");
                if (!orderItems.isArray()) continue;

                for (JsonNode orderItem : orderItems) {
                    JsonNode item = orderItem.path("item");
                    String sku = item.path("seller_sku").asString("");
                    if (sku.isBlank()) {
                        sku = item.path("seller_custom_field").asString("");
                    }
                    String itemTitle = item.path("title").asString("");
                    double quantity = orderItem.path("quantity").asDouble(0);

                    if (quantity <= 0) {
                        AppLogger.warn("ML - Producto con cantidad inválida en orden " + orderId + ": " + sku);
                        String errorSku = sku.isBlank() ? itemTitle : sku;
                        Venta venta = new Venta("CANT INVALIDA: " + errorSku, quantity, "ML");
                        ventas.add(venta);
                        ordenML.getItems().add(venta);
                        continue;
                    }
                    if (sku.isBlank()) {
                        AppLogger.warn("ML - Producto sin SKU en orden " + orderId + ": " + itemTitle);
                        Venta venta = new Venta("SIN SKU: " + itemTitle, quantity, "ML");
                        ventas.add(venta);
                        ordenML.getItems().add(venta);
                        continue;
                    }
                    Venta venta = new Venta(sku, quantity, "ML");
                    ventas.add(venta);
                    ordenML.getItems().add(venta);
                }

                if (!ordenML.getItems().isEmpty()) {
                    ordenes.add(ordenML);
                }
            }

            JsonNode paging = root.path("paging");
            int total = paging.path("total").asInt(0);
            offset += limit;
            hasMore = offset < total;

            AppLogger.info(String.format("ML - Obtenidas %d/%d órdenes ready_to_print", Math.min(offset, total), total));
        }

        AppLogger.info("ML - Ventas ready_to_print: " + ventas.size());
        return new MLOrderResult(ventas, ordenes);
    }

    /**
     * Obtiene las ventas de ML sin envío (acuerdo con el vendedor) que NO tengan la nota "impreso".
     */
    public static MLOrderResult obtenerVentasSellerAgreement(String userId) {
        verificarTokens();

        List<Venta> ventas = new ArrayList<>();
        List<OrdenML> ordenes = new ArrayList<>();
        Set<Long> orderIdsSeen = new HashSet<>();
        int offset = 0;
        final int limit = 50;
        boolean hasMore = true;
        int omitidas = 0;

        while (hasMore) {
            final int currentOffset = offset;
            String fechaDesde = java.time.OffsetDateTime.now()
                    .minusDays(7)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00:00.000XXX"));

            String url = String.format(
                    "https://api.mercadolibre.com/orders/search?seller=%s&tags=no_shipping&order.status=paid&order.date_created.from=%s&sort=date_asc&offset=%d&limit=%d",
                    userId, URLEncoder.encode(fechaDesde, StandardCharsets.UTF_8), currentOffset, limit);

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

                // Excluir órdenes con tag "delivered"
                JsonNode tagsNode = order.path("tags");
                if (tagsNode.isArray()) {
                    boolean esEntregada = false;
                    for (JsonNode tag : tagsNode) {
                        if ("delivered".equals(tag.asString())) {
                            esEntregada = true;
                            break;
                        }
                    }
                    if (esEntregada) continue;
                }

                // Excluir órdenes completadas (fulfilled)
                if (order.path("fulfilled").asBoolean(false)) continue;

                // Verificar si la orden tiene alguna nota
                if (tieneNota(orderId)) {
                    omitidas++;
                    continue;
                }

                String dateCreated = order.path("date_created").asString("");
                OffsetDateTime fecha = null;
                if (!dateCreated.isBlank()) {
                    try {
                        fecha = OffsetDateTime.parse(dateCreated);
                    } catch (Exception e) {
                        AppLogger.warn("ML - Error al parsear fecha de orden " + orderId + ": " + dateCreated);
                    }
                }
                JsonNode packNode = order.path("pack_id");
                Long packId = packNode.isNull() || packNode.isMissingNode() ? null : packNode.asLong();
                OrdenML ordenML = new OrdenML(orderId, packId, fecha);

                JsonNode orderItems = order.path("order_items");
                if (!orderItems.isArray()) continue;

                for (JsonNode orderItem : orderItems) {
                    JsonNode item = orderItem.path("item");
                    String sku = item.path("seller_sku").asString("");
                    if (sku.isBlank()) {
                        sku = item.path("seller_custom_field").asString("");
                    }
                    String itemTitle = item.path("title").asString("");
                    double quantity = orderItem.path("quantity").asDouble(0);

                    if (quantity <= 0) {
                        AppLogger.warn("ML Acuerdo - Producto con cantidad inválida en orden " + orderId + ": " + sku);
                        String errorSku = sku.isBlank() ? itemTitle : sku;
                        Venta venta = new Venta("CANT INVALIDA: " + errorSku, quantity, "ML Acuerdo");
                        ventas.add(venta);
                        ordenML.getItems().add(venta);
                        continue;
                    }
                    if (sku.isBlank()) {
                        AppLogger.warn("ML Acuerdo - Producto sin SKU en orden " + orderId + ": " + itemTitle);
                        Venta venta = new Venta("SIN SKU: " + itemTitle, quantity, "ML Acuerdo");
                        ventas.add(venta);
                        ordenML.getItems().add(venta);
                        continue;
                    }
                    Venta venta = new Venta(sku, quantity, "ML Acuerdo");
                    ventas.add(venta);
                    ordenML.getItems().add(venta);
                }

                if (!ordenML.getItems().isEmpty()) {
                    ordenes.add(ordenML);
                }
            }

            JsonNode paging = root.path("paging");
            int total = paging.path("total").asInt(0);
            offset += limit;
            hasMore = offset < total;

            AppLogger.info(String.format("ML - Obtenidas %d/%d órdenes seller_agreement", Math.min(offset, total), total));
        }

        AppLogger.info("ML - Ventas seller_agreement: " + ventas.size() + " (omitidas con nota: " + omitidas + ")");
        return new MLOrderResult(ventas, ordenes);
    }

    /**
     * Busca el stock disponible de un producto por SKU.
     * Primero busca por atributo SELLER_SKU, si no encuentra intenta por seller_custom_field.
     *
     * @param userId ID del usuario/vendedor en ML
     * @param sku    SKU del producto a buscar
     * @return Stock disponible, o -1 si no se encuentra el producto
     */
    public static int obtenerStockPorSku(String userId, String sku) {
        verificarTokens();

        String encodedSku = URLEncoder.encode(sku, StandardCharsets.UTF_8);

        // Intentar primero con seller_sku (atributo SELLER_SKU)
        String itemId = buscarItemPorSkuParam(userId, encodedSku, "seller_sku");

        // Si no encuentra, intentar con sku (campo seller_custom_field)
        if (itemId == null) {
            itemId = buscarItemPorSkuParam(userId, encodedSku, "sku");
        }

        if (itemId == null) {
            return -1;
        }

        return obtenerStockDeItem(itemId);
    }

    /**
     * Busca un item por SKU usando el parámetro especificado.
     * @return itemId si encuentra, null si no
     */
    private static String buscarItemPorSkuParam(String userId, String encodedSku, String paramName) {
        String url = String.format(
                "https://api.mercadolibre.com/users/%s/items/search?%s=%s",
                userId, paramName, encodedSku);

        Supplier<HttpRequest> requestBuilder = () -> HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + tokens.accessToken)
                .GET()
                .build();

        HttpResponse<String> response = retryHandler.sendWithRetry(requestBuilder);

        if (response == null || response.statusCode() != 200) {
            return null;
        }

        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode results = root.path("results");

            if (!results.isArray() || results.isEmpty()) {
                return null;
            }

            return results.get(0).asString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Obtiene el stock disponible de un item por su ID.
     * Primero obtiene el user_product_id y luego consulta el stock distribuido.
     */
    private static int obtenerStockDeItem(String itemId) {
        // Paso 1: Obtener el item para extraer user_product_id
        Supplier<HttpRequest> itemRequest = () -> HttpRequest.newBuilder()
                .uri(URI.create("https://api.mercadolibre.com/items/" + itemId))
                .header("Authorization", "Bearer " + tokens.accessToken)
                .GET()
                .build();

        HttpResponse<String> itemResponse = retryHandler.sendWithRetry(itemRequest);

        if (itemResponse == null || itemResponse.statusCode() != 200) {
            AppLogger.warn("ML - Error al obtener item " + itemId + ": " +
                    (itemResponse != null ? itemResponse.body() : "sin respuesta"));
            return -1;
        }

        String userProductId;
        try {
            JsonNode itemRoot = mapper.readTree(itemResponse.body());
            userProductId = itemRoot.path("user_product_id").asString("");
            if (userProductId.isBlank()) {
                // Fallback: usar available_quantity del item
                return itemRoot.path("available_quantity").asInt(0);
            }
        } catch (Exception e) {
            AppLogger.warn("ML - Error al leer user_product_id de item " + itemId + ": " + e.getMessage());
            return -1;
        }

        // Paso 2: Obtener stock distribuido del user_product
        Supplier<HttpRequest> stockRequest = () -> HttpRequest.newBuilder()
                .uri(URI.create("https://api.mercadolibre.com/user-products/" + userProductId + "/stock"))
                .header("Authorization", "Bearer " + tokens.accessToken)
                .GET()
                .build();

        HttpResponse<String> stockResponse = retryHandler.sendWithRetry(stockRequest);

        if (stockResponse == null || stockResponse.statusCode() != 200) {
            AppLogger.warn("ML - Error al obtener stock de user_product " + userProductId + ": " +
                    (stockResponse != null ? stockResponse.body() : "sin respuesta"));
            return -1;
        }

        try {
            JsonNode stockRoot = mapper.readTree(stockResponse.body());
            JsonNode locations = stockRoot.path("locations");

            if (!locations.isArray() || locations.isEmpty()) {
                return 0;
            }

            // Sumar stock de todas las ubicaciones
            int totalStock = 0;
            for (JsonNode location : locations) {
                totalStock += location.path("quantity").asInt(0);
            }
            return totalStock;
        } catch (Exception e) {
            AppLogger.warn("ML - Error al leer stock de user_product " + userProductId + ": " + e.getMessage());
            return -1;
        }
    }

    /**
     * Obtiene el stock de múltiples SKUs en paralelo.
     *
     * @param userId ID del usuario/vendedor en ML
     * @param skus   Lista de SKUs a buscar
     * @return Mapa de SKU → stock disponible (-1 si no se encontró)
     */
    public static Map<String, Integer> obtenerStockPorSkus(String userId, List<String> skus) {
        Map<String, Integer> stockMap = new LinkedHashMap<>();

        // Usar CompletableFuture para llamadas en paralelo
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String sku : skus) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                int stock = obtenerStockPorSku(userId, sku);
                synchronized (stockMap) {
                    stockMap.put(sku, stock);
                }
            });
            futures.add(future);
        }

        // Esperar a que terminen todas las llamadas
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return stockMap;
    }

    /**
     * Método de prueba: obtiene una orden por ID y muestra el JSON completo.
     */
    public static void testObtenerOrden(long orderId) {
        verificarTokens();

        String url = "https://api.mercadolibre.com/orders/" + orderId;

        Supplier<HttpRequest> requestBuilder = () -> HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + tokens.accessToken)
                .GET()
                .build();

        HttpResponse<String> response = retryHandler.sendWithRetry(requestBuilder);

        if (response == null) {
            System.err.println("ML TEST - Sin respuesta para orden " + orderId);
            return;
        }

        System.out.println("ML TEST - Status: " + response.statusCode());
        try {
            JsonNode json = mapper.readTree(response.body());
            String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
            System.out.println("ML TEST - Orden " + orderId + ":\n" + prettyJson);
        } catch (Exception e) {
            System.out.println("ML TEST - JSON raw:\n" + response.body());
        }
    }

    /**
     * Método de prueba: obtiene las notas de una orden por ID y muestra el JSON.
     */
    public static void testObtenerNotas(long orderId) {
        verificarTokens();

        String url = "https://api.mercadolibre.com/orders/" + orderId + "/notes";

        Supplier<HttpRequest> requestBuilder = () -> HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + tokens.accessToken)
                .GET()
                .build();

        HttpResponse<String> response = retryHandler.sendWithRetry(requestBuilder);

        if (response == null) {
            System.err.println("ML TEST - Sin respuesta para notas de orden " + orderId);
            return;
        }

        System.out.println("ML TEST NOTAS - Status: " + response.statusCode());
        try {
            JsonNode json = mapper.readTree(response.body());
            String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
            System.out.println("ML TEST NOTAS - Orden " + orderId + ":\n" + prettyJson);
        } catch (Exception e) {
            System.out.println("ML TEST NOTAS - JSON raw:\n" + response.body());
        }
    }

    /**
     * Método de prueba: obtiene los estados de shipment disponibles.
     */
    public static void testObtenerShipmentStatuses() {
        verificarTokens();

        String url = "https://api.mercadolibre.com/shipment_statuses";

        Supplier<HttpRequest> requestBuilder = () -> HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + tokens.accessToken)
                .header("x-format-new", "true")
                .GET()
                .build();

        HttpResponse<String> response = retryHandler.sendWithRetry(requestBuilder);

        if (response == null) {
            System.err.println("ML TEST - Sin respuesta para shipment_statuses");
            return;
        }

        System.out.println("ML TEST SHIPMENT STATUSES - Status: " + response.statusCode());
        try {
            JsonNode json = mapper.readTree(response.body());
            String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
            System.out.println("ML TEST SHIPMENT STATUSES:\n" + prettyJson);
        } catch (Exception e) {
            System.out.println("ML TEST SHIPMENT STATUSES - JSON raw:\n" + response.body());
        }
    }

    /**
     * Test: compara búsquedas con y sin tags.not=delivered
     * y muestra los tags de cada orden para entender la diferencia.
     */
    public static void testFiltroDelivered() {
        try {
            String userId = getUserId();
            String fechaDesde = java.time.OffsetDateTime.now()
                    .minusDays(7)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00:00.000XXX"));
            String fechaEncoded = URLEncoder.encode(fechaDesde, StandardCharsets.UTF_8);

            String urlBase = "https://api.mercadolibre.com/orders/search?seller=%s&tags=no_shipping&order.status=paid&order.date_created.from=%s&sort=date_asc&limit=50";
            String urlSin = String.format(urlBase, userId, fechaEncoded);
            String urlCon = urlSin + "&tags.not=delivered";

            // Query SIN tags.not
            Supplier<HttpRequest> reqSin = () -> HttpRequest.newBuilder()
                    .uri(URI.create(urlSin))
                    .header("Authorization", "Bearer " + tokens.accessToken)
                    .GET().build();
            HttpResponse<String> respSin = retryHandler.sendWithRetry(reqSin);
            JsonNode rootSin = mapper.readTree(respSin.body());
            int totalSin = rootSin.path("paging").path("total").asInt(0);

            // Query CON tags.not=delivered
            Supplier<HttpRequest> reqCon = () -> HttpRequest.newBuilder()
                    .uri(URI.create(urlCon))
                    .header("Authorization", "Bearer " + tokens.accessToken)
                    .GET().build();
            HttpResponse<String> respCon = retryHandler.sendWithRetry(reqCon);
            JsonNode rootCon = mapper.readTree(respCon.body());
            int totalCon = rootCon.path("paging").path("total").asInt(0);

            System.out.println("=== TEST tags.not=delivered (últimos 7 días) ===");
            System.out.println("SIN filtro:            total = " + totalSin);
            System.out.println("CON tags.not=delivered: total = " + totalCon);
            System.out.println("Diferencia:            " + (totalSin - totalCon));
            System.out.println();

            // Mostrar tags de cada orden de la query SIN filtro
            System.out.println("--- Detalle de órdenes (sin filtro) ---");
            JsonNode results = rootSin.path("results");
            if (results.isArray()) {
                for (JsonNode order : results) {
                    long orderId = order.path("id").asLong();
                    String status = order.path("status").asString("");
                    JsonNode tags = order.path("tags");
                    boolean fulfilled = order.path("fulfilled").asBoolean(false);
                    boolean tieneNotaEscrita = tieneNota(orderId);
                    System.out.println("Orden " + orderId + " | status: " + status + " | fulfilled: " + fulfilled + " | nota: " + tieneNotaEscrita + " | tags: " + tags);
                }
            }
            System.out.println("================================================");

        } catch (Exception e) {
            System.err.println("Error en test: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Main de prueba
    public static void main(String[] args) {
        if (!inicializar()) {
            System.err.println("No se pudo inicializar ML API");
            return;
        }
        testFiltroDelivered();
    }

    /**
     * Verifica si una orden tiene alguna nota escrita.
     */
    private static boolean tieneNota(long orderId) {
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
            JsonNode root = mapper.readTree(response.body());
            if (!root.isArray() || root.isEmpty()) return false;

            JsonNode results = root.get(0).path("results");
            if (!results.isArray()) return false;

            for (JsonNode note : results) {
                String texto = note.path("note").asString("").trim();
                if (!texto.isEmpty()) {
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
