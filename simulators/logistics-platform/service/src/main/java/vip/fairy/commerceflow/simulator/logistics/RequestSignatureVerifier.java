package vip.fairy.commerceflow.simulator.logistics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RequestSignatureVerifier {
  private final LogisticsDataMapper dataMapper;
  private final String appId, secret;
  private final Duration allowedSkew;

  public RequestSignatureVerifier(LogisticsDataMapper dataMapper,
                                  @Value("${logistics-platform.app-id}") String appId,
                                  @Value("${logistics-platform.request-secret}") String secret,
                                  @Value("${logistics-platform.allowed-skew:5m}") Duration allowedSkew) {
    this.dataMapper = dataMapper;
    this.appId = appId;
    this.secret = secret;
    this.allowedSkew = allowedSkew;
  }

  public void verify(String suppliedAppId, String timestamp, String nonce, String signature, String body) {
    try {
      if (!appId.equals(suppliedAppId) || nonce == null || nonce.isBlank()) reject();
      long epoch = Long.parseLong(timestamp);
      if (Math.abs(Instant.now().getEpochSecond() - epoch) > allowedSkew.toSeconds()) reject();
      String canonical = suppliedAppId + "\n" + timestamp + "\n" + nonce + "\n" + sha256(body);
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
      if (signature == null || !MessageDigest.isEqual(expected, Base64.getDecoder().decode(signature))) reject();
      dataMapper.insertSignedNonce(suppliedAppId, nonce, epoch);
    } catch (DuplicateKeyException exception) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "REPLAYED_REQUEST");
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (Exception exception) {
      reject();
    }
  }

  private String sha256(String body) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
  }

  private void reject() {
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_REQUEST_SIGNATURE");
  }
}
