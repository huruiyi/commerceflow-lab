package com.tanerinal.education.web.dto;

import jakarta.validation.constraints.*;

public record PaymentRequest(@NotBlank @Size(max=80) String idempotencyKey, boolean simulateFailure) {}
