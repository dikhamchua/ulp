package com.ulp.features.admin.categories.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * DTOs for the {@code /admin/categories} screen. Entities never leak past the
 * service boundary — the controller and templates only see these records.
 */
public final class CategoryDtos {

    private CategoryDtos() {
        // holder for the nested DTO records
    }

    /**
     * A flat view row used to render the two-level tree. Parent rows carry
     * their {@code children}; child rows carry an empty list. {@code childCount}
     * and {@code courseCount} drive the delete-guard hints shown in the UI.
     */
    public record CategoryRow(
            Long id,
            String name,
            String slug,
            String description,
            boolean active,
            Long parentId,
            long childCount,
            long courseCount,
            List<CategoryRow> children
    ) {
        /** True when this row is a top-level category (no parent). */
        public boolean isParent() {
            return parentId == null;
        }
    }

    /**
     * A lightweight option for the parent dropdown (top-level categories only).
     */
    public record ParentOption(Long id, String name) {
    }

    /**
     * Create/edit form for a category. Slug is auto-generated from {@code name}
     * in the service, so it is not a form field. {@code parentId} is optional —
     * {@code null} means a top-level category.
     */
    public record CategoryForm(
            @NotBlank(message = "Tên danh mục không được để trống")
            @Size(max = 150, message = "Tên danh mục tối đa 150 ký tự")
            String name,

            @Size(max = 65535, message = "Mô tả quá dài")
            String description,

            Long parentId,

            boolean active
    ) {
        /** Blank form for the create screen — active by default. */
        public static CategoryForm empty() {
            return new CategoryForm(null, null, null, true);
        }
    }
}
