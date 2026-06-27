package com.ulp.features.admin.users.dto;

import com.ulp.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create-account form for {@code /admin/users}. All required fields are
 * validated with bean-validation annotations; the service additionally
 * normalises {@code email} (trim + lowercase) before persistence.
 */
public record CreateUserForm(
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

        boolean emailVerified,

        @NotBlank(message = "Mật khẩu tạm thời không được để trống")
        String password
) {
    public static CreateUserForm empty() {
        return new CreateUserForm(null, null, Role.LECTURER, null, null, null, false, null);
    }
}
