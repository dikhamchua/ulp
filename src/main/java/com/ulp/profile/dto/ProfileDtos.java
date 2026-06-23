package com.ulp.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** DTOs for the profile feature — update user information and change password. */
public class ProfileDtos {

    public record ProfileUpdateRequest(
            @NotBlank @Size(min = 2, max = 100) String fullName,
            @Size(max = 500) String bio,
            @Size(max = 20) String phone
    ) {}

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 6, max = 64) String newPassword,
            @NotBlank String confirmPassword
    ) {}
}
