package vip.fairy.commerceflow.fulfillment.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InternalSignatureTest {

  @Test
  void signatureBindsBodyRouteAndTime() throws Exception {
    String secret = "local-test-secret";
    String path = "/internal/v1/fulfillment/queries";
    String id = "af65eb25-4840-4a88-9222-e76a79c94e3c";
    String body = "{}";
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
      .digest(body.getBytes(StandardCharsets.UTF_8)));
    Mac hmac = Mac.getInstance("HmacSHA256");
    hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String signed = HexFormat.of().formatHex(hmac.doFinal(
      String.join("\n", "POST", path, "1000", id, hash).getBytes(StandardCharsets.UTF_8)));
    var verifier = new InternalSignature(secret);
    assertThat(verifier.verify("POST", path, body, "1000", id, hash, signed, 1000)).isTrue();
    assertThat(verifier.verify("POST", path, "{ }", "1000", id, hash, signed, 1000)).isFalse();
    assertThat(verifier.verify("POST", path + "/other", body, "1000", id, hash, signed, 1000)).isFalse();
    assertThat(verifier.verify("POST", path, body, "1000", id, hash, signed, 1301)).isFalse();
  }
}
