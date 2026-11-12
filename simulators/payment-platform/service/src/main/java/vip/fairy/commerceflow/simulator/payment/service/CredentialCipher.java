package vip.fairy.commerceflow.simulator.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CredentialCipher {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final SecretKeySpec key;

  public CredentialCipher(@Value("${payment-platform.credential-master-key}") String base64Key) {
    byte[] decoded = Base64.getDecoder().decode(base64Key);
    if (decoded.length != 32) {
      throw new IllegalArgumentException("PAYMENT_CREDENTIAL_MASTER_KEY must decode to exactly 32 bytes");
    }
    key = new SecretKeySpec(decoded, "AES");
  }

  public String encrypt(String plainText) {
    try {
      byte[] iv = new byte[12];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
      byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
      byte[] result = new byte[iv.length + encrypted.length];
      System.arraycopy(iv, 0, result, 0, iv.length);
      System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
      return Base64.getEncoder().encodeToString(result);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Credential encryption failed", exception);
    }
  }

  public String decrypt(String cipherText) {
    try {
      byte[] value = Base64.getDecoder().decode(cipherText);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, value, 0, 12));
      return new String(cipher.doFinal(value, 12, value.length - 12), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Credential decryption failed", exception);
    }
  }
}
