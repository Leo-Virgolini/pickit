package ar.com.leo.excel;

import ar.com.leo.AppLogger;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.util.*;

public class ExcelManager {

    public record ComboEntry(String skuComponente, double cantidad) {}

    public record ProductoStock(String producto, String subRubro, String unidad, String proveedor, int stock) {}

    /**
     * Lee el archivo Combos (xls o xlsx).
     * Col A = SKU combo, Col C = SKU componente, Col E = cantidad por combo.
     * Retorna Map<String, List<ComboEntry>> (SKU combo -> lista de componentes).
     */
    public static Map<String, List<ComboEntry>> obtenerCombos(File combosExcel) throws Exception {
        Map<String, List<ComboEntry>> combos = new LinkedHashMap<>();

        try (Workbook workbook = WorkbookFactory.create(combosExcel)) {

            Sheet hoja = workbook.getSheetAt(0);
            if (hoja == null) {
                throw new Exception("No se encontró ninguna hoja en " + combosExcel.getName());
            }

            for (int i = 3; i <= hoja.getLastRowNum(); i++) {
                Row fila = hoja.getRow(i);
                if (isEmptyRow(fila)) continue;

                Cell celdaSkuCombo = fila.getCell(0);       // Col A
                Cell celdaSkuComponente = fila.getCell(2);   // Col C
                Cell celdaCantidad = fila.getCell(4);        // Col E

                if (celdaSkuCombo == null || celdaSkuComponente == null || celdaCantidad == null) continue;

                String skuCombo = getCellValue(celdaSkuCombo).trim();
                String skuComponente = getCellValue(celdaSkuComponente).trim();
                String cantidadStr = getCellValue(celdaCantidad).trim();

                if (skuCombo.isEmpty() || skuComponente.isEmpty() || cantidadStr.isEmpty()) continue;

                double cantidad;
                try {
                    cantidad = Double.parseDouble(cantidadStr);
                } catch (NumberFormatException e) {
                    AppLogger.warn("Excel - Error al parsear cantidad combo en fila " + (i + 1) + ": " + cantidadStr);
                    continue;
                }

                if (cantidad <= 0) {
                    AppLogger.warn("Excel - Cantidad inválida en combo fila " + (i + 1) + ": " + cantidadStr + " (SKU: " + skuComponente + ")");
                    continue;
                }

                combos.computeIfAbsent(skuCombo, k -> new ArrayList<>())
                        .add(new ComboEntry(skuComponente, cantidad));
            }
        }

        AppLogger.info("Excel - Combos leídos: " + combos.size());
        return combos;
    }

    /**
     * Lee el archivo Stock.xlsx.
     * Col A = SKU, Col B = Producto, Col G = Sub Rubro, Col H = Stock, Col L = Unidad, Col R = Proveedor.
     * Retorna Map<String, ProductoStock> (SKU -> datos del producto).
     */
    public static Map<String, ProductoStock> obtenerProductosStock(File stockExcel) throws Exception {
        Map<String, ProductoStock> productos = new LinkedHashMap<>();

        try (OPCPackage opcPackage = OPCPackage.open(stockExcel, PackageAccess.READ);
             Workbook workbook = new XSSFWorkbook(opcPackage)) {

            Sheet hoja = workbook.getSheetAt(0);
            if (hoja == null) {
                throw new Exception("No se encontró ninguna hoja en " + stockExcel.getName());
            }

            for (int i = 3; i <= hoja.getLastRowNum(); i++) {
                Row fila = hoja.getRow(i);
                if (isEmptyRow(fila)) continue;

                Cell celdaSku = fila.getCell(0);         // Col A
                Cell celdaProducto = fila.getCell(1);    // Col B
                Cell celdaSubRubro = fila.getCell(6);    // Col G
                Cell celdaStock = fila.getCell(7);       // Col H
                Cell celdaUnidad = fila.getCell(11);     // Col L
                Cell celdaProveedor = fila.getCell(17);  // Col R

                if (celdaSku == null) continue;

                String sku = getCellValue(celdaSku).trim();
                if (sku.isEmpty()) continue;

                String producto = celdaProducto != null ? getCellValue(celdaProducto).trim() : "";
                String subRubro = celdaSubRubro != null ? getCellValue(celdaSubRubro).trim() : "";
                String unidad = celdaUnidad != null ? getCellValue(celdaUnidad).trim() : "";
                String proveedor = celdaProveedor != null ? getCellValue(celdaProveedor).trim() : "";

                int stock = 0;
                if (celdaStock != null) {
                    String stockStr = getCellValue(celdaStock).trim();
                    if (!stockStr.isEmpty()) {
                        try {
                            stock = (int) Double.parseDouble(stockStr);
                        } catch (NumberFormatException ignored) {}
                    }
                }

                productos.put(sku, new ProductoStock(producto, subRubro, unidad, proveedor, stock));
            }
        }

        AppLogger.info("Excel - Productos leídos: " + productos.size());
        return productos;
    }

    private static boolean isEmptyRow(Row row) {
        if (row == null) {
            return true;
        }
        for (int i = row.getFirstCellNum(); i < row.getLastCellNum(); i++) {
            final Cell cell = row.getCell(i);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                if (cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank()) {
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    private static String getCellValue(Cell cell) throws Exception {
        if (cell == null) {
            return "";
        }

        final CellType cellType = cell.getCellType();
        switch (cellType) {
            case STRING:
                return cell.getStringCellValue().trim();

            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    double num = cell.getNumericCellValue();
                    if (num == Math.floor(num)) {
                        return String.valueOf((long) num);
                    } else {
                        return String.valueOf(num);
                    }
                }

            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());

            case FORMULA:
                switch (cell.getCachedFormulaResultType()) {
                    case STRING:
                        return cell.getStringCellValue().trim();
                    case NUMERIC:
                        if (DateUtil.isCellDateFormatted(cell)) {
                            return cell.getDateCellValue().toString();
                        } else {
                            double num = cell.getNumericCellValue();
                            if (num == Math.floor(num)) {
                                return String.valueOf((long) num);
                            } else {
                                return String.valueOf(num);
                            }
                        }
                    case BOOLEAN:
                        return String.valueOf(cell.getBooleanCellValue());
                    case ERROR:
                        AppLogger.warn("Excel - Fórmula con error en celda " + cell.getAddress());
                        return "0";
                    default:
                        return "";
                }

            case ERROR:
                throw new Exception("Excel - Error en la celda fila: " + (cell.getAddress().getRow() + 1)
                        + " columna: " + (cell.getAddress().getColumn() + 1));

            case BLANK:
            default:
                return "";
        }
    }
}
