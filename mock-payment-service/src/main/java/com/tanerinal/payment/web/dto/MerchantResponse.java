package com.tanerinal.payment.web.dto;

import java.time.LocalDateTime;

public record MerchantResponse(Long id, String merchantNo, String displayName, String appId,
    String secretMasked, int credentialVersion, String merchantPublicKey, String notifyUrl,
    boolean enabled, Long createdBy, LocalDateTime createdAt) {
}
