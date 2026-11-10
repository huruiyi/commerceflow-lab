package com.tanerinal.payment.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Set;

public record CreateAdminUserRequest(
    @NotBlank @Size(min = 4, max = 64) String username,
    @NotBlank @Size(min = 12, max = 128) String password,
    @NotBlank @Size(max = 100) String displayName,
    @NotBlank @Email @Size(max = 128) String email,
    @Size(max = 32) String mobile,
    @NotEmpty Set<String> roleCodes,
    boolean requireMfa,
    boolean requirePasswordChange) {
}
