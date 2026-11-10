package com.tanerinal.payment.web.dto;

import java.util.List;

public record SessionResponse(Long userId, String username, String displayName, String email,
    List<String> authorities, boolean passwordChangeRequired, boolean mfaRequired, boolean mfaEnabled) {
}
