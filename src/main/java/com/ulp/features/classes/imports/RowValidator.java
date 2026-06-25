package com.ulp.features.classes.imports;

import com.ulp.security.Role;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.entities.Enrollment;
import com.ulp.features.classes.repository.EnrollmentRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates the {@link ExcelParser.RawRow rows} produced by {@link ExcelParser}
 * and attaches a final {@link ImportRowStatus} plus, when relevant, the
 * matched {@code userId}.
 *
 * <p>Validation order (first match wins):
 * <ol>
 *   <li>Empty identifier columns → {@code MISSING_REQUIRED}.</li>
 *   <li>Duplicate (email|MSSV) within the uploaded file → {@code DUPLICATE_IN_FILE}.</li>
 *   <li>Email present but malformed → {@code INVALID_EMAIL}.</li>
 *   <li>MSSV present but malformed → {@code INVALID_STUDENT_ID}.</li>
 *   <li>No matching user → {@code USER_NOT_FOUND}.</li>
 *   <li>User role is not STUDENT → {@code NOT_A_STUDENT}.</li>
 *   <li>User locked or deactivated → {@code USER_INACTIVE}.</li>
 *   <li>Existing ACTIVE enrollment → {@code DUPLICATE_IN_CLASS}.</li>
 *   <li>Existing REMOVED enrollment → {@code RE_ENROLL}.</li>
 *   <li>Otherwise → {@code OK}.</li>
 * </ol>
 *
 * <p>The validator does NOT mutate the database; it only inspects the existing
 * users and enrollments to compute the per-row status.
 */
@Component
public class RowValidator {

    /** RFC 5322-compatible-ish email regex (good enough for a preview check). */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    /** MSSV: 4..15 alphanumeric characters (relaxed to fit different institutions). */
    private static final Pattern STUDENT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9]{4,15}$");

    private final UserRepository userRepository;
    private final EnrollmentRepository enrollmentRepository;

    public RowValidator(UserRepository userRepository,
                        EnrollmentRepository enrollmentRepository) {
        this.userRepository = userRepository;
        this.enrollmentRepository = enrollmentRepository;
    }

    /**
     * Validates {@code rawRows} against the supplied class and returns the
     * fully-populated {@link ImportRow} list in the same order.
     */
    public List<ImportRow> validate(List<ExcelParser.RawRow> rawRows, Long classId) {
        List<ImportRow> result = new ArrayList<>(rawRows.size());

        Set<String> seenEmails = new HashSet<>();
        Set<String> seenStudentIds = new HashSet<>();

        // Pre-resolve users in bulk to avoid N+1 lookups. The map is keyed on
        // lowercase email; users without an email cannot be matched anyway.
        Map<String, User> usersByEmail = preloadUsersByEmail(rawRows);

        for (ExcelParser.RawRow raw : rawRows) {
            ImportRow row = new ImportRow(
                    raw.rowNumber(), raw.email(), raw.studentId(),
                    raw.fullName(), raw.phone());

            String email = blankToNull(raw.email());
            String studentId = blankToNull(raw.studentId());

            if (email == null && studentId == null) {
                row.mark(ImportRowStatus.MISSING_REQUIRED);
                result.add(row);
                continue;
            }

            // Duplicate-within-file detection BEFORE pattern checks: a duplicate
            // is a duplicate even when the second occurrence is malformed.
            String emailKey = email == null ? null : email.toLowerCase(Locale.ROOT);
            String studentIdKey = studentId == null ? null : studentId.toLowerCase(Locale.ROOT);
            boolean dupeEmail = emailKey != null && !seenEmails.add(emailKey);
            boolean dupeStudentId = studentIdKey != null && !seenStudentIds.add(studentIdKey);
            if (dupeEmail || dupeStudentId) {
                row.mark(ImportRowStatus.DUPLICATE_IN_FILE,
                        "Trùng " + (dupeEmail ? "email" : "MSSV") + " với dòng trước");
                result.add(row);
                continue;
            }

            if (email != null && !EMAIL_PATTERN.matcher(email).matches()) {
                row.mark(ImportRowStatus.INVALID_EMAIL);
                result.add(row);
                continue;
            }

            if (studentId != null && !STUDENT_ID_PATTERN.matcher(studentId).matches()) {
                row.mark(ImportRowStatus.INVALID_STUDENT_ID);
                result.add(row);
                continue;
            }

            User user = resolveUser(email, usersByEmail);
            if (user == null) {
                if (email == null) {
                    // MSSV-only row: V1 cannot resolve users by student_id
                    // (the User entity has no such column yet). Surface a
                    // lecturer-facing hint to add the email column instead of
                    // the generic "account not found" message.
                    row.mark(ImportRowStatus.USER_NOT_FOUND,
                            "Hiện chỉ hỗ trợ tìm theo email. Vui lòng bổ sung cột email.");
                } else {
                    row.mark(ImportRowStatus.USER_NOT_FOUND);
                }
                result.add(row);
                continue;
            }

            if (user.getRole() != Role.STUDENT) {
                row.mark(ImportRowStatus.NOT_A_STUDENT,
                        "Vai trò hiện tại: " + user.getRole().name());
                result.add(row);
                continue;
            }

            if (!user.isActive() || user.isLocked()) {
                row.mark(ImportRowStatus.USER_INACTIVE);
                result.add(row);
                continue;
            }

            Optional<Enrollment> existing = enrollmentRepository
                    .findByUserIdAndClassId(user.getId(), classId);
            if (existing.isPresent()) {
                Enrollment e = existing.get();
                if (Enrollment.STATUS_ACTIVE.equals(e.getStatus())) {
                    row.attachUser(user);
                    row.mark(ImportRowStatus.DUPLICATE_IN_CLASS);
                    result.add(row);
                    continue;
                }
                // REMOVED or COMPLETED → re-activate on confirm.
                row.attachUser(user);
                row.mark(ImportRowStatus.RE_ENROLL);
                result.add(row);
                continue;
            }

            row.attachUser(user);
            row.mark(ImportRowStatus.OK);
            result.add(row);
        }

        return result;
    }

    /**
     * Bulk-loads users whose email matches any of the (non-blank) emails in the
     * uploaded file. Lookups are case-insensitive; the resulting map is keyed
     * on the lowercase email so callers can probe with O(1) cost.
     */
    private Map<String, User> preloadUsersByEmail(List<ExcelParser.RawRow> rawRows) {
        Set<String> emails = new HashSet<>();
        for (ExcelParser.RawRow r : rawRows) {
            String e = blankToNull(r.email());
            if (e != null) emails.add(e.toLowerCase(Locale.ROOT));
        }
        Map<String, User> map = new HashMap<>();
        // UserRepository does not offer a batch lookup, but the per-email cost
        // is bounded by MAX_DATA_ROWS=500 which is acceptable for a one-shot
        // preview operation. We could add an `IN` query later if needed.
        for (String emailKey : emails) {
            userRepository.findByEmailIgnoreCase(emailKey)
                    .ifPresent(u -> map.put(emailKey, u));
        }
        return map;
    }

    /**
     * Lookup strategy (V1):
     * <ul>
     *   <li>Match user by <b>email</b> (lowercase exact).</li>
     *   <li>The MSSV column in the Excel file is NOT used to query users
     *       (the {@code User} entity has no {@code student_id} column yet).
     *       MSSV is only used for display and to detect duplicates within the
     *       same uploaded file.</li>
     *   <li>A row that only has an MSSV (no email) returns
     *       {@code USER_NOT_FOUND} with a detail message telling the lecturer
     *       to add the email column.</li>
     * </ul>
     * When the schema gains a {@code student_id} column (next sprint), add a
     * fallback lookup by MSSV here — the controller and service layers do
     * not need to change.
     */
    private User resolveUser(String email, Map<String, User> usersByEmail) {
        if (email == null) return null;
        return usersByEmail.get(email.toLowerCase(Locale.ROOT));
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
