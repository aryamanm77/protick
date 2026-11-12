package com.yourorg.qbank.starter.web;

import com.yourorg.qbank.core.exception.BadRequestException;
import com.yourorg.qbank.core.importer.ImportPreviewResult;
import com.yourorg.qbank.core.service.CoreImportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("${qbank.base-path:/qbank}/api/v1/hod")
public class QBankHodController {

    private final CoreImportService importService;

    public QBankHodController(CoreImportService importService) {
        this.importService = importService;
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate(@RequestParam(defaultValue = "false") boolean multi) {
        byte[] bytes = importService.downloadBlankTemplate(multi);
        String filename = multi ? "qbank-5module-template.xlsx" : "qbank-module-template.xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    @PostMapping(value = "/subjects/{subjectId}/modules/{moduleNumber}/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPreviewResult previewSingle(
            @PathVariable long subjectId,
            @PathVariable int moduleNumber,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Excel file is required");
        }
        return importService.previewSingleModule(subjectId, moduleNumber, file.getBytes(), file.getOriginalFilename());
    }

    @PostMapping(value = "/subjects/{subjectId}/import-multi/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<ImportPreviewResult> previewMulti(
            @PathVariable long subjectId,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Excel workbook is required");
        }
        return importService.previewMultiModule(subjectId, file.getBytes(), file.getOriginalFilename());
    }

    @GetMapping("/previews/{previewId}")
    public ImportPreviewResult getPreview(@PathVariable String previewId) {
        return importService.getPreview(previewId);
    }

    @PutMapping("/previews/{previewId}/rows/{sourceRow}")
    public ImportPreviewResult updatePreviewRow(
            @PathVariable String previewId,
            @PathVariable int sourceRow,
            @RequestBody UpdateRowRequest req
    ) {
        return importService.updatePreviewRow(
                previewId,
                sourceRow,
                req.questionText(),
                req.options(),
                req.correctIndex(),
                req.excluded()
        );
    }

    @PostMapping("/previews/{previewId}/confirm")
    public CoreImportService.ConfirmImportResult confirm(@PathVariable String previewId) {
        return importService.confirmPreview(previewId);
    }

    @DeleteMapping("/previews/{previewId}")
    public ResponseEntity<Void> discard(@PathVariable String previewId) {
        importService.discardPreview(previewId);
        return ResponseEntity.noContent().build();
    }

    public record UpdateRowRequest(
            String questionText,
            List<String> options,
            Integer correctIndex,
            boolean excluded
    ) {
    }
}
