package com.ulp.classes.dto;

import com.ulp.classes.entity.ClassEntity;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** View-model DTOs cho man hinh quan ly lop hoc cua giang vien. */
public class ClassesDtos {

    /**
     * View-model 1 dong trong danh sach lop. Render boi
     * {@code templates/classes/manage.html}.
     *
     * <p>{@code thumbLabel} duoc derive tu {@link #name} (2 ky tu dau,
     * viet hoa). {@code gradientCss} duoc Service tinh tu chi muc danh sach
     * de moi lop co mau phan biet — xem {@link com.ulp.classes.ClassGradient}.
     *
     * <p>Cac cot stat (studentCount/lectureCount/assignmentCount/materialCount)
     * tam thoi return 0 cho Sprint 2. Sprint 3/5 se noi vao count thuc.
     *
     * <p>{@code createdAtIso} la {@code created_at.toString()} duoi dang
     * ISO-8601, dung cho client-side sort theo ngay tao.
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
        /** Nhan 2 ky tu dau cua ten lop, viet hoa, dung cho thumbnail. */
        public String thumbLabel() {
            if (name == null || name.isBlank()) return "?";
            String trimmed = name.trim();
            int end = Math.min(2, trimmed.length());
            return trimmed.substring(0, end).toUpperCase();
        }
    }

    /**
     * Form payload cho ca {@code GET /lecturer/classes/new} + {@code /edit}
     * lan {@code POST /lecturer/classes} + {@code /{id}}.
     *
     * <p>Quy tac validate:
     * <ul>
     *   <li>{@code name}: bat buoc, 3–300 ky tu</li>
     *   <li>{@code description}: tuy chon, ≤2000 ky tu</li>
     *   <li>{@code maxStudents}: tuy chon, 1–1000</li>
     *   <li>{@code endDate} phai STRICTLY sau {@code startDate} khi ca 2 deu co
     *       (equal cung khong duoc, tranh "lop 1 ngay") — kiem tra qua
     *       {@link #isDateRangeValid()}</li>
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

        /** Form rong, dung khi render {@code GET /new}. */
        public static ClassForm empty() {
            return new ClassForm("", "", null, null, 100);
        }

        /** Chuyen entity ve form de pre-fill khi edit. */
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
         * Bean Validation constraint: {@code endDate} phai STRICTLY sau
         * {@code startDate} khi ca 2 deu non-null. Equal cung bi reject.
         * Truong hop NULL (mot trong hai hoac ca 2) duoc cho phep.
         *
         * <p>Violation se gan vao field {@code endDate} qua {@code @AssertTrue}
         * — Spring/Hibernate Validator suy ra ten property tu ten method
         * ({@code isDateRangeValid} → {@code dateRangeValid}). Controller
         * se rebind error vao field {@code endDate} cho dung UX.
         */
        @AssertTrue(message = "Ngày kết thúc phải sau ngày bắt đầu")
        public boolean isDateRangeValid() {
            if (startDate == null || endDate == null) return true;
            return endDate.isAfter(startDate);
        }
    }
}
