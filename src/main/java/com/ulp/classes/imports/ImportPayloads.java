package com.ulp.classes.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the JSON payload structures the {@link ImportStudentsController}
 * returns to the browser for both preview and confirm responses. Extracted
 * from the controller so the HTTP handlers stay focused on transport concerns.
 */
final class ImportPayloads {

    private ImportPayloads() {}

    /** Serializes a preview session (upload response). */
    static Map<String, Object> preview(ImportSession session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", session.getId().toString());
        payload.put("fileName", session.getFileName());
        payload.put("totalRows", session.totalRows());
        payload.put("okCount", session.okCount());
        payload.put("warningCount", session.warningCount());
        payload.put("errorCount", session.errorCount());
        payload.put("rows", rows(session.getRows()));
        return payload;
    }

    /** Serializes a confirm result (confirm response). */
    static Map<String, Object> result(ImportResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalProcessed", result.totalProcessed());
        payload.put("imported", result.imported());
        payload.put("reactivated", result.reactivated());
        payload.put("skippedDuplicate", result.skippedDuplicate());
        payload.put("skippedError", result.skippedError());
        payload.put("failed", result.failed());
        payload.put("rows", rows(result.rows()));
        return payload;
    }

    /** Serializes the per-row identity + status into a JSON-friendly map. */
    static List<Map<String, Object>> rows(List<ImportRow> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ImportRow row : rows) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("rowNumber", row.getRowNumber());
            r.put("email", row.getEmail());
            r.put("studentId", row.getStudentId());
            r.put("fullName", row.getFullName());
            r.put("phone", row.getPhone());
            ImportRowStatus s = row.getStatus();
            r.put("status", s == null ? null : s.name());
            r.put("statusMessage", s == null ? null : s.message());
            r.put("isError", s != null && s.isError());
            r.put("isWarning", s != null && s.isWarning());
            r.put("isImportable", s != null && s.isImportable());
            r.put("detail", row.getErrorDetail());
            out.add(r);
        }
        return out;
    }
}
