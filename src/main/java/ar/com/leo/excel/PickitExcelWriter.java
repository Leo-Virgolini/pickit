package ar.com.leo.excel;

import ar.com.leo.AppLogger;
import ar.com.leo.Util;
import ar.com.leo.pickit.model.CarrosItem;
import ar.com.leo.pickit.model.CarrosOrden;
import ar.com.leo.pickit.model.PickitItem;

import static ar.com.leo.fx.services.PickitGenerator.esSkuConError;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.RegionUtil;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
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
    public static File generar(List<PickitItem> items, List<CarrosOrden> carrosOrdenes) throws Exception {
        Path excelDir = Paths.get(Util.getJarFolder(), "Excel");
        Files.createDirectories(excelDir);

        LocalDateTime ahora = LocalDateTime.now();
        String fecha = ahora.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        File outputFile = excelDir.resolve("PICKIT_" + fecha + ".xlsx").toFile();

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

            // --- Estilo error (fondo rojo claro) ---
            Font fontError = workbook.createFont();
            fontError.setFontName("Calibri");
            fontError.setFontHeightInPoints((short) 14);
            fontError.setBold(true);

            CellStyle errorStyle = workbook.createCellStyle();
            errorStyle.setFont(fontError);
            errorStyle.setAlignment(HorizontalAlignment.CENTER);
            errorStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorders(errorStyle);
            errorStyle.setFillForegroundColor(IndexedColors.CORAL.getIndex());
            errorStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // --- Estilo advertencia (fondo amarillo) ---
            CellStyle warningStyle = workbook.createCellStyle();
            warningStyle.setFont(fontNormal);
            warningStyle.setAlignment(HorizontalAlignment.CENTER);
            warningStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorders(warningStyle);
            warningStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            warningStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // --- Estilo stock insuficiente (fondo naranja) ---
            CellStyle stockBajoStyle = workbook.createCellStyle();
            stockBajoStyle.setFont(fontNormal);
            stockBajoStyle.setAlignment(HorizontalAlignment.CENTER);
            stockBajoStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorders(stockBajoStyle);
            stockBajoStyle.setFillForegroundColor(IndexedColors.LIGHT_ORANGE.getIndex());
            stockBajoStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

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

                // Determinar estilo según error, advertencia o cantidad
                String codigo = item.getCodigo();
                boolean esError = esSkuConError(codigo);
                String descripcion = item.getDescripcion() != null ? item.getDescripcion() : "";
                String unidad = item.getUnidad() != null ? item.getUnidad() : "";
                boolean esAdvertencia = !esError && (descripcion.isBlank() || unidad.isBlank());
                boolean destacar = item.getCantidad() > 1;
                CellStyle style;
                if (esError) {
                    style = errorStyle;
                } else if (esAdvertencia) {
                    style = warningStyle;
                } else if (destacar) {
                    style = boldUnderlineStyle;
                } else {
                    style = normalStyle;
                }

                Row row = sheet.createRow(rowIndex++);

                Cell cellCodigo = row.createCell(0);
                cellCodigo.setCellValue(codigo);
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
                cellDesc.setCellValue(descripcion);
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
                boolean stockInsuficiente = !esError && !esAdvertencia && stock < item.getCantidad();
                cellStock.setCellStyle(stockInsuficiente ? stockBajoStyle : style);

            }

            // Aplicar borde grueso exterior a toda la tabla
            CellRangeAddress tablaCompleta = new CellRangeAddress(0, rowIndex - 1, 0, HEADERS.length - 1);
            RegionUtil.setBorderTop(BorderStyle.THICK, tablaCompleta, sheet);
            RegionUtil.setBorderBottom(BorderStyle.THICK, tablaCompleta, sheet);
            RegionUtil.setBorderLeft(BorderStyle.THICK, tablaCompleta, sheet);
            RegionUtil.setBorderRight(BorderStyle.THICK, tablaCompleta, sheet);

            // Auto-ajustar columnas
            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            // Generar hoja CARROS
            generarHojaCarros(workbook, carrosOrdenes, ahora);

            // Guardar
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                workbook.write(fos);
            }
        }

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

    private static final String[] CARROS_HEADERS = {
            "# de venta", "Unidades", "SKU", "Producto", "Sector", "Carro"
    };

    private static void generarHojaCarros(XSSFWorkbook workbook, List<CarrosOrden> carrosOrdenes, LocalDateTime ahora) {
        Sheet sheet = workbook.createSheet("CARROS");

        // --- Fuentes ---
        Font fontNormal = workbook.createFont();
        fontNormal.setFontName("Calibri");
        fontNormal.setFontHeightInPoints((short) 11);

        Font fontBoldUnderline = workbook.createFont();
        fontBoldUnderline.setFontName("Calibri");
        fontBoldUnderline.setFontHeightInPoints((short) 11);
        fontBoldUnderline.setBold(true);
        fontBoldUnderline.setUnderline(Font.U_SINGLE);

        Font fontHeader = workbook.createFont();
        fontHeader.setFontName("Calibri");
        fontHeader.setFontHeightInPoints((short) 13);
        fontHeader.setBold(true);

        // --- Color verde para header ---
        byte[] greenRgb = {(byte) 51, (byte) 153, (byte) 51};
        XSSFColor greenColor = new XSSFColor(greenRgb, null);

        // --- Estilos ---
        XSSFCellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFont(fontHeader);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        headerStyle.setBorderTop(BorderStyle.THICK);
        headerStyle.setBorderBottom(BorderStyle.THICK);
        headerStyle.setBorderLeft(BorderStyle.THICK);
        headerStyle.setBorderRight(BorderStyle.THICK);
        headerStyle.setFillForegroundColor(greenColor);
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        Font fontBold = workbook.createFont();
        fontBold.setFontName("Calibri");
        fontBold.setFontHeightInPoints((short) 11);
        fontBold.setBold(true);

        // Estilo fila gris (orden) - bold sin underline
        XSSFCellStyle orderStyle = workbook.createCellStyle();
        orderStyle.setFont(fontBold);
        orderStyle.setAlignment(HorizontalAlignment.CENTER);
        orderStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(orderStyle);
        orderStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        orderStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Fondo gris claro para filas de items
        byte[] lightGreyRgb = {(byte) 243, (byte) 243, (byte) 243};
        XSSFColor lightGreyColor = new XSSFColor(lightGreyRgb, null);

        // Estilo fila item normal
        XSSFCellStyle itemStyle = workbook.createCellStyle();
        itemStyle.setFont(fontNormal);
        itemStyle.setAlignment(HorizontalAlignment.CENTER);
        itemStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(itemStyle);
        itemStyle.setFillForegroundColor(lightGreyColor);
        itemStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Estilo fila item bold+underline (cant > 1)
        XSSFCellStyle itemBoldStyle = workbook.createCellStyle();
        itemBoldStyle.setFont(fontBoldUnderline);
        itemBoldStyle.setAlignment(HorizontalAlignment.CENTER);
        itemBoldStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        itemBoldStyle.setFillForegroundColor(lightGreyColor);
        itemBoldStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorders(itemBoldStyle);

        // Estilo fila item error (fondo rojo)
        XSSFCellStyle itemErrorStyle = workbook.createCellStyle();
        itemErrorStyle.setFont(fontBold);
        itemErrorStyle.setAlignment(HorizontalAlignment.CENTER);
        itemErrorStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        itemErrorStyle.setFillForegroundColor(IndexedColors.CORAL.getIndex());
        itemErrorStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorders(itemErrorStyle);

        // Estilo fila item advertencia (fondo amarillo)
        XSSFCellStyle itemWarningStyle = workbook.createCellStyle();
        itemWarningStyle.setFont(fontNormal);
        itemWarningStyle.setAlignment(HorizontalAlignment.CENTER);
        itemWarningStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        itemWarningStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        itemWarningStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorders(itemWarningStyle);

        // --- Titulo ---
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
        for (int i = 0; i < CARROS_HEADERS.length; i++) {
            Cell cell = titleRow.createCell(i);
            if (i == 0) {
                cell.setCellValue("CARROS KT - " + fechaHora);
            }
            cell.setCellStyle(titleStyle);
        }
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, CARROS_HEADERS.length - 1));

        // --- Header columnas ---
        Row headerRow = sheet.createRow(1);
        for (int i = 0; i < CARROS_HEADERS.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(CARROS_HEADERS[i]);
            cell.setCellStyle(headerStyle);
        }

        // --- Datos ---
        int rowIndex = 2;
        List<int[]> grupoRanges = new java.util.ArrayList<>();

        for (CarrosOrden orden : carrosOrdenes) {
            int grupoStart = rowIndex;

            // Fila gris de orden: # de venta + carro
            Row orderRow = sheet.createRow(rowIndex++);

            Cell cellVenta = orderRow.createCell(0);
            cellVenta.setCellValue(orden.getNumeroVenta());
            cellVenta.setCellStyle(orderStyle);

            for (int i = 1; i < CARROS_HEADERS.length - 1; i++) {
                Cell cell = orderRow.createCell(i);
                cell.setCellStyle(orderStyle);
            }

            Cell cellCarro = orderRow.createCell(CARROS_HEADERS.length - 1);
            cellCarro.setCellValue(orden.getLetraCarro());
            cellCarro.setCellStyle(orderStyle);

            // Filas blancas de items
            for (CarrosItem item : orden.getItems()) {
                String sku = item.getSku();
                boolean esError = esSkuConError(sku);
                String descripcion = item.getDescripcion() != null ? item.getDescripcion() : "";
                String sector = item.getSector() != null ? item.getSector() : "";
                boolean esAdvertencia = !esError && (descripcion.isBlank() || sector.isBlank());
                boolean destacar = item.getCantidad() > 1;

                CellStyle style;
                if (esError) {
                    style = itemErrorStyle;
                } else if (esAdvertencia) {
                    style = itemWarningStyle;
                } else if (destacar) {
                    style = itemBoldStyle;
                } else {
                    style = itemStyle;
                }

                Row itemRow = sheet.createRow(rowIndex++);

                Cell cellVentaItem = itemRow.createCell(0);
                cellVentaItem.setCellValue(orden.getNumeroVenta());
                cellVentaItem.setCellStyle(style);

                Cell cellCant = itemRow.createCell(1);
                double cant = item.getCantidad();
                if (cant == Math.floor(cant)) {
                    cellCant.setCellValue((int) cant);
                } else {
                    cellCant.setCellValue(cant);
                }
                cellCant.setCellStyle(style);

                Cell cellSku = itemRow.createCell(2);
                cellSku.setCellValue(sku);
                cellSku.setCellStyle(style);

                Cell cellProducto = itemRow.createCell(3);
                cellProducto.setCellValue(descripcion);
                cellProducto.setCellStyle(style);

                Cell cellSector = itemRow.createCell(4);
                cellSector.setCellValue(sector);
                cellSector.setCellStyle(style);

                Cell cellCarroItem = itemRow.createCell(5);
                cellCarroItem.setCellStyle(style);
            }

            grupoRanges.add(new int[]{grupoStart, rowIndex - 1});
        }

        // Aplicar bordes gruesos alrededor de cada grupo
        for (int[] range : grupoRanges) {
            CellRangeAddress region = new CellRangeAddress(range[0], range[1], 0, CARROS_HEADERS.length - 1);
            RegionUtil.setBorderTop(BorderStyle.THICK, region, sheet);
            RegionUtil.setBorderBottom(BorderStyle.THICK, region, sheet);
            RegionUtil.setBorderLeft(BorderStyle.THICK, region, sheet);
            RegionUtil.setBorderRight(BorderStyle.THICK, region, sheet);
        }

        // Auto-ajustar columnas
        for (int i = 0; i < CARROS_HEADERS.length; i++) {
            sheet.autoSizeColumn(i);
        }
    }
}
