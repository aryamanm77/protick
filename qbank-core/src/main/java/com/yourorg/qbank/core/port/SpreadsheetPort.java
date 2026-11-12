package com.yourorg.qbank.core.port;

import com.yourorg.qbank.core.importer.RawSheetData;

import java.util.List;

/**
 * Port hiding Excel parsing/generation I/O libraries (Apache POI) outside qbank-core.
 */
public interface SpreadsheetPort {

    List<RawSheetData> readWorkbook(byte[] content, String filename, int maxBytes);

    byte[] createBlankTemplate(boolean multiModule);

    byte[] createSanitizedWorkbook(String sheetName, List<List<String>> rows);
}
