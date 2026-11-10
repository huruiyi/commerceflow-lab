package com.tanerinal.payment.web.dto;

public record IssuedCredentialResponse(MerchantResponse merchant, String appSecret,
    String warning) {
}
