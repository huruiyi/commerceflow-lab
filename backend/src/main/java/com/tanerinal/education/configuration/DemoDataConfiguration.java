package com.tanerinal.education.configuration;

import com.tanerinal.education.domain.AppUser;
import com.tanerinal.education.domain.Product;
import com.tanerinal.education.repository.AppUserRepository;
import com.tanerinal.education.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Component
public class DemoDataConfiguration implements ApplicationRunner {
  private final AppUserRepository users;
  private final ProductRepository products;
  private final PasswordEncoder passwordEncoder;
  private final String demoEmail;
  private final String demoPassword;

  public DemoDataConfiguration(AppUserRepository users, ProductRepository products,
                               PasswordEncoder passwordEncoder,
                               @Value("${demo.user.email}") String demoEmail,
                               @Value("${demo.user.password}") String demoPassword) {
    this.users = users;
    this.products = products;
    this.passwordEncoder = passwordEncoder;
    this.demoEmail = demoEmail;
    this.demoPassword = demoPassword;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (users.count() == 0) {
      users.save(new AppUser(demoEmail, passwordEncoder.encode(demoPassword), "Demo", "Student",
          "+90 555 000 0001", "USER", true));
      String disabledPassword = passwordEncoder.encode(UUID.randomUUID().toString());
      users.saveAll(List.of(
          new AppUser("ada@example.com", disabledPassword, "Ada", "Lovelace", "+44 20 0000 0001", "USER", false),
          new AppUser("grace@example.com", disabledPassword, "Grace", "Hopper", "+1 212 000 0002", "USER", false),
          new AppUser("alan@example.com", disabledPassword, "Alan", "Turing", "+44 20 0000 0003", "USER", false)));
    }

    if (products.count() == 0) {
      products.saveAll(List.of(
          product("Learning Laptop", "A practical laptop for coding labs", "Java, Angular and Docker learning workstation.", "1299.00", "laptop"),
          product("Mechanical Keyboard", "Comfortable keyboard for long sessions", "Compact mechanical keyboard with tactile switches.", "89.90", "keyboard"),
          product("Developer Monitor", "27-inch QHD display", "A clear display for IDE, terminal and browser side by side.", "349.50", "monitor"),
          product("USB-C Dock", "Connect the complete workspace", "Dock with display, network and USB connectivity.", "119.00", "dock"),
          product("Learning Headset", "Headset for online courses", "Closed-back headset with microphone.", "69.00", "headset"),
          product("Spring Notebook", "Keep architecture notes", "A paper notebook for flows, APIs and database diagrams.", "12.50", "notebook")));
    }
  }

  private Product product(String name, String brief, String description, String price, String seed) {
    return new Product(name, brief, description, new BigDecimal(price),
        "https://picsum.photos/seed/" + seed + "/800/500",
        "https://picsum.photos/seed/" + seed + "/160/100");
  }
}
