package ar.com.leo.nube;

import ar.com.leo.AppLogger;
import ar.com.leo.HttpRetryHandler;
import ar.com.leo.nube.model.NubeCredentials;
import ar.com.leo.nube.model.NubeCredentials.StoreCredentials;
import ar.com.leo.pickit.model.Venta;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static ar.com.leo.HttpRetryHandler.BASE_SECRET_DIR;

public class TiendaNubeApi {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final HttpRetryHandler retryHandler = new HttpRetryHandler(httpClient, 10000L, 2);
    private static final Path NUBE_CREDENTIALS_FILE = BASE_SECRET_DIR.resolve("nube_tokens.json");

    private static final String STORE_HOGAR = "KT HOGAR";
    private static final String STORE_GASTRO = "KT GASTRO";

    private static NubeCredentials credentials;

    // ================== METODO DE PRUEBA ==================
    public static void main(String[] args) {
        System.out.println("Iniciando prueba...");
        System.out.flush();
        if (!inicializar()) {
            System.out.println("Error al inicializar credenciales");
            return;
        }
        System.out.println("Credenciales cargadas OK");
        System.out.flush();

        testObtenerOrdenPorNumero("6296");

        System.out.println("Prueba finalizada");
        System.out.flush();
    }

    public static boolean inicializar() {
        credentials = cargarCredenciales();
        if (credentials == null || credentials.stores == null || credentials.stores.isEmpty()) {
            AppLogger.warn("NUBE - No se encontraron credenciales de Tienda Nube.");
            return false;
        }
        return true;
    }

    public static List<Venta> obtenerVentasHogar() {
        StoreCredentials store = getStore(STORE_HOGAR);
        if (store == null) {
            AppLogger.warn("NUBE - Credenciales de " + STORE_HOGAR + " no disponibles.");
            return List.of();
        }
        return obtenerVentas(store, STORE_HOGAR);
    }

    public static List<Venta> obtenerVentasGastro() {
        StoreCredentials store = getStore(STORE_GASTRO);
        if (store == null) {
            AppLogger.warn("NUBE - Credenciales de " + STORE_GASTRO + " no disponibles.");
            return List.of();
        }
        return obtenerVentas(store, STORE_GASTRO);
    }

    /**
     * Obtiene todas las ventas pagadas, abiertas y sin empaquetar de una tienda Nube.
     * GET /v1/{store_id}/orders?payment_status=paid&shipping_status=unpacked&status=open&aggregates=fulfillment_orders
     * Filtra client-side por fulfillment_orders con status UNPACKED.
     * Paginación usando el header Link como recomienda la documentación de Tiendanube.
     */
    private static List<Venta> obtenerVentas(StoreCredentials store, String label) {
        List<Venta> ventas = new ArrayList<>();
        String nextUrl = String.format(
                "https://api.tiendanube.com/v1/%s/orders?payment_status=paid&shipping_status=unpacked&status=open&aggregates=fulfillment_orders&per_page=200&page=1",
                store.storeId);

        while (nextUrl != null) {
            final String currentUrl = nextUrl;
            nextUrl = null;

            Supplier<HttpRequest> requestBuilder = () -> HttpRequest.newBuilder()
                    .uri(URI.create(currentUrl))
                    .header("Authentication", "bearer " + store.accessToken)
                    .header("User-Agent", "Pickit")
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = retryHandler.sendWithRetry(requestBuilder);

            if (response == null || response.statusCode() != 200) {
                // 404 con "Last page is 0" significa que no hay órdenes, no es un error
                if (response != null && response.statusCode() == 404 && response.body().contains("Last page is 0")) {
                    break;
                }
                String body = response != null ? response.body() : "sin respuesta";
                AppLogger.warn("NUBE (" + label + ") - Error al obtener órdenes: " + body);
                break;
            }

            JsonNode ordersArray = mapper.readTree(response.body());

            if (!ordersArray.isArray() || ordersArray.isEmpty()) {
                break;
            }

            for (JsonNode order : ordersArray) {
                long orderId = order.path("id").asLong(0);

                // Filtrar por fulfillment_orders con status UNPACKED
                if (!tieneFulfillmentUnpacked(order)) continue;

                // Omitir órdenes de retiro en local que tengan alguna nota
                if (esPickup(order) && tieneNota(order)) {
                    AppLogger.info("NUBE (" + label + ") - Omitida orden pickup con nota: " + orderId);
                    continue;
                }

                JsonNode products = order.path("products");
                if (!products.isArray()) continue;

                for (JsonNode product : products) {
                    String sku = product.path("sku").asString("");
                    double quantity = product.path("quantity").asDouble(0);
                    String productName = product.path("name").asString("");

                    if (quantity <= 0) {
                        AppLogger.warn("NUBE (" + label + ") - Producto con cantidad inválida en orden " + orderId + ": " + sku);
                        String errorSku = sku.isBlank() ? productName : sku;
                        ventas.add(new Venta("CANT INVALIDA: " + errorSku, quantity, label));
                        continue;
                    }
                    if (sku.isBlank()) {
                        AppLogger.warn("NUBE (" + label + ") - Producto sin SKU en orden " + orderId + ": " + productName);
                        ventas.add(new Venta("SIN SKU: " + productName, quantity, label));
                        continue;
                    }
                    ventas.add(new Venta(sku, quantity, label));
                }
            }

            // Obtener la URL de la siguiente página del header Link
            nextUrl = parseLinkNext(response);
        }

        AppLogger.info("NUBE (" + label + ") - Ventas obtenidas: " + ventas.size());
        return ventas;
    }

    /**
     * Parsea el header Link de la respuesta HTTP y extrae la URL con rel="next".
     * Formato esperado: <URL>; rel="next", <URL>; rel="last"
     * Retorna null si no hay página siguiente.
     */
    private static String parseLinkNext(HttpResponse<String> response) {
        var linkHeader = response.headers().firstValue("Link").orElse(null);
        if (linkHeader == null) return null;

        for (String part : linkHeader.split(",")) {
            part = part.trim();
            if (part.contains("rel=\"next\"")) {
                int start = part.indexOf('<');
                int end = part.indexOf('>');
                if (start >= 0 && end > start) {
                    return part.substring(start + 1, end);
                }
            }
        }
        return null;
    }

    /**
     * Obtiene el stock de un producto por SKU buscando en todas las tiendas.
     *
     * @param sku SKU del producto
     * @return Stock total o -1 si no se encuentra
     */
    public static int obtenerStockPorSku(String sku) {
        if (credentials == null || credentials.stores == null) {
            return -1;
        }

        // Buscar en todas las tiendas
        for (StoreCredentials store : credentials.stores.values()) {
            int stock = obtenerStockEnTienda(store, sku);
            if (stock >= 0) {
                return stock;
            }
        }
        return -1;
    }

    /**
     * Obtiene el stock de un producto por SKU en una tienda específica.
     * Usa el endpoint /products/sku/{sku} que devuelve el primer producto
     * donde una de sus variantes tiene el SKU dado.
     */
    private static int obtenerStockEnTienda(StoreCredentials store, String sku) {
        String url = String.format(
                "https://api.tiendanube.com/v1/%s/products/sku/%s",
                store.storeId, java.net.URLEncoder.encode(sku, java.nio.charset.StandardCharsets.UTF_8));

        Supplier<HttpRequest> requestBuilder = () -> HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authentication", "bearer " + store.accessToken)
                .header("User-Agent", "Pickit")
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = retryHandler.sendWithRetry(requestBuilder);

        if (response == null || response.statusCode() != 200) {
            return -1;
        }

        try {
            JsonNode product = mapper.readTree(response.body());

            // El endpoint devuelve un objeto producto directamente
            JsonNode variants = product.path("variants");
            if (variants.isArray()) {
                for (JsonNode variant : variants) {
                    String variantSku = variant.path("sku").asString("");
                    if (sku.equals(variantSku)) {
                        return variant.path("stock").asInt(0);
                    }
                }
            }
            return -1;
        } catch (Exception e) {
            AppLogger.warn("NUBE - Error al obtener stock de SKU " + sku + ": " + e.getMessage());
            return -1;
        }
    }

    private static boolean esPickup(JsonNode order) {
        JsonNode fulfillments = order.path("fulfillments");
        if (!fulfillments.isArray()) return false;
        for (JsonNode fo : fulfillments) {
            if ("pickup".equalsIgnoreCase(fo.path("shipping").path("type").asString(""))) {
                return true;
            }
        }
        return false;
    }

    private static boolean tieneNota(JsonNode order) {
        String nota = order.path("owner_note").asString("").trim();
        return !nota.isEmpty();
    }

    /**
     * Verifica si una orden tiene al menos un fulfillment order con status UNPACKED.
     */
    private static boolean tieneFulfillmentUnpacked(JsonNode order) {
        JsonNode fulfillments = order.path("fulfillments");
        if (!fulfillments.isArray() || fulfillments.isEmpty()) return false;
        for (JsonNode fo : fulfillments) {
            if ("unpacked".equalsIgnoreCase(fo.path("status").asString(""))) {
                return true;
            }
        }
        return false;
    }

    private static StoreCredentials getStore(String storeName) {
        if (credentials == null || credentials.stores == null) return null;
        return credentials.stores.get(storeName);
    }

    private static NubeCredentials cargarCredenciales() {
        try {
            File f = NUBE_CREDENTIALS_FILE.toFile();
            if (!f.exists()) return null;
            return mapper.readValue(f, NubeCredentials.class);
        } catch (Exception e) {
            AppLogger.warn("NUBE - Error al cargar credenciales: " + e.getMessage());
            return null;
        }
    }

    /**
     * Obtiene una orden específica por número de venta y muestra su JSON completo.
     */
    private static void testObtenerOrdenPorNumero(String numeroOrden) {
        // Intentar en ambas tiendas
        for (String storeName : List.of("KT HOGAR", "KT GASTRO")) {
            StoreCredentials store = getStore(storeName);
            if (store == null) continue;

            String url = String.format(
                    "https://api.tiendanube.com/v1/%s/orders?q=%s",
                    store.storeId, numeroOrden);

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authentication", "bearer " + store.accessToken)
                        .header("User-Agent", "Pickit")
                        .header("Content-Type", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    Object json = mapper.readValue(response.body(), Object.class);
                    if (json instanceof List<?> list && !list.isEmpty()) {
                        System.out.println("Orden encontrada en " + storeName + ":");
                        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json));
                        System.out.flush();
                        return;
                    }
                }
            } catch (Exception e) {
                System.out.println("Error en " + storeName + ": " + e.getMessage());
            }
        }
        System.out.println("Orden no encontrada: " + numeroOrden);
    }

}
