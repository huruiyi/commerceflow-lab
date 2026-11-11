package lab.commerceflow.payment.web.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CreateChannelRefundRequest(
    @NotBlank @Size(max=100)String merchantRefundNo,
    @NotBlank @Size(max=80)String channelTransactionNo,
    @NotNull @DecimalMin("0.01")BigDecimal amount,
    @NotBlank @Pattern(regexp="[A-Z]{3}")String currency,
    @NotBlank @Size(max=50)String reasonCode,
    @Size(max=40)String faultScenario) {}
