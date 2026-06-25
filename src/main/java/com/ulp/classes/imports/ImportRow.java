package com.ulp.classes.imports;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ulp.auth.entity.User;

/**
 * Mutable holder describing a single row of an uploaded student-import file.
 *
 * <p>The row is built in two stages:
 * <ol>
 *   <li>{@link ExcelParser} produces it from a spreadsheet row, populating
 *       {@code rowNumber}, {@code email}, {@code studentId}, {@code fullName},
 *       and {@code phone}.</li>
 *   <li>{@link RowValidator} fills in {@code status} and {@code errorDetail}
 *       (and {@code user} when the row resolves to a real account).</li>
 * </ol>
 *
 * <p>The class is intentionally a JavaBean (plain mutable fields) rather than a
 * record so the validator can populate fields one-at-a-time while keeping the
 * row identity (the {@code rowNumber}) stable.
 */
public class ImportRow {

    private final int rowNumber;
    private final String email;
    private final String studentId;
    private final String fullName;
    private final String phone;

    private ImportRowStatus status;
    private String errorDetail;
    private User user;

    public ImportRow(int rowNumber, String email, String studentId,
                     String fullName, String phone) {
        this.rowNumber = rowNumber;
        this.email = email;
        this.studentId = studentId;
        this.fullName = fullName;
        this.phone = phone;
    }

    public int getRowNumber() {
        return rowNumber;
    }

    public String getEmail() {
        return email;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPhone() {
        return phone;
    }

    public ImportRowStatus getStatus() {
        return status;
    }

    public String getErrorDetail() {
        return errorDetail;
    }

    /**
     * The fully-hydrated user matched by {@link RowValidator}. {@code null}
     * for rows that did not resolve to an account.
     *
     * <p>Marked {@link JsonIgnore} so the user object is never serialized as
     * part of the preview/confirm response — the controller exposes only the
     * scalar identity fields via its row payload mapper.
     */
    @JsonIgnore
    public User getUser() {
        return user;
    }

    /** Convenience getter that returns {@code user.getId()} or {@code null}. */
    public Long getUserId() {
        return user == null ? null : user.getId();
    }

    /**
     * Marks the row with a final status code and a short Vietnamese tooltip
     * description (≤80 characters). Detail is only set when it adds useful
     * context beyond {@link ImportRowStatus#message()}.
     */
    public void mark(ImportRowStatus status, String detail) {
        this.status = status;
        this.errorDetail = truncate(detail);
    }

    public void mark(ImportRowStatus status) {
        mark(status, null);
    }

    /**
     * Attaches the resolved user to the row. Called by {@link RowValidator}
     * during validation so the confirm step can persist the enrollment
     * without re-fetching the user.
     */
    public void attachUser(User user) {
        this.user = user;
    }

    private static String truncate(String detail) {
        if (detail == null) return null;
        String trimmed = detail.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 77) + "...";
    }
}
