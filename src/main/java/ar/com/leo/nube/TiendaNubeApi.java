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
     * Obtiene todas las ventas pagadas y sin envío de una tienda Nube.
     * GET /v1/{store_id}/orders?payment_status=paid&shipping_status=unshipped
     * Paginación con parámetro page.
     */
    private static List<Venta> obtenerVentas(StoreCredentials store, String label) {
        List<Venta> ventas = new ArrayList<>();
        int page = 1;
        final int perPage = 50;
        boolean hasMore = true;

        while (hasMore) {
            final int currentPage = page;
            String url = String.format(
                    "https://api.tiendanube.com/v1/%s/orders?payment_status=paid&shipping_status=unshipped&per_page=%d&page=%d",
                    store.storeId, perPage, currentPage);

            Supplier<HttpRequest> requestBuilder = () -> HttpRequest.newBuilder()
                    .uri(URI.create(url))
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
                AppLogger.warn("NUBE (" + label + ") - Error al obtener órdenes (página " + currentPage + "): " + body);
                break;
            }

            JsonNode ordersArray = mapper.readTree(response.body());

            if (!ordersArray.isArray() || ordersArray.isEmpty()) {
                hasMore = false;
                break;
            }

            for (JsonNode order : ordersArray) {
                long orderId = order.path("id").asLong(0);
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

            if (ordersArray.size() < perPage) {
                hasMore = false;
            } else {
                page++;
            }
        }

        AppLogger.info("NUBE (" + label + ") - Ventas obtenidas: " + ventas.size());
        return ventas;
    }

    /**
     * Obtiene el stock de un producto por SKU buscando en todas las tiendas.
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
}
