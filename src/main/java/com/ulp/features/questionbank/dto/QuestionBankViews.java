package com.ulp.features.questionbank.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Read-only DTOs for department-scoped shared question pages. */
public final class QuestionBankViews {

    private QuestionBankViews() {
    }

    public record CategoryOption(Long id, String name, boolean active) {
    }

    public record CategoryRow(Long id, String name, String description,
                              boolean active, long itemCount) {
    }

    /** One distinct contributor shown in the HEAD management-screen filter. */
    public record ContributorOption(Long id, String name) {
    }

    /** Workflow-status tallies for the HEAD management-screen stat header. */
    public record StatusCounts(long review, long approved, long rejected,
                               long archived, long total) {
    }

    public record ItemRow(Long id, String contentPreview, String questionType,
                          String workflowStatus, Long categoryId, String categoryName,
                          String contributorName, LocalDateTime updatedAt,
                          boolean editable, boolean reviewable) {
    }

    /**
     * Master→detail payload: category header plus its questions and scoped tallies.
     * Items carry full detail (content, options, review flags) so the detail screen
     * can render a client-side "view" modal per row without a server round-trip.
     */
    public record CategoryDetailView(Long categoryId, String categoryName, String description,
                                     boolean active, List<ItemDetail> items,
                                     StatusCounts statusCounts, List<ContributorOption> contributors) {
    }

    public record OptionView(String content, boolean correct) {
    }

    public record ItemDetail(Long id, String questionType, String workflowStatus,
                             String content, String explanation, String reviewNote,
                             String categoryName, String contributorName,
                             String reviewerName, LocalDateTime reviewedAt,
                             LocalDateTime approvedAt, LocalDateTime updatedAt,
                             List<OptionView> options, boolean editable,
                             boolean reviewable, boolean archivable, boolean unarchivable) {
    }
}
