package ar.com.leo.fx.services;

import ar.com.leo.AppLogger;
import ar.com.leo.dux.DuxApi;
import ar.com.leo.dux.model.Item;
import ar.com.leo.dux.model.Stock;
import ar.com.leo.excel.ExcelManager;
import ar.com.leo.excel.ExcelManager.ComboEntry;
import ar.com.leo.excel.PickitExcelWriter;
import ar.com.leo.ml.MercadoLibreAPI;
import ar.com.leo.nube.TiendaNubeApi;
import ar.com.leo.pickit.model.PickitItem;
import ar.com.leo.pickit.model.Venta;

import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PickitGenerator {

    private static final ExecutorService executor = Executors.newFixedThreadPool(4);

    public static void shutdownExecutors() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Genera el Excel Pickit ejecutando todos los pasos del flujo.
     *
     * @param pickitExcel Archivo PICKIT.xlsm con hojas COMBOS y STOCK
     * @return Archivo Excel generado
     */
    public static File generarPickit(File pickitExcel) throws Exception {

        // Paso 1: Inicializar ML API + obtener userId
        AppLogger.info("PICKIT - Paso 1: Inicializando MercadoLibre API...");
        if (!MercadoLibreAPI.inicializar()) {
            throw new RuntimeException("No se pudieron inicializar los tokens de MercadoLibre.");
        }
        final String userId = MercadoLibreAPI.getUserId();

        final List<Venta> todasLasVentas = Collections.synchronizedList(new ArrayList<>());

        // Inicializar Tienda Nube antes de lanzar los futures paralelos
        boolean nubeDisponible = TiendaNubeApi.inicializar();
        if (!nubeDisponible) {
            AppLogger.warn("PICKIT - No se pudo inicializar Tienda Nube. Se omitirán ventas de Nube.");
        }

        // Pasos 2-5: Obtener ventas de todas las fuentes en paralelo
        AppLogger.info("PICKIT - Pasos 2-5: Obteniendo ventas de todas las fuentes...");

        var futureMLPrint = executor.submit(() -> {
            AppLogger.info("PICKIT - Paso 2: Obteniendo ventas ML ready_to_print...");
            List<Venta> ventas = MercadoLibreAPI.obtenerVentasReadyToPrint(userId);
            todasLasVentas.addAll(ventas);
            return ventas.size();
        });

        var futureMLAgreement = executor.submit(() -> {
            AppLogger.info("PICKIT - Paso 3: Obteniendo ventas ML acuerdo con el vendedor...");
            List<Venta> ventas = MercadoLibreAPI.obtenerVentasSellerAgreement(userId);
            todasLasVentas.addAll(ventas);
            return ventas.size();
        });

        var futureNube = executor.submit(() -> {
            AppLogger.info("PICKIT - Paso 4: Obteniendo ventas KT HOGAR (Tienda Nube)...");
            if (!nubeDisponible) return 0;
            List<Venta> ventas = TiendaNubeApi.obtenerVentasHogar();
            todasLasVentas.addAll(ventas);
            return ventas.size();
        });

        var futureGastro = executor.submit(() -> {
            AppLogger.info("PICKIT - Paso 5: Obteniendo ventas KT GASTRO (Tienda Nube)...");
            if (!nubeDisponible) return 0;
            List<Venta> ventas = TiendaNubeApi.obtenerVentasGastro();
            todasLasVentas.addAll(ventas);
            return ventas.size();
        });

        // Esperar a que terminen todos
        int countMLPrint = futureMLPrint.get();
        int countMLAgreement = futureMLAgreement.get();
        int countNube = futureNube.get();
        int countGastro = futureGastro.get();

        // Paso 6: Consolidar ventas
        AppLogger.info("PICKIT - Paso 6: Consolidando ventas...");
        AppLogger.info(String.format("  ML ready_to_print: %d | ML acuerdo: %d | KT HOGAR: %d | KT GASTRO: %d | Total: %d",
                countMLPrint, countMLAgreement, countNube, countGastro, todasLasVentas.size()));

        if (todasLasVentas.isEmpty()) {
            AppLogger.info("PICKIT - No se encontraron ventas para procesar.");
            throw new RuntimeException("No se encontraron ventas para procesar. Verificar conexión a las APIs.");
        }

        // Paso 7: Limpiar SKUs (trim, texto antes del espacio, asegurar numérico)
        AppLogger.info("PICKIT - Paso 7: Limpiando SKUs...");
        for (Venta venta : todasLasVentas) {
            String sku = venta.getSku().trim();
            // Tomar texto antes del primer espacio
            int spaceIndex = sku.indexOf(' ');
            if (spaceIndex > 0) {
                sku = sku.substring(0, spaceIndex);
            }
            // Quitar caracteres no numéricos al inicio (por si tiene prefijos)
            sku = sku.replaceAll("^[^0-9]*", "");
            venta.setSku(sku);
        }

        // Remover ventas con SKU vacío después de limpieza
        todasLasVentas.removeIf(v -> v.getSku().isBlank());

        // Log cantidad de ventas por canal
        Map<String, Integer> ventasPorCanal = new LinkedHashMap<>();
        for (Venta v : todasLasVentas) {
            ventasPorCanal.merge(v.getOrigen(), 1, Integer::sum);
        }
        ventasPorCanal.forEach((canal, count) ->
                AppLogger.info("PICKIT - Ventas " + canal + ": " + count));

        // Paso 8: Leer combos de PICKIT.xlsm y expandir
        AppLogger.info("PICKIT - Paso 8: Leyendo combos y expandiendo...");
        Map<String, List<ComboEntry>> combos = ExcelManager.obtenerCombos(pickitExcel);

        List<Venta> ventasExpandidas = new ArrayList<>();
        for (Venta venta : todasLasVentas) {
            List<ComboEntry> componentes = combos.get(venta.getSku());
            if (componentes != null && !componentes.isEmpty()) {
                // Es un combo: reemplazar por componentes * cantidad
                for (ComboEntry comp : componentes) {
                    ventasExpandidas.add(new Venta(
                            comp.skuComponente(),
                            venta.getCantidad() * comp.cantidad(),
                            venta.getOrigen()
                    ));
                }
                AppLogger.info("PICKIT - Combo expandido: " + venta.getSku() + " → " + componentes.size() + " componentes");
            } else {
                ventasExpandidas.add(venta);
            }
        }

        // Paso 9: Agregar por SKU (sumar cantidades)
        AppLogger.info("PICKIT - Paso 9: Agrupando por SKU...");
        Map<String, Double> skuCantidad = new LinkedHashMap<>();
        for (Venta venta : ventasExpandidas) {
            skuCantidad.merge(venta.getSku(), venta.getCantidad(), Double::sum);
        }
        AppLogger.info("PICKIT - SKUs únicos: " + skuCantidad.size());

        // Paso 10: Enriquecer con DUX API (solo los SKUs necesarios)
        AppLogger.info("PICKIT - Paso 10: Enriqueciendo con DUX API (" + skuCantidad.size() + " SKUs)...");
        Map<String, Item> duxMap = new HashMap<>();
        if (DuxApi.inicializar()) {
            int i = 0;
            for (String sku : skuCantidad.keySet()) {
                i++;
                try {
                    Item item = DuxApi.obtenerProductoPorCodigo(sku);
                    if (item != null) {
                        duxMap.put(sku, item);
                    } else {
                        AppLogger.warn("PICKIT - SKU no encontrado en DUX: " + sku);
                    }
                    AppLogger.info(String.format("DUX - Consultado %d/%d: %s", i, skuCantidad.size(), sku));
                } catch (Exception e) {
                    AppLogger.warn("DUX - Error al obtener SKU " + sku + ": " + e.getMessage());
                }
            }
        } else {
            AppLogger.warn("PICKIT - No se pudieron inicializar los tokens de DUX. Se continuará sin datos de DUX.");
        }

        // Paso 11: Leer unidades de PICKIT.xlsm
        AppLogger.info("PICKIT - Paso 11: Leyendo unidades de PICKIT.xlsm...");
        Map<String, String> unidades = ExcelManager.obtenerUnidades(pickitExcel);

        // Construir lista de PickitItems
        List<PickitItem> pickitItems = new ArrayList<>();
        for (Map.Entry<String, Double> entry : skuCantidad.entrySet()) {
            String sku = entry.getKey();
            double cantidad = entry.getValue();

            Item duxItem = duxMap.get(sku);

            String descripcion = "";
            String proveedor = "";
            double stockDisponible = 0;
            String subRubro = "";

            if (duxItem != null) {
                descripcion = duxItem.getItem() != null ? duxItem.getItem() : "";
                if (duxItem.getProveedor() != null && duxItem.getProveedor().getProveedor() != null) {
                    proveedor = duxItem.getProveedor().getProveedor();
                }
                if (duxItem.getSubRubro() != null && duxItem.getSubRubro().getNombre() != null) {
                    subRubro = duxItem.getSubRubro().getNombre();
                }
                if (duxItem.getStock() != null && !duxItem.getStock().isEmpty()) {
                    // Sumar stock disponible de todos los depósitos
                    for (Stock stock : duxItem.getStock()) {
                        if (stock.getStockDisponible() != null) {
                            try {
                                stockDisponible += Double.parseDouble(stock.getStockDisponible().replace(",", "."));
                            } catch (NumberFormatException e) {
                                // ignorar
                            }
                        }
                    }
                }
            }

            String unidad = unidades.getOrDefault(sku, "");

            pickitItems.add(new PickitItem(sku, cantidad, descripcion, proveedor, unidad, stockDisponible, subRubro));
        }

        // Paso 12: Ordenar por unidad → proveedor → subRubro → descripcion
        AppLogger.info("PICKIT - Paso 12: Ordenando resultados...");
        pickitItems.sort(Comparator
                .comparing((PickitItem i) -> i.getUnidad() != null ? i.getUnidad() : "")
                .thenComparing(i -> i.getProveedor() != null ? i.getProveedor() : "")
                .thenComparing(i -> i.getSubRubro() != null ? i.getSubRubro() : "")
                .thenComparing(i -> i.getDescripcion() != null ? i.getDescripcion() : ""));

        // Paso 13: Generar Excel
        AppLogger.info("PICKIT - Paso 13: Generando Excel Pickit...");
        File resultado = PickitExcelWriter.generar(pickitItems);
        AppLogger.info("PICKIT - Proceso completado. " + pickitItems.size() + " items generados. Archivo: " + resultado.getAbsolutePath());

        return resultado;
    }
}
