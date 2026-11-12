package vip.fairy.commerceflow.simulator.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import vip.fairy.commerceflow.simulator.payment.domain.PaymentMerchant;
import vip.fairy.commerceflow.simulator.payment.mapper.PaymentMerchantMapper;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DemoMerchantInitializer implements ApplicationRunner {
  private final PaymentMerchantMapper merchants;
  private final CredentialCipher cipher;

  public DemoMerchantInitializer(PaymentMerchantMapper m, CredentialCipher c) {
    merchants = m;
    cipher = c;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments a) {
    for (String channel : List.of("alipay", "wechat-pay", "union-pay")) {
      String appId = "commerceflow-" + channel;
      if (merchants.selectCount(new LambdaQueryWrapper<PaymentMerchant>().eq(PaymentMerchant::getAppId, appId)) > 0)
        continue;
      String secret = "demo-" + channel + "-secret";
      PaymentMerchant m = new PaymentMerchant();
      m.setMerchantNo("CFL-" + channel.toUpperCase());
      m.setDisplayName("CommerceFlow " + channel);
      m.setAppId(appId);
      m.setSecretCipher(cipher.encrypt(secret));
      m.setSecretLastFour(secret.substring(secret.length() - 4));
      m.setCredentialVersion(1);
      m.setSigningAlgorithm("HMAC_SHA256");
      m.setNotifyUrl("http://commerce-service:8080/api/payment-notifications/callback");
      m.setEnabled(true);
      m.setCreatedBy(1L);
      m.setUpdatedBy(1L);
      m.setCreatedAt(LocalDateTime.now());
      m.setUpdatedAt(LocalDateTime.now());
      merchants.insert(m);
      merchants.insertCallbackEndpoint(m.getId(), m.getNotifyUrl());
    }
  }
}
