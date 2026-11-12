package vip.fairy.commerceflow.commerce.identity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 商城用户账号持久化实体，承载登录身份与基础资料。
 */
@TableName("app_users")
public class AppUser {
  /**
   * 数据库主键；仅用于内部关联，不作为对外业务编号。
   */
  @TableId(type = IdType.AUTO)
  private Long id;

  /**
   * 用于登录或联系的邮箱地址。
   */
  private String email;
  /**
   * 经密码编码器处理的口令哈希，不保存明文。
   */
  private String passwordHash;
  /**
   * 用户名。
   */
  private String firstName;
  /**
   * 用户姓。
   */
  private String lastName;
  /**
   * 收货或账号联系电话。
   */
  private String phone;
  /**
   * 用户主角色代码，用于前端展示和基础授权。
   */
  private String role;
  /**
   * 记录或账号是否可用。
   */
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

  public Long getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getFirstName() {
    return firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public String getPhone() {
    return phone;
  }

  public String getRole() {
    return role;
  }

  public boolean isEnabled() {
    return enabled;
  }
}
