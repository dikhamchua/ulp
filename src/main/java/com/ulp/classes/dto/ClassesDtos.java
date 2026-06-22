package com.ulp.classes.dto;

/** View-model DTOs cho man hinh quan ly lop hoc. */
public class ClassesDtos {

    /**
     * View-model 1 dong trong danh sach lop. Su dung cho template
     * `templates/classes/manage.html`. Sprint sau se thay bang entity that.
     *
     * <p>{@code thumbLabel} duoc derive tu {@link #name} (2 ky tu dau,
     * viet hoa). {@code gradientCss} duoc Service tinh tu chi muc danh sach
     * de moi lop co mau phan biet — xem {@link com.ulp.classes.ClassGradient}.
     */
    public record ClassRow(
            Long id,
            String name,
            String code,
            String gradientCss,
            int studentCount,
            int lectureCount,
            int assignmentCount,
            int materialCount
    ) {
        /** Nhan 2 ky tu dau cua ten lop, viet hoa, dung cho thumbnail. */
        public String thumbLabel() {
            if (name == null || name.isBlank()) return "?";
            String trimmed = name.trim();
            int end = Math.min(2, trimmed.length());
            return trimmed.substring(0, end).toUpperCase();
        }
    }
}
