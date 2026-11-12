package vip.fairy.commerceflow.simulator.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class CredentialCipherTest {
  private static final String KEY = Base64.getEncoder()
    .encodeToString("12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8));

  @Test
  void encryptsWithRandomIvAndDecryptsRoundTrip() {
    CredentialCipher cipher = new CredentialCipher(KEY);

    String first = cipher.encrypt("merchant-secret");
    String second = cipher.encrypt("merchant-secret");

    assertThat(first).isNotEqualTo(second).doesNotContain("merchant-secret");
    assertThat(cipher.decrypt(first)).isEqualTo("merchant-secret");
    assertThat(cipher.decrypt(second)).isEqualTo("merchant-secret");
  }

  @Test
  void rejectsIncorrectMasterKeyLength() {
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
    assertThatThrownBy(() -> new CredentialCipher(shortKey))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("32 bytes");
  }
}
