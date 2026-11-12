package com.yourorg.qbank.persistence.adapter;

import com.yourorg.qbank.core.exception.BadRequestException;
import com.yourorg.qbank.core.importer.RawRow;
import com.yourorg.qbank.core.importer.RawSheetData;
import com.yourorg.qbank.core.port.SpreadsheetPort;
import com.yourorg.qbank.core.security.FormulaSanitizer;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Security-hardened Apache POI Excel adapter:
 * - Enforces byte size cap
 * - Verifies ZIP (.xlsx) / OLE2 (.xls) magic bytes (not file extension alone)
 * - Enforces ZipSecureFile ratios and pre-scans OOXML entries against XXE / zip bombs
 * - Extracts every cell strictly as plain display text via DataFormatter without evaluating formulas
 * - Neutralizes leading formula characters on exported workbooks
 */
public class HardenedPoiSpreadsheetAdapter implements SpreadsheetPort {

    private static final int MAX_ZIP_ENTRIES = 256;
    private static final long MAX_UNCOMPRESSED_ZIP_BYTES = 64L * 1024 * 1024;
    private static final int MAX_ROWS_PER_SHEET = 10_000;

    static {
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(16L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(10L * 1024 * 1024);
    }

    @Override
    public List<RawSheetData> readWorkbook(byte[] content, String filename, int maxBytes) {
        if (content == null || content.length == 0) {
            throw new BadRequestException("Uploaded Excel file is empty");
        }
        if (content.length > maxBytes) {
            throw new BadRequestException("Uploaded file exceeds limit of " + (maxBytes / (1024 * 1024)) + " MB");
        }

        boolean isZip = hasZipMagic(content);
        boolean isOle2 = hasOle2Magic(content);
        if (!isZip && !isOle2) {
            throw new BadRequestException("Invalid Excel binary signature (file is neither .xlsx nor .xls)");
        }
        if (isZip) {
            scanZipSafety(content);
        }

        DataFormatter formatter = new DataFormatter(Locale.ROOT, true);
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            List<RawSheetData> out = new ArrayList<>();
            int sheetCount = Math.min(wb.getNumberOfSheets(), 32);
            for (int s = 0; s < sheetCount; s++) {
                Sheet sheet = wb.getSheetAt(s);
                if (sheet == null) {
                    continue;
                }
                Set<Integer> mergedDupCols = detectDuplicateMergedColumns(sheet);
                int lastRow = Math.min(sheet.getLastRowNum(), MAX_ROWS_PER_SHEET);
                int maxCols = 0;
                List<RawRow> rows = new ArrayList<>();
                for (int r = 0; r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) {
                        continue;
                    }
                    int cellCount = Math.min(Math.max(row.getLastCellNum(), 0), 32);
                    maxCols = Math.max(maxCols, cellCount);
                    List<String> cells = new ArrayList<>(cellCount);
                    boolean anyNonBlank = false;
                    for (int c = 0; c < cellCount; c++) {
                        Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        String text = readPlainTextCell(cell, formatter);
                        if (!text.isBlank()) {
                            anyNonBlank = true;
                        }
                        cells.add(text);
                    }
                    if (anyNonBlank) {
                        rows.add(new RawRow(r + 1, cells));
                    }
                }
                if (!rows.isEmpty()) {
                    out.add(new RawSheetData(sheet.getSheetName(), maxCols, rows, mergedDupCols));
                }
            }
            return out;
        } catch (BadRequestException bre) {
            throw bre;
        } catch (Exception e) {
            throw new BadRequestException("Unable to read Excel workbook: " + e.getMessage());
        }
    }

    @Override
    public byte[] createBlankTemplate(boolean multiModule) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle lockedHeaderStyle = wb.createCellStyle();
            lockedHeaderStyle.setLocked(true);
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            lockedHeaderStyle.setFont(boldFont);

            CellStyle editableStyle = wb.createCellStyle();
            editableStyle.setLocked(false);

            int count = multiModule ? 5 : 1;
            List<String> cols = List.of("Question", "A", "B", "C", "D", "Correct");
            for (int m = 1; m <= count; m++) {
                Sheet sheet = wb.createSheet("Module " + m);
                for (int i = 0; i < cols.size(); i++) {
                    sheet.setDefaultColumnStyle(i, editableStyle);
                    sheet.setColumnWidth(i, i == 0 ? 14000 : 5000);
                }
                Row header = sheet.createRow(0);
                for (int i = 0; i < cols.size(); i++) {
                    Cell cell = header.createCell(i, CellType.STRING);
                    cell.setCellValue(cols.get(i));
                    cell.setCellStyle(lockedHeaderStyle);
                }

                DataValidationHelper dvHelper = sheet.getDataValidationHelper();
                DataValidationConstraint dvConstraint = dvHelper.createExplicitListConstraint(new String[]{"A", "B", "C", "D"});
                DataValidation validation = dvHelper.createValidation(dvConstraint, new CellRangeAddressList(1, 500, 5, 5));
                validation.setShowErrorBox(true);
                sheet.addValidationData(validation);
                sheet.createFreezePane(0, 1);
                sheet.protectSheet("qbank-header");
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate Excel template", e);
        }
    }

    @Override
    public byte[] createSanitizedWorkbook(String sheetName, List<List<String>> rows) {
        String safeSheetName = (sheetName == null || sheetName.isBlank()) ? "Sheet1" : sheetName.trim();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(safeSheetName);
            for (int r = 0; r < rows.size(); r++) {
                Row excelRow = sheet.createRow(r);
                List<String> cols = rows.get(r);
                for (int c = 0; c < cols.size(); c++) {
                    String safeValue = FormulaSanitizer.neutralizeSpreadsheetCell(cols.get(c));
                    excelRow.createCell(c, CellType.STRING).setCellValue(safeValue);
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create sanitized workbook", e);
        }
    }

    private static String readPlainTextCell(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.FORMULA) {
            // Treat formula cells strictly as raw literal text (never evaluate formulas)
            return "=" + cell.getCellFormula();
        }
        String formatted = formatter.formatCellValue(cell);
        return formatted == null ? "" : formatted;
    }

    private static Set<Integer> detectDuplicateMergedColumns(Sheet sheet) {
        Set<Integer> cols = new HashSet<>();
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            CellRangeAddress range = sheet.getMergedRegion(i);
            if (range.getFirstRow() != range.getLastRow() && range.getFirstColumn() == range.getLastColumn()) {
                cols.add(range.getFirstColumn());
            }
        }
        return cols;
    }

    private static boolean hasZipMagic(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == 0x50 && bytes[1] == 0x4B
                && bytes[2] == 0x03 && bytes[3] == 0x04;
    }

    private static boolean hasOle2Magic(byte[] bytes) {
        return bytes.length >= 8
                && (bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF
                && (bytes[2] & 0xFF) == 0x11 && (bytes[3] & 0xFF) == 0xE0;
    }

    private static void scanZipSafety(byte[] content) {
        long totalUncompressed = 0L;
        int entryCount = 0;
        byte[] buf = new byte[4096];
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw new BadRequestException("Workbook zip exceeds maximum safe entry count");
                }
                String name = entry.getName().toLowerCase(Locale.ROOT);
                boolean isXml = name.endsWith(".xml") || name.endsWith(".rels");
                ByteArrayOutputStream xmlHead = isXml ? new ByteArrayOutputStream(4096) : null;
                int read;
                while ((read = zis.read(buf)) > 0) {
                    totalUncompressed += read;
                    if (totalUncompressed > MAX_UNCOMPRESSED_ZIP_BYTES) {
                        throw new BadRequestException("Workbook rejected: zip bomb expansion limit exceeded");
                    }
                    if (xmlHead != null && xmlHead.size() < 8192) {
                        xmlHead.write(buf, 0, Math.min(read, 8192 - xmlHead.size()));
                    }
                }
                if (xmlHead != null) {
                    String snippet = xmlHead.toString(StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
                    if (snippet.contains("<!DOCTYPE") || snippet.contains("<!ENTITY")) {
                        throw new BadRequestException("Workbook rejected: prohibited XML DOCTYPE/ENTITY declaration detected");
                    }
                }
                zis.closeEntry();
            }
        } catch (BadRequestException bre) {
            throw bre;
        } catch (IOException e) {
            throw new BadRequestException("Malformed .xlsx zip archive: " + e.getMessage());
        }
    }
}
