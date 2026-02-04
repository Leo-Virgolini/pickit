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

    /**
     * Lee la hoja COMBOS del PICKIT.xlsm.
     * Col A = SKU combo, Col C = SKU componente, Col E = cantidad por combo.
     * Retorna Map<String, List<ComboEntry>> (SKU combo -> lista de componentes).
     */
    public static Map<String, List<ComboEntry>> obtenerCombos(File pickitExcel) throws Exception {
        Map<String, List<ComboEntry>> combos = new LinkedHashMap<>();

        try (OPCPackage opcPackage = OPCPackage.open(pickitExcel, PackageAccess.READ);
             Workbook workbook = new XSSFWorkbook(opcPackage)) {

            Sheet hoja = workbook.getSheet("COMBOS");
            if (hoja == null) {
                AppLogger.warn("Excel - No se encontró la hoja 'COMBOS' en " + pickitExcel.getName());
                return combos;
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

                combos.computeIfAbsent(skuCombo, k -> new ArrayList<>())
                        .add(new ComboEntry(skuComponente, cantidad));
            }
        }

        AppLogger.info("Excel - Combos leídos: " + combos.size());
        return combos;
    }

    /**
     * Lee la hoja STOCK del PICKIT.xlsm.
     * Col A = SKU, Col L (12, index 11) = Unidad.
     * Retorna Map<String, String> (SKU -> unidad).
     */
    public static Map<String, String> obtenerUnidades(File pickitExcel) throws Exception {
        Map<String, String> unidades = new LinkedHashMap<>();

        try (OPCPackage opcPackage = OPCPackage.open(pickitExcel, PackageAccess.READ);
             Workbook workbook = new XSSFWorkbook(opcPackage)) {

            Sheet hoja = workbook.getSheet("STOCK");
            if (hoja == null) {
                AppLogger.warn("Excel - No se encontró la hoja 'STOCK' en " + pickitExcel.getName());
                return unidades;
            }

            for (int i = 3; i <= hoja.getLastRowNum(); i++) {
                Row fila = hoja.getRow(i);
                if (isEmptyRow(fila)) continue;

                Cell celdaSku = fila.getCell(0);    // Col A
                Cell celdaUnidad = fila.getCell(11); // Col L (index 11)

                if (celdaSku == null) continue;

                String sku = getCellValue(celdaSku).trim();
                if (sku.isEmpty()) continue;

                String unidad = "";
                if (celdaUnidad != null) {
                    unidad = getCellValue(celdaUnidad).trim();
                }

                unidades.put(sku, unidad);
            }
        }

        AppLogger.info("Excel - Unidades leídas: " + unidades.size());
        return unidades;
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
