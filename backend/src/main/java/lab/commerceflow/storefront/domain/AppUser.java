package lab.commerceflow.storefront.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("app_users")
public class AppUser {
  @TableId(type = IdType.AUTO)
  private Long id;

  private String email;
  private String passwordHash;
  private String firstName;
  private String lastName;
  private String phone;
  private String role;
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
