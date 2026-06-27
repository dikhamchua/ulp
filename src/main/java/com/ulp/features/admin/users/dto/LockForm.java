package com.ulp.features.admin.users.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Lock-with-reason modal submission on {@code /admin/users}. */
public record LockForm(
        @NotBlank(message = "Lý do khoá tài khoản không được để trống")
        @Size(max = 255)
        String lockedReason
) {}
