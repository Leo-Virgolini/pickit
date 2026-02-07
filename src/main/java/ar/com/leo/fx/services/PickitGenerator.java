package ar.com.leo.fx.services;

import ar.com.leo.AppLogger;
import ar.com.leo.excel.ExcelManager;
import ar.com.leo.excel.ExcelManager.ComboEntry;
import ar.com.leo.excel.ExcelManager.ProductoStock;
import ar.com.leo.excel.PickitExcelWriter;
import ar.com.leo.fx.model.ProductoManual;
import ar.com.leo.ml.MercadoLibreAPI;
import ar.com.leo.ml.MercadoLibreAPI.MLOrderResult;
import ar.com.leo.nube.TiendaNubeApi;
import ar.com.leo.pickit.model.*;

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
     * @param stockExcel        Archivo Stock.xlsx con datos de productos
     * @param combosExcel       Archivo Combos.xlsx con datos de combos
     * @param productosManuales Lista de productos agregados manualmente
     * @return Archivo Excel generado
     */
    public static File generarPickit(File stockExcel, File combosExcel, List<ProductoManual> productosManuales) throws Exception {

        // Paso 1: Inicializar ML API + obtener userId
        AppLogger.info("PICKIT - Paso 1: Inicializando MercadoLibre API...");
        if (!MercadoLibreAPI.inicializar()) {
            throw new RuntimeException("No se pudieron inicializar los tokens de MercadoLibre.");
        }
        final String userId = MercadoLibreAPI.getUserId();

        final List<Venta> todasLasVentas = Collections.synchronizedList(new ArrayList<>());
        final List<OrdenML> todasLasOrdenesML = Collections.synchronizedList(new ArrayList<>());

        // Inicializar Tienda Nube antes de lanzar los futures paralelos
        if (!TiendaNubeApi.inicializar()) {
            throw new RuntimeException("No se pudieron inicializar las credenciales de Tienda Nube.");
        }

        // Pasos 2-5: Obtener ventas de todas las fuentes en paralelo
        AppLogger.info("PICKIT - Pasos 2-5: Obteniendo ventas de todas las fuentes...");

        var futureMLPrint = executor.submit(() -> {
            AppLogger.info("PICKIT - Paso 2: Obteniendo ventas ML ready_to_print...");
            MLOrderResult result = MercadoLibreAPI.obtenerVentasReadyToPrint(userId);
            todasLasVentas.addAll(result.ventas());
            todasLasOrdenesML.addAll(result.ordenes());
            return result.ventas().size();
        });

        var futureMLAgreement = executor.submit(() -> {
            AppLogger.info("PICKIT - Paso 3: Obteniendo ventas ML acuerdo con el vendedor...");
            MLOrderResult result = MercadoLibreAPI.obtenerVentasSellerAgreement(userId);
            todasLasVentas.addAll(result.ventas());
            todasLasOrdenesML.addAll(result.ordenes());
            return result.ventas().size();
        });

        var futureNube = executor.submit(() -> {
            AppLogger.info("PICKIT - Paso 4: Obteniendo ventas KT HOGAR (Tienda Nube)...");
            List<Venta> ventas = TiendaNubeApi.obtenerVentasHogar();
            todasLasVentas.addAll(ventas);
            return ventas.size();
        });

        var futureGastro = executor.submit(() -> {
            AppLogger.info("PICKIT - Paso 5: Obteniendo ventas KT GASTRO (Tienda Nube)...");
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
        AppLogger.info(String.format("ML ready_to_print: %d | ML acuerdo: %d | KT HOGAR: %d | KT GASTRO: %d | Total: %d",
                countMLPrint, countMLAgreement, countNube, countGastro, todasLasVentas.size()));

        // Agregar productos manuales
        if (productosManuales != null && !productosManuales.isEmpty()) {
            for (ProductoManual pm : productosManuales) {
                todasLasVentas.add(new Venta(pm.getSku(), pm.getCantidad(), "MANUAL"));
            }
            AppLogger.info("PICKIT - Productos manuales agregados: " + productosManuales.size());
        }

        if (todasLasVentas.isEmpty()) {
            AppLogger.info("PICKIT - No se encontraron ventas para procesar.");
            throw new RuntimeException("No se encontraron ventas para procesar. Verificar conexión a las APIs.");
        }

        // Paso 7: Limpiar SKUs (trim, texto antes del espacio, asegurar numérico)
        // Los SKUs que empiezan con prefijos de error se mantienen como están
        AppLogger.info("PICKIT - Paso 7: Limpiando SKUs...");
        for (Venta venta : todasLasVentas) {
            String sku = venta.getSku().trim();
            // Mantener SKUs marcados como error
            if (esSkuConError(sku)) {
                continue;
            }
            // Tomar texto antes del primer espacio
            int spaceIndex = sku.indexOf(' ');
            if (spaceIndex > 0) {
                sku = sku.substring(0, spaceIndex);
            }
            // Quitar caracteres no numéricos al inicio (por si tiene prefijos)
            sku = sku.replaceAll("^[^0-9]*|[^0-9]*$", "");
            venta.setSku(sku);
        }

        // Marcar ventas con SKU vacío o no numérico (excepto los ya marcados como error)
        for (Venta v : todasLasVentas) {
            String sku = v.getSku();
            if (esSkuConError(sku)) {
                continue;
            }
            if (sku.isBlank() || !sku.matches("\\d+")) {
                AppLogger.warn("PICKIT - SKU inválido: '" + sku + "'");
                v.setSku("SKU INVALIDO: " + sku);
            }
        }

        // Log cantidad de ventas por canal
        Map<String, Integer> ventasPorCanal = new LinkedHashMap<>();
        for (Venta v : todasLasVentas) {
            ventasPorCanal.merge(v.getOrigen(), 1, Integer::sum);
        }
        ventasPorCanal.forEach((canal, count) ->
                AppLogger.info("PICKIT - Ventas " + canal + ": " + count));

        // Paso 8: Leer combos de Combos.xlsx y expandir
        AppLogger.info("PICKIT - Paso 8: Leyendo combos y expandiendo...");
        Map<String, List<ComboEntry>> combos = ExcelManager.obtenerCombos(combosExcel);

        List<Venta> ventasExpandidas = new ArrayList<>();
        for (Venta venta : todasLasVentas) {
            // No expandir ventas con error
            if (esSkuConError(venta.getSku())) {
                ventasExpandidas.add(venta);
                continue;
            }

            List<ComboEntry> componentes = combos.get(venta.getSku());
            if (componentes != null && !componentes.isEmpty()) {
                // Es un combo: reemplazar por componentes * cantidad
                for (ComboEntry comp : componentes) {
                    double cantidadExpandida = venta.getCantidad() * comp.cantidad();
                    if (cantidadExpandida <= 0) {
                        AppLogger.warn("PICKIT - Componente de combo con cantidad inválida: " + comp.skuComponente());
                        ventasExpandidas.add(new Venta(
                                "COMBO INVALIDO: " + comp.skuComponente(),
                                cantidadExpandida,
                                venta.getOrigen()
                        ));
                    } else {
                        ventasExpandidas.add(new Venta(
                                comp.skuComponente(),
                                cantidadExpandida,
                                venta.getOrigen()
                        ));
                    }
                }

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

        // Paso 10: Leer datos de productos de Stock.xlsx (incluye stock en columna H)
        AppLogger.info("PICKIT - Paso 10: Leyendo datos de productos de Stock.xlsx...");
        Map<String, ProductoStock> productosStock = ExcelManager.obtenerProductosStock(stockExcel);

        // Construir lista de PickitItems
        List<PickitItem> pickitItems = new ArrayList<>();
        int skusNoEncontrados = 0;
        int skusStockInsuficiente = 0;
        int skusConError = 0;
        for (Map.Entry<String, Double> entry : skuCantidad.entrySet()) {
            String sku = entry.getKey();
            double cantidad = entry.getValue();

            ProductoStock producto = productosStock.get(sku);

            String descripcion = "";
            String proveedor = "";
            String subRubro = "";
            String unidad = "";
            int stockDisponible = 0;

            // Si es "SIN SKU: descripcion", extraer la descripción
            if (sku.startsWith("SIN SKU: ")) {
                descripcion = sku.substring("SIN SKU: ".length());
                sku = "SIN SKU";
                skusConError++;
            } else if (esSkuConError(sku)) {
                skusConError++;
            } else if (producto != null) {
                descripcion = producto.producto();
                proveedor = producto.proveedor();
                subRubro = producto.subRubro();
                unidad = producto.unidad();
                stockDisponible = producto.stock();
                if (stockDisponible < cantidad) {
                    AppLogger.warn("PICKIT - SKU " + sku + " stock insuficiente (pedido: " + (int) cantidad + ", disponible: " + stockDisponible + ")");
                    skusStockInsuficiente++;
                }
            } else {
                AppLogger.warn("PICKIT - SKU " + sku + " no encontrado en Stock.xlsx");
                skusNoEncontrados++;
            }

            pickitItems.add(new PickitItem(sku, cantidad, descripcion, proveedor, unidad, stockDisponible, subRubro));
        }

        // Paso 11: Ordenar por unidad → proveedor → subRubro → descripcion
        AppLogger.info("PICKIT - Paso 11: Ordenando resultados...");
        pickitItems.sort(Comparator
                .comparing((PickitItem i) -> i.getUnidad() != null ? i.getUnidad() : "")
                .thenComparing(i -> i.getProveedor() != null ? i.getProveedor() : "")
                .thenComparing(i -> i.getSubRubro() != null ? i.getSubRubro() : "")
                .thenComparing(i -> i.getDescripcion() != null ? i.getDescripcion() : ""));

        // Construir CarrosOrden a partir de las ordenes ML, agrupando por ventaId (pack_id o order_id)
        AppLogger.info("PICKIT - Construyendo datos de CARROS...");
        todasLasOrdenesML.sort(Comparator
                .comparing((OrdenML o) -> o.getFechaCreacion() != null ? o.getFechaCreacion() : java.time.OffsetDateTime.MAX)
                .thenComparingLong(OrdenML::getOrderId));

        // Agrupar ordenes por ventaId (pack_id si existe, sino order_id)
        Map<Long, List<OrdenML>> ordenesPorVenta = new LinkedHashMap<>();
        for (OrdenML orden : todasLasOrdenesML) {
            ordenesPorVenta.computeIfAbsent(orden.getVentaId(), k -> new ArrayList<>()).add(orden);
        }

        List<CarrosOrden> carrosOrdenes = new ArrayList<>();
        int carroIndex = 0;
        for (Map.Entry<Long, List<OrdenML>> entry : ordenesPorVenta.entrySet()) {
            List<OrdenML> ordenesGrupo = entry.getValue();
            String letra = generarLetraCarro(carroIndex++);
            String numeroVenta = ordenesGrupo.getFirst().getNumeroVenta();
            java.time.OffsetDateTime fechaCreacion = ordenesGrupo.getFirst().getFechaCreacion();

            List<CarrosItem> carrosItems = new ArrayList<>();
            Set<String> skusDistintos = new HashSet<>();
            for (OrdenML orden : ordenesGrupo) {
                for (Venta v : orden.getItems()) {
                    String skuItem = v.getSku();
                    String descripcion = "";
                    String sector = "";

                    // Si es "SIN SKU: descripcion", extraer la descripción
                    if (skuItem.startsWith("SIN SKU: ")) {
                        descripcion = skuItem.substring("SIN SKU: ".length());
                        skuItem = "SIN SKU";
                    } else {
                        // Mantener SKUs con error tal como están
                        boolean esError = esSkuConError(skuItem);
                        if (!esError && (skuItem.isBlank() || !skuItem.matches("\\d+"))) {
                            AppLogger.warn("CARROS - SKU inválido en venta " + numeroVenta + ": '" + skuItem + "'");
                            skuItem = "SKU INVALIDO: " + skuItem;
                            esError = true;
                        }
                        if (!esError) {
                            ProductoStock producto = productosStock.get(skuItem);
                            if (producto != null) {
                                descripcion = producto.producto();
                                sector = producto.unidad();
                            }
                        }
                    }
                    skusDistintos.add(skuItem);
                    carrosItems.add(new CarrosItem(skuItem, v.getCantidad(), descripcion, sector));
                }
            }
            // Solo agregar si tiene 2+ SKUs distintos
            if (skusDistintos.size() >= 2) {
                carrosOrdenes.add(new CarrosOrden(numeroVenta, fechaCreacion, letra, carrosItems));
            } else {
                carroIndex--; // Revertir incremento de letra
            }
        }
        AppLogger.info("PICKIT - Ordenes CARROS: " + carrosOrdenes.size());

        // Paso 12: Generar Excel
        AppLogger.info("PICKIT - Paso 12: Generando Excel Pickit...");
        File resultado = PickitExcelWriter.generar(pickitItems, carrosOrdenes);

        // Resumen de problemas
        if (skusNoEncontrados > 0 || skusStockInsuficiente > 0 || skusConError > 0) {
            AppLogger.warn("PICKIT - ========== RESUMEN ==========");
            if (skusNoEncontrados > 0)
                AppLogger.warn("PICKIT -   SKUs no encontrados en Stock: " + skusNoEncontrados);
            if (skusStockInsuficiente > 0)
                AppLogger.warn("PICKIT -   SKUs con stock insuficiente: " + skusStockInsuficiente);
            if (skusConError > 0)
                AppLogger.warn("PICKIT -   SKUs con error: " + skusConError);
            AppLogger.warn("PICKIT - ==============================");
        }

        AppLogger.success("PICKIT - Proceso completado. " + pickitItems.size() + " items generados. Archivo: " + resultado.getAbsolutePath());

        return resultado;
    }

    /**
     * Genera letra de carro estilo Excel: A-Z, AA-AZ, BA-BZ, ...
     */
    private static String generarLetraCarro(int index) {
        StringBuilder sb = new StringBuilder();
        index++;
        while (index > 0) {
            index--;
            sb.insert(0, (char) ('A' + index % 26));
            index /= 26;
        }
        return sb.toString();
    }

    /**
     * Verifica si un SKU tiene un prefijo de error.
     */
    public static boolean esSkuConError(String sku) {
        return sku.equals("SIN SKU") ||
                sku.startsWith("SIN SKU:") ||
                sku.startsWith("SKU INVALIDO:") ||
                sku.startsWith("CANT INVALIDA:") ||
                sku.startsWith("COMBO INVALIDO:");
    }
}
