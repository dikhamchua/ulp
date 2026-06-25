package com.ulp.student.dto;

import com.ulp.classes.service.InviteTokenGenerator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDateTime;

/** View-model + form DTOs for the student-facing {@code /my/classes} surface. */
public class StudentClassesDtos {

    /**
     * Form payload for {@code POST /my/classes/join}.
     *
     * <p>The 6-character invite CODE format is validated client-side
     * AND server-side. The regex accepts upper- and lower-case letters
     * plus digits — the service normalizes to upper-case before
     * lookup (see spec scenario "CODE is case-insensitive on input").
     */
    public record JoinForm(
            @NotBlank(message = "Mã không được để trống")
            @Pattern(regexp = InviteTokenGenerator.CODE_REGEX,
                    message = "Mã phải có 6 ký tự")
            String code
    ) {
        public static JoinForm empty() {
            return new JoinForm("");
        }
    }

    /**
     * A single row rendered on {@code GET /my/classes}.
     *
     * @param classId        target class id (used by the leave form)
     * @param className      class name as shown in the row title
     * @param classCode      5-char {@code classes.code} (display only)
     * @param lecturerName   lecturer's full name for the row subtitle
     * @param joinedAt       when the student joined (timestamp shown)
     * @param avatarGradient CSS gradient string used by the card visual
     */
    public record EnrolledClassRow(
            Long classId,
            String className,
            String classCode,
            String lecturerName,
            LocalDateTime joinedAt,
            String avatarGradient
    ) {
        /** Two-letter capitalized abbreviation of the class name. */
        public String thumbLabel() {
            if (className == null || className.isBlank()) return "?";
            String trimmed = className.trim();
            int end = Math.min(2, trimmed.length());
            return trimmed.substring(0, end).toUpperCase();
        }
    }
}
