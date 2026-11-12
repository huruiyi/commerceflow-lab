package vip.fairy.commerceflow.simulator.payment.web.dto;

public record IssuedCredentialResponse(MerchantResponse merchant, String appSecret,
                                       String warning) {
}
