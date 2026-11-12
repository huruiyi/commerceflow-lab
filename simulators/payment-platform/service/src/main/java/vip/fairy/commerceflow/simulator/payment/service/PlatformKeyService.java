package vip.fairy.commerceflow.simulator.payment.service;

import vip.fairy.commerceflow.simulator.payment.domain.PaymentPlatformKey;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentPlatformKeyMapper;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDateTime;
import java.util.Base64;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformKeyService implements ApplicationRunner {
  private final PaymentPlatformKeyMapper keys;
  private final CredentialCipher cipher;

  public PlatformKeyService(PaymentPlatformKeyMapper keys, CredentialCipher cipher) {
    this.keys = keys;
    this.cipher = cipher;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) throws GeneralSecurityException {
    if (keys.selectById(1L) != null) return;
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    PaymentPlatformKey key = new PaymentPlatformKey();
    key.setId(1L);
    key.setKeyVersion(1);
    key.setPublicKey(pem("PUBLIC KEY", pair.getPublic().getEncoded()));
    key.setPrivateKeyCipher(cipher.encrypt(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())));
    key.setActive(true);
    key.setCreatedAt(LocalDateTime.now());
    keys.insert(key);
  }

  public String publicKey() {
    PaymentPlatformKey key = keys.selectById(1L);
    if (key == null) throw new IllegalStateException("Platform key has not been initialized");
    return key.getPublicKey();
  }

  private static String pem(String type, byte[] encoded) {
    return "-----BEGIN " + type + "-----\n"
      + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
      + "\n-----END " + type + "-----\n";
  }
}
