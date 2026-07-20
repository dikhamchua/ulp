package com.ulp.features.admin.users.dto;

import com.ulp.entities.User;
import com.ulp.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Edit-account form for {@code /admin/users}. Mirrors {@link CreateUserForm}
 * but without the password field — admins use the dedicated reset-password
 * modal flow for that.
 */
public record EditUserForm(
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không hợp lệ")
        @Size(max = 255)
        String email,

        @NotBlank(message = "Họ tên không được để trống")
        @Size(max = 150)
        String fullName,

        @NotNull(message = "Vai trò không được để trống")
        Role role,

        Long departmentId,

        @Size(max = 20, message = "Số điện thoại tối đa 20 ký tự")
        String phone,

        String bio,

        boolean emailVerified
) {
    public static EditUserForm empty() {
        return new EditUserForm(null, null, Role.LECTURER, null, null, null, false);
    }

    /** Build an edit form pre-filled with the supplied user's current values. */
    public static EditUserForm fromUser(User u) {
        return new EditUserForm(
                u.getEmail(),
                u.getFullName(),
                u.getRole(),
                u.getDepartmentId(),
                u.getPhone(),
                u.getBio(),
                u.isEmailVerified()
        );
    }
}
