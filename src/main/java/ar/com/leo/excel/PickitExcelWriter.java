package ar.com.leo.excel;

import ar.com.leo.AppLogger;
import ar.com.leo.Util;
import ar.com.leo.pickit.model.PickitItem;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class PickitExcelWriter {

    private static final String[] HEADERS = {
            "SKU", "CANT", "DESCRIPCION", "PROVEEDOR", "SECTOR", "STOCK"
    };

    /**
     * Genera el Excel Pickit con separaciones grises por cambio de unidad,
     * bold+underline para filas con cantidad > 1.
     * Font Calibri 14, centrado, bordes finos.
     */
    public static File generar(List<PickitItem> items) throws Exception {
        Path excelDir = Paths.get(Util.getJarFolder(), "Excel");
        Files.createDirectories(excelDir);

        LocalDateTime ahora = LocalDateTime.now();
        String fecha = ahora.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File outputFile = excelDir.resolve("pickit_" + fecha + ".xlsx").toFile();

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("PICKIT");

            // --- Fuentes ---
            Font fontNormal = workbook.createFont();
            fontNormal.setFontName("Calibri");
            fontNormal.setFontHeightInPoints((short) 14);

            Font fontBoldUnderline = workbook.createFont();
            fontBoldUnderline.setFontName("Calibri");
            fontBoldUnderline.setFontHeightInPoints((short) 14);
            fontBoldUnderline.setBold(true);
            fontBoldUnderline.setUnderline(Font.U_SINGLE);

            Font fontHeader = workbook.createFont();
            fontHeader.setFontName("Calibri");
            fontHeader.setFontHeightInPoints((short) 14);
            fontHeader.setBold(true);

            // --- Estilos ---
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(fontHeader);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorders(headerStyle);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle normalStyle = workbook.createCellStyle();
            normalStyle.setFont(fontNormal);
            normalStyle.setAlignment(HorizontalAlignment.CENTER);
            normalStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorders(normalStyle);

            CellStyle boldUnderlineStyle = workbook.createCellStyle();
            boldUnderlineStyle.setFont(fontBoldUnderline);
            boldUnderlineStyle.setAlignment(HorizontalAlignment.CENTER);
            boldUnderlineStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorders(boldUnderlineStyle);

            CellStyle separatorStyle = workbook.createCellStyle();
            separatorStyle.setFillForegroundColor(IndexedColors.GREY_40_PERCENT.getIndex());
            separatorStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            setBorders(separatorStyle);

            // --- Título "PICKIT KT" con fecha y hora ---
            Font fontTitle = workbook.createFont();
            fontTitle.setFontName("Calibri");
            fontTitle.setFontHeightInPoints((short) 18);
            fontTitle.setBold(true);

            CellStyle titleStyle = workbook.createCellStyle();
            titleStyle.setFont(fontTitle);
            titleStyle.setAlignment(HorizontalAlignment.CENTER);
            titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            titleStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            titleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            titleStyle.setBorderTop(BorderStyle.THICK);
            titleStyle.setBorderBottom(BorderStyle.THICK);
            titleStyle.setBorderLeft(BorderStyle.THICK);
            titleStyle.setBorderRight(BorderStyle.THICK);

            Row titleRow = sheet.createRow(0);
            String fechaHora = ahora.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = titleRow.createCell(i);
                if (i == 0) {
                    cell.setCellValue("PICKIT KT - " + fechaHora);
                }
                cell.setCellStyle(titleStyle);
            }
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, HEADERS.length - 1));

            // --- Header columnas ---
            Row headerRow = sheet.createRow(1);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            // --- Datos ---
            int rowIndex = 2;
            String lastUnidadPrefix = null;

            for (PickitItem item : items) {
                // Separador gris cuando cambian los primeros 2 caracteres de la unidad
                String currentUnidadPrefix = getUnidadPrefix(item.getUnidad());
                if (lastUnidadPrefix != null && !currentUnidadPrefix.equals(lastUnidadPrefix)) {
                    Row sepRow = sheet.createRow(rowIndex++);
                    for (int i = 0; i < HEADERS.length; i++) {
                        Cell cell = sepRow.createCell(i);
                        cell.setCellStyle(separatorStyle);
                    }
                }
                lastUnidadPrefix = currentUnidadPrefix;

                // Determinar estilo según cantidad
                boolean destacar = item.getCantidad() > 1;
                CellStyle style = destacar ? boldUnderlineStyle : normalStyle;

                Row row = sheet.createRow(rowIndex++);

                Cell cellCodigo = row.createCell(0);
                cellCodigo.setCellValue(item.getCodigo());
                cellCodigo.setCellStyle(style);

                Cell cellCantidad = row.createCell(1);
                double cant = item.getCantidad();
                if (cant == Math.floor(cant)) {
                    cellCantidad.setCellValue((int) cant);
                } else {
                    cellCantidad.setCellValue(cant);
                }
                cellCantidad.setCellStyle(style);

                Cell cellDesc = row.createCell(2);
                cellDesc.setCellValue(item.getDescripcion() != null ? item.getDescripcion() : "");
                cellDesc.setCellStyle(style);

                Cell cellProv = row.createCell(3);
                cellProv.setCellValue(item.getProveedor() != null ? item.getProveedor() : "");
                cellProv.setCellStyle(style);

                Cell cellUnidad = row.createCell(4);
                cellUnidad.setCellValue(item.getUnidad() != null ? item.getUnidad() : "");
                cellUnidad.setCellStyle(style);

                Cell cellStock = row.createCell(5);
                double stock = item.getStockDisponible();
                if (stock == Math.floor(stock)) {
                    cellStock.setCellValue((int) stock);
                } else {
                    cellStock.setCellValue(stock);
                }
                cellStock.setCellStyle(style);

            }

            // Auto-ajustar columnas
            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            // Guardar
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                workbook.write(fos);
            }
        }

        AppLogger.info("Excel - Pickit generado: " + outputFile.getAbsolutePath());
        return outputFile;
    }

    private static String getUnidadPrefix(String unidad) {
        if (unidad == null || unidad.isEmpty()) return "";
        return unidad.length() >= 2 ? unidad.substring(0, 2).toUpperCase() : unidad.toUpperCase();
    }

    private static void setBorders(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }
}
