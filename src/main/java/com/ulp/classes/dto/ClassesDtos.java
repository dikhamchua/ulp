package com.ulp.classes.dto;

/** View-model DTOs cho man hinh quan ly lop hoc. */
public class ClassesDtos {

    /**
     * View-model 1 dong trong danh sach lop. Su dung cho template
     * `templates/classes/manage.html`. Sprint sau se thay bang entity that.
     */
    public record ClassRow(
            Long id,
            String name,
            String code,
            String thumbLabel,
            String gradient,
            int studentCount,
            int lectureCount,
            int assignmentCount,
            int materialCount
    ) {}
}
