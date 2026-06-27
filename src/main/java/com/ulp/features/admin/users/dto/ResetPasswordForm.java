package com.ulp.features.admin.users.dto;

import jakarta.validation.constraints.NotBlank;

/** Admin reset-password modal submission on {@code /admin/users}. */
public record ResetPasswordForm(
        @NotBlank(message = "Mật khẩu mới không được để trống")
        String newPassword
) {}
