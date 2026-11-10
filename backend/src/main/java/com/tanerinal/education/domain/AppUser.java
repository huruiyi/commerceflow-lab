package com.tanerinal.education.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "app_users")
public class AppUser {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 190)
  private String email;

  @Column(nullable = false, length = 100)
  private String passwordHash;

  @Column(nullable = false, length = 80)
  private String firstName;

  @Column(nullable = false, length = 80)
  private String lastName;

  @Column(nullable = false, length = 40)
  private String phone;

  @Column(nullable = false, length = 30)
  private String role;

  @Column(nullable = false)
  private boolean enabled;

  protected AppUser() {
  }

  public AppUser(String email, String passwordHash, String firstName, String lastName,
                 String phone, String role, boolean enabled) {
    this.email = email;
    this.passwordHash = passwordHash;
    this.firstName = firstName;
    this.lastName = lastName;
    this.phone = phone;
    this.role = role;
    this.enabled = enabled;
  }

  public Long getId() { return id; }
  public String getEmail() { return email; }
  public String getPasswordHash() { return passwordHash; }
  public String getFirstName() { return firstName; }
  public String getLastName() { return lastName; }
  public String getPhone() { return phone; }
  public String getRole() { return role; }
  public boolean isEnabled() { return enabled; }
}
