package ar.com.leo.dux;

import ar.com.leo.AppLogger;
import ar.com.leo.HttpRetryHandler;
import ar.com.leo.dux.model.DuxResponse;
import ar.com.leo.dux.model.Item;
import ar.com.leo.dux.model.Stock;
import ar.com.leo.dux.model.TokensDux;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static ar.com.leo.HttpRetryHandler.BASE_SECRET_DIR;

public class DuxApi {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    // 0.143 permits/sec ≈ 1 request cada ~7 segundos. Con 5s o 5.5s la API devuelve 429 tras muchos requests seguidos.
    private static final HttpRetryHandler retryHandler = new HttpRetryHandler(httpClient, 7000L, 0.143);
    private static final Path TOKEN_FILE = BASE_SECRET_DIR.resolve("dux_tokens.json");
    private static TokensDux tokens;

    // NOTA: Los campos UNIDAD MEDIDA, TIPO DE PRODUCTO y UNIDADES POR BULTO no
    // están en la respuesta de la API de DUX. Solo permite obtener de maximo 50
    // productos por request cada 5 segundos
    public static List<Item> obtenerProductos(String fecha) throws IOException {

        List<Item> allItems = new ArrayList<>();
        int offset = 0;
        int total = Integer.MAX_VALUE;
        final int limit = 50;
        int intentosVacios = 0;
        final int MAX_INTENTOS_VACIOS = 3; // Máximo de respuestas vacías consecutivas antes de terminar

        final boolean conFiltroFecha = fecha != null && !fecha.isBlank();
        final String fechaParam = conFiltroFecha
                ? "&fecha=" + URLEncoder.encode(fecha, StandardCharsets.UTF_8)
                : "";
        final String tipoProductos = conFiltroFecha ? "productos modificados (por stock o precio)" : "productos";

        while (offset < total) {
            final int finalOffset = offset;
            Supplier<HttpRequest> requestBuilder = () -> HttpRequest.newBuilder()
                    .uri(URI.create("https://erp.duxsoftware.com.ar/WSERP/rest/services/items?offset=" + finalOffset
                            + "&limit=" + limit + fechaParam))
                    .GET()
                    .header("accept", "application/json")
                    .header("authorization", tokens.token)
                    .build();

            HttpResponse<String> response = retryHandler.sendWithRetry(requestBuilder);

            if (response == null || response.statusCode() != 200) {
                String body = response != null ? response.body() : "sin respuesta";
                throw new IOException("Error al obtener productos: " + body);
            }

            String body = response.body().trim();

            // Mapear respuesta a objeto Java
            DuxResponse duxResponse = mapper.readValue(body, DuxResponse.class);

            // Actualizar total si es la primera vez o si cambió
            if (duxResponse.getPaging() != null) {
                int nuevoTotal = duxResponse.getPaging().getTotal();
                if (total == Integer.MAX_VALUE || nuevoTotal != total) {
                    total = nuevoTotal;
                    AppLogger.info("DUX - Total de " + tipoProductos + " en DUX: " + total);
                }
            }

            // Verificar si hay resultados
            if (duxResponse.getResults() == null || duxResponse.getResults().isEmpty()) {
                // Si ya alcanzamos o superamos el total, terminar
                if (offset >= total) {
                    AppLogger.info("DUX - No hay más resultados. Fin de la paginación (offset >= total).");
                    break;
                }

                intentosVacios++;
                AppLogger.warn("DUX - Respuesta vacía en offset " + offset + " (intento " + intentosVacios + "/"
                        + MAX_INTENTOS_VACIOS + "). Total esperado: " + total);

                // Si tenemos varios intentos vacíos consecutivos, terminar (evitar loops
                // infinitos)
                if (intentosVacios >= MAX_INTENTOS_VACIOS) {
                    AppLogger.warn("DUX - Terminando después de " + MAX_INTENTOS_VACIOS
                            + " intentos vacíos consecutivos. Productos obtenidos: " + allItems.size() + " / " + total);
                    break;
                }

                // Aumentar offset y continuar
                offset += limit;
                continue;
            }

            // Resetear contador de intentos vacíos si obtuvimos resultados
            intentosVacios = 0;

            allItems.addAll(duxResponse.getResults());

            AppLogger.info(String.format("DUX - Obtenidos: %d / %d", allItems.size(), total));

            // Aumentar offset
            offset += limit;

            // Verificar si ya obtuvimos todos los productos
            if (allItems.size() >= total) {
                AppLogger.info("DUX - Se obtuvieron todos los productos disponibles.");
                break;
            }
        }

        AppLogger.info("DUX - Descarga completa: " + allItems.size() + " " + tipoProductos + " obtenidos de " + total + " totales.");
        return allItems;
    }

    /**
     * Obtiene un producto específico por su código de item.
     * Usa el parámetro codigoItem de la API para evitar traer todo el catálogo.
     *
     * @param codigoItem Código del producto a buscar
     * @return El Item encontrado, o null si no existe
     */
    public static Item obtenerProductoPorCodigo(String codigoItem) throws IOException {
        String codEncoded = URLEncoder.encode(codigoItem, StandardCharsets.UTF_8);

        Supplier<HttpRequest> requestBuilder = () -> HttpRequest.newBuilder()
                .uri(URI.create("https://erp.duxsoftware.com.ar/WSERP/rest/services/items?codigoItem=" + codEncoded + "&limit=1"))
                .GET()
                .header("accept", "application/json")
                .header("authorization", tokens.token)
                .build();

        HttpResponse<String> response = retryHandler.sendWithRetry(requestBuilder);

        if (response == null || response.statusCode() != 200) {
            String body = response != null ? response.body() : "sin respuesta";
            AppLogger.warn("DUX - Error al obtener producto " + codigoItem + ": " + body);
            return null;
        }

        DuxResponse duxResponse = mapper.readValue(response.body().trim(), DuxResponse.class);

        if (duxResponse.getResults() != null && !duxResponse.getResults().isEmpty()) {
            return duxResponse.getResults().getFirst();
        }

        return null;
    }

    /**
     * Obtiene el stock disponible de un producto por su código.
     * @param codigoItem Código del producto
     * @return Stock disponible o -1 si no se encuentra
     */
    public static int obtenerStockPorCodigo(String codigoItem) {
        try {
            Item item = obtenerProductoPorCodigo(codigoItem);
            if (item == null || item.getStock() == null || item.getStock().isEmpty()) {
                return -1;
            }

            // Sumar stock disponible de todas las ubicaciones
            int totalStock = 0;
            for (Stock stock : item.getStock()) {
                String stockDisp = stock.getStockDisponible();
                if (stockDisp != null && !stockDisp.isBlank()) {
                    try {
                        totalStock += (int) Double.parseDouble(stockDisp);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return totalStock;
        } catch (Exception e) {
            AppLogger.warn("DUX - Error al obtener stock de " + codigoItem + ": " + e.getMessage());
            return -1;
        }
    }

    // TOKENS
    // ---------------------------------------------------------------------------------------------------------
    public static boolean inicializar() {
        tokens = DuxApi.cargarTokens();
        return tokens != null;
    }

    private static TokensDux cargarTokens() {
        try {
            File f = TOKEN_FILE.toFile();
            if (!f.exists())
                return null;
            return mapper.readValue(f, TokensDux.class);
        } catch (Exception e) {
            AppLogger.warn("DUX - Error al cargar tokens: " + e.getMessage());
            return null;
        }
    }
}
