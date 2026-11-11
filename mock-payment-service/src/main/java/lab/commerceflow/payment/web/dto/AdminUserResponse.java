package lab.commerceflow.payment.web.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AdminUserResponse(Long id, String username, String displayName, String email, String mobile,
    String status, boolean accountLocked, LocalDateTime lockedUntil, boolean passwordChangeRequired,
    boolean mfaRequired, boolean mfaEnabled, List<String> roles, Long createdBy, LocalDateTime createdAt,
    LocalDateTime lastLoginAt) {
}
