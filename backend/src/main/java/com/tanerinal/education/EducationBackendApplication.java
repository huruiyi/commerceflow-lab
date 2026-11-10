package com.tanerinal.education;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.tanerinal.education.mapper")
@EnableMethodSecurity
public class EducationBackendApplication {
  public static void main(String[] args) {
    SpringApplication.run(EducationBackendApplication.class, args);
  }
}
