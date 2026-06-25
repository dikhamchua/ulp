package com.ulp.features.classes.dto;

import com.ulp.entities.ClassEntity;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** View-model DTOs for the lecturer class-management screens. */
public class ClassesDtos {

    /**
     * View-model for a single row in the class list, rendered by
     * {@code templates/classes/manage.html}.
     *
     * <p>{@code thumbLabel} is derived from {@link #name} (first two characters,
     * uppercased). {@code gradientCss} is computed by the service from the list
     * index so each class gets a distinct color — see {@link com.ulp.features.classes.ClassGradient}.
     *
     * <p>The stat columns ({@code studentCount}, {@code lectureCount},
     * {@code assignmentCount}, {@code materialCount}) temporarily return 0 for
     * Sprint 2. Sprint 3/5 will wire them to real counts.
     *
     * <p>{@code createdAtIso} is {@code created_at.toString()} in ISO-8601 format,
     * used for client-side sorting by creation date.
     */
    public record ClassRow(
            Long id,
            String name,
            String code,
            String gradientCss,
            int studentCount,
            int lectureCount,
            int assignmentCount,
            int materialCount,
            String createdAtIso
    ) {
        /** Returns the first two characters of the class name, uppercased, for use as a thumbnail label. */
        public String thumbLabel() {
            if (name == null || name.isBlank()) return "?";
            String trimmed = name.trim();
            int end = Math.min(2, trimmed.length());
            return trimmed.substring(0, end).toUpperCase();
        }
    }

    /**
     * Form payload for both {@code GET /lecturer/classes/new} + {@code /edit}
     * and {@code POST /lecturer/classes} + {@code /{id}}.
     *
     * <p>Validation rules:
     * <ul>
     *   <li>{@code name}: required, 3–300 characters</li>
     *   <li>{@code description}: optional, ≤2000 characters</li>
     *   <li>{@code maxStudents}: optional, 1–1000</li>
     *   <li>{@code endDate} must be STRICTLY after {@code startDate} when both are
     *       present (equal dates are also rejected to avoid a one-day class) —
     *       enforced via {@link #isDateRangeValid()}</li>
     * </ul>
     */
    public record ClassForm(
            @NotBlank(message = "Tên lớp không được để trống")
            @Size(min = 3, max = 300, message = "Tên lớp 3–300 ký tự")
            String name,

            @Size(max = 2000, message = "Mô tả tối đa 2000 ký tự")
            String description,

            LocalDate startDate,
            LocalDate endDate,

            @Min(value = 1, message = "Sĩ số tối thiểu là 1")
            @Max(value = 1000, message = "Sĩ số tối đa là 1000")
            Integer maxStudents
    ) {

        /** Returns an empty form instance, used when rendering {@code GET /new}. */
        public static ClassForm empty() {
            return new ClassForm("", "", null, null, 100);
        }

        /** Converts an entity to a form instance to pre-fill the edit form.
         *
         * @param e the {@link ClassEntity} to read field values from
         * @return a {@code ClassForm} populated with the entity's current values
         */
        public static ClassForm fromEntity(ClassEntity e) {
            return new ClassForm(
                    e.getName(),
                    e.getDescription(),
                    e.getStartDate(),
                    e.getEndDate(),
                    e.getMaxStudents()
            );
        }

        /**
         * Bean Validation constraint: {@code endDate} must be STRICTLY after
         * {@code startDate} when both are non-null. Equal dates are also rejected.
         * {@code null} values (either or both fields) are allowed and pass validation.
         *
         * <p>The violation is bound to the {@code endDate} field via {@code @AssertTrue}
         * — Spring/Hibernate Validator infers the property name from the method name
         * ({@code isDateRangeValid} → {@code dateRangeValid}). The controller
         * re-binds the error to the {@code endDate} field for correct UX.
         *
         * @return {@code true} if the date range is valid or either date is {@code null}
         */
        @AssertTrue(message = "Ngày kết thúc phải sau ngày bắt đầu")
        public boolean isDateRangeValid() {
            if (startDate == null || endDate == null) return true;
            return endDate.isAfter(startDate);
        }
    }

    /**
     * View-model for the active {@code CODE} invite token rendered
     * on the Members tab "Mời sinh viên" panel.
     *
     * @param code     the 6-char invite code value
     * @param id       primary key of the {@code class_invite_codes} row
     * @param useCount how many successful joins this token has served
     */
    public record InviteCodeView(String code, Long id, Integer useCount) {}

    /**
     * View-model for the active {@code LINK} invite token rendered
     * on the Members tab "Mời sinh viên" panel.
     *
     * @param token    the 32-char base64url token (the URL path
     *                 segment under {@code /j/})
     * @param fullUrl  the complete invite URL the lecturer copies
     *                 (e.g. {@code https://app.example/j/<token>})
     * @param id       primary key of the {@code class_invite_codes} row
     * @param useCount how many successful joins this LINK has served
     */
    public record InviteLinkView(String token, String fullUrl, Long id, Integer useCount) {}
}
