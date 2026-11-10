package com.tanerinal.education;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.tanerinal.education.mapper")
public class EducationBackendApplication {
  public static void main(String[] args) {
    SpringApplication.run(EducationBackendApplication.class, args);
  }
}
