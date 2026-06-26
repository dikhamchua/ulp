package com.ulp.features.classes.imports.service;

import com.ulp.entities.User;
import com.ulp.entities.Enrollment;
import com.ulp.features.classes.imports.dto.ImportRow;
import com.ulp.features.classes.imports.dto.ImportRowStatus;
import com.ulp.features.classes.imports.dto.RowOutcome;
import com.ulp.features.classes.repository.EnrollmentRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Maps the status of a previewed {@link ImportRow} to a database action and a
 * {@link RowOutcome} contribution. Extracted from {@link ImportStudentsService}
 * so the confirm-loop body stays at one level of abstraction.
 *
 * <p>The processor never flushes — it appends to the caller-supplied
 * {@code pending} list, leaving batch sizing and persistence to the service.
 */
@Component
class ImportRowProcessor {

    private final EnrollmentRepository enrollmentRepository;

    ImportRowProcessor(EnrollmentRepository enrollmentRepository) {
        this.enrollmentRepository = enrollmentRepository;
    }

    /**
     * Routes a single row to the appropriate handler based on its preview status.
     */
    RowOutcome process(ImportRow row, Long classId, List<Enrollment> pending) {
        ImportRowStatus status = row.getStatus();
        return switch (status) {
            case OK -> processOk(row, classId, pending);
            case RE_ENROLL -> processReEnroll(row, classId, pending);
            case DUPLICATE_IN_CLASS -> RowOutcome.SKIPPED_DUP;
            default -> status.isError() ? RowOutcome.SKIPPED_ERR : RowOutcome.ZERO;
        };
    }

    /** Stages a brand-new enrollment row for the OK preview status. */
    private RowOutcome processOk(ImportRow row, Long classId, List<Enrollment> pending) {
        User user = row.getUser();
        if (user == null) {
            // Defensive guard — the validator must populate the user for OK rows.
            row.mark(ImportRowStatus.PERSISTENCE_FAILED,
                    "Tài khoản đã thay đổi giữa lúc preview và xác nhận");
            return RowOutcome.FAILED;
        }
        pending.add(Enrollment.createFor(user, classId, Enrollment.JoinedVia.IMPORT, null));
        return RowOutcome.IMPORTED;
    }

    /**
     * Handles a RE_ENROLL preview status. Three sub-cases:
     * <ul>
     *   <li>existing row deleted between preview/confirm → fresh insert;</li>
     *   <li>existing row still REMOVED → reactivate in place;</li>
     *   <li>existing row now ACTIVE → silent skip.</li>
     * </ul>
     */
    private RowOutcome processReEnroll(ImportRow row, Long classId, List<Enrollment> pending) {
        Optional<Enrollment> existing = enrollmentRepository
                .findByUserIdAndClassId(row.getUserId(), classId);
        if (existing.isEmpty()) {
            User user = row.getUser();
            if (user == null) {
                row.mark(ImportRowStatus.PERSISTENCE_FAILED);
                return RowOutcome.FAILED;
            }
            pending.add(Enrollment.createFor(user, classId, Enrollment.JoinedVia.IMPORT, null));
            return RowOutcome.IMPORTED;
        }
        Enrollment e = existing.get();
        if (Enrollment.STATUS_ACTIVE.equals(e.getStatus())) {
            return RowOutcome.SKIPPED_DUP;
        }
        e.reactivateVia(Enrollment.JoinedVia.IMPORT, null);
        pending.add(e);
        return RowOutcome.REACTIVATED;
    }
}
