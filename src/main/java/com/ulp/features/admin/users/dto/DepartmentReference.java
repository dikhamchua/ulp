package com.ulp.features.admin.users.dto;

import java.util.List;

/**
 * Lightweight department reference used by the admin user-management form.
 *
 * <p>Sprint 6 will introduce the real Departments capability with its own
 * repository and CRUD UI. Until then, the Edit form needs SOMETHING to drive
 * the department dropdown, so we expose the five departments seeded in V2 as
 * an immutable in-memory list. When Sprint 6 ships, replace
 * {@link DepartmentReference#DEFAULT_LIST} with calls to the new repository
 * and delete this class.
 */
public record DepartmentReference(Long id, String code, String name) {

    public static final List<DepartmentReference> DEFAULT_LIST = List.of(
            new DepartmentReference(1L, "CNTT", "Công nghệ thông tin"),
            new DepartmentReference(2L, "KT",   "Kinh tế"),
            new DepartmentReference(3L, "NN",   "Ngoại ngữ"),
            new DepartmentReference(4L, "DDT",  "Điện - Điện tử"),
            new DepartmentReference(5L, "CK",   "Cơ khí")
    );
}
