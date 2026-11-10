package com.tanerinal.payment;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.tanerinal.payment.mapper")
@SpringBootApplication
public class MockPaymentServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(MockPaymentServiceApplication.class, args);
  }
}
