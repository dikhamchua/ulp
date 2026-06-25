package com.ulp.classes.imports;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * In-memory session capturing the result of a single Excel preview upload.
 *
 * <p>The session is created by {@link ImportStudentsService#previewUpload}
 * after parsing and validation finish, stored in {@link ImportSessionStore},
 * and later replayed by {@link ImportStudentsService#confirmImport} when the
 * lecturer confirms the operation. Sessions expire after 10 minutes; an
 * expired session must be re-uploaded.
 */
public final class ImportSession {

    /** TTL after which the cleanup scheduler discards the session. */
    public static final long TTL_MINUTES = 10L;

    private final UUID id;
    private final Long classId;
    private final Long lecturerId;
    private final Instant uploadedAt;
    private final String fileName;
    private final List<ImportRow> rows;

    public ImportSession(UUID id, Long classId, Long lecturerId,
                         Instant uploadedAt, String fileName, List<ImportRow> rows) {
        this.id = id;
        this.classId = classId;
        this.lecturerId = lecturerId;
        this.uploadedAt = uploadedAt;
        this.fileName = fileName;
        this.rows = List.copyOf(rows);
    }

    public UUID getId() { return id; }
    public Long getClassId() { return classId; }
    public Long getLecturerId() { return lecturerId; }
    public Instant getUploadedAt() { return uploadedAt; }
    public String getFileName() { return fileName; }
    public List<ImportRow> getRows() { return rows; }

    public int totalRows() {
        return rows.size();
    }

    public long okCount() {
        return rows.stream().filter(r -> r.getStatus() == ImportRowStatus.OK
                                       || r.getStatus() == ImportRowStatus.RE_ENROLL).count();
    }

    public long warningCount() {
        return rows.stream().filter(r -> r.getStatus() != null && r.getStatus().isWarning()).count();
    }

    public long errorCount() {
        return rows.stream().filter(r -> r.getStatus() != null && r.getStatus().isError()).count();
    }

    /** Whether the session is older than {@link #TTL_MINUTES} minutes. */
    public boolean isExpired(Instant now) {
        return uploadedAt.plusSeconds(TTL_MINUTES * 60).isBefore(now);
    }
}
