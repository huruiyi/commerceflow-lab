package com.tanerinal.payment.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateChannelTransactionRequest(
    @NotBlank @Size(max = 100) String merchantRequestNo,
    @NotBlank @Pattern(regexp = "ALIPAY|WECHAT_PAY|UNION_PAY") String channel,
    @NotBlank @Size(max = 200) String subject,
    @DecimalMin("0.01") BigDecimal amount,
    @NotBlank @Pattern(regexp = "CNY") String currency,
    @Pattern(regexp = "NONE|CREATE_FAILURE|DELAYED_SUCCESS|NOTIFY_LOST") String faultScenario) {
}
