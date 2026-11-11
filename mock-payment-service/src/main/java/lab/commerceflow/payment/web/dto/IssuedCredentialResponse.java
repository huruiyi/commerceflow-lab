package lab.commerceflow.payment.web.dto;

public record IssuedCredentialResponse(MerchantResponse merchant, String appSecret,
    String warning) {
}
