package com.yourorg.qbank.persistence;

import com.yourorg.qbank.core.exception.BadRequestException;
import com.yourorg.qbank.core.importer.RawSheetData;
import com.yourorg.qbank.persistence.adapter.HardenedPoiSpreadsheetAdapter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class HardenedPoiSecurityTest {

    private final HardenedPoiSpreadsheetAdapter adapter = new HardenedPoiSpreadsheetAdapter();

    @Test
    void rejectsSpoofedExtensionWithNonExcelMagicBytes() {
        byte[] fakeXlsx = "Just a plain text file masquerading as xlsx".getBytes(StandardCharsets.UTF_8);
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> adapter.readWorkbook(fakeXlsx, "evil.xlsx", 1024 * 1024));
        assertTrue(ex.getMessage().contains("Invalid Excel binary signature"));
    }

    @Test
    void rejectsXxeDoctypeEntityInsideOoxmlArchive() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zos.write("<!DOCTYPE foo [ <!ENTITY xxe SYSTEM \"file:///c:/secret\"> ]><Types/>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> adapter.readWorkbook(baos.toByteArray(), "xxe.xlsx", 5 * 1024 * 1024));
        assertTrue(ex.getMessage().contains("DOCTYPE/ENTITY"));
    }

    @Test
    void rejectsZipBombExceedingSafeDecompressedBudget() throws Exception {
        // Put >256 zip entries to hit safe zip entry threshold or large zero-compressed payload
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (int i = 0; i < 260; i++) {
                zos.putNextEntry(new ZipEntry("e" + i + ".txt"));
                zos.write("x".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        assertThrows(BadRequestException.class,
                () -> adapter.readWorkbook(baos.toByteArray(), "bomb.xlsx", 5 * 1024 * 1024));
    }

    @Test
    void treatsFormulaCellsAsPlainTextAndNeutralizesFormulaExportCells() throws Exception {
        byte[] xlsx;
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Module 1");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Question");
            h.createCell(1).setCellValue("Option A");
            h.createCell(2).setCellValue("Option B");
            h.createCell(3).setCellValue("Correct");
            Row r = s.createRow(1);
            r.createCell(0).setCellFormula("1+1");
            r.createCell(1).setCellValue("Two");
            r.createCell(2).setCellValue("Three");
            r.createCell(3).setCellValue("A");
            wb.write(out);
            xlsx = out.toByteArray();
        }

        List<RawSheetData> parsed = adapter.readWorkbook(xlsx, "formula.xlsx", 1024 * 1024);
        assertEquals("=1+1", parsed.get(0).rows().get(1).cell(0));

        byte[] exported = adapter.createSanitizedWorkbook("Results", List.of(
                List.of("Roll", "Name"),
                List.of("CS01", "=HYPERLINK(\"http://evil\")")
        ));
        List<RawSheetData> reRead = adapter.readWorkbook(exported, "out.xlsx", 1024 * 1024);
        assertEquals("'=HYPERLINK(\"http://evil\")", reRead.get(0).rows().get(1).cell(1));
    }

    @Test
    void generatesBlankFiveModuleTemplateWithLockedHeaderAndAnswerDropdown() throws Exception {
        byte[] template = adapter.createBlankTemplate(true);
        List<RawSheetData> sheets = adapter.readWorkbook(template, "qbank-5module-template.xlsx", 5 * 1024 * 1024);
        assertEquals(5, sheets.size());
        for (int i = 0; i < 5; i++) {
            assertEquals("Module " + (i + 1), sheets.get(i).sheetName());
            assertEquals(List.of("Question", "A", "B", "C", "D", "Correct"), sheets.get(i).rows().get(0).cells());
        }
        try (Workbook wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(template))) {
            Sheet m1 = wb.getSheetAt(0);
            assertTrue(m1.getProtect());
            assertTrue(m1.getRow(0).getCell(0).getCellStyle().getLocked());
            assertFalse(m1.getDataValidations().isEmpty());
            assertArrayEquals(new String[]{"A", "B", "C", "D"},
                    m1.getDataValidations().get(0).getValidationConstraint().getExplicitListValues());
        }
    }
}
