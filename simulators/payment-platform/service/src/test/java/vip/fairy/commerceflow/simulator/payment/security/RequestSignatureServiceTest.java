package vip.fairy.commerceflow.simulator.payment.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class RequestSignatureServiceTest {

  @Test
  void canonicalRequestHasStableFieldOrder() {
    assertThat(RequestSignatureService.canonical("post", "/api/channel/transactions", "100", "nonce",
      "request", "hash")).isEqualTo("POST\n/api/channel/transactions\n100\nnonce\nrequest\nhash");
  }

  @Test
  void sha256UsesLowercaseHex() {
    assertThat(RequestSignatureService.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
      .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }
}
