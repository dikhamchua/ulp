package com.ulp.features.questionbank.dto;

import java.util.List;
import java.util.UUID;

/** JSON payload records for question bank Excel preview and confirm flows. */
public final class QuestionBankImportDtos {

    private QuestionBankImportDtos() {
    }

    public record PreviewRow(int rowNumber,
                             String categoryName,
                             String questionType,
                             String contentPreview,
                             String status,
                             String message,
                             int optionCount,
                             int correctCount,
                             boolean blocking) {
    }

    public record Preview(UUID sessionId,
                          String fileName,
                          int totalRows,
                          int acceptedRows,
                          int errorRows,
                          boolean confirmable,
                          List<PreviewRow> rows) {
    }

    public record ConfirmResult(int createdCount,
                                int totalRows,
                                String workflowStatus,
                                List<Long> itemIds) {
    }

    public record ConfirmRequest(UUID sessionId) {
    }
}
