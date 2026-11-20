package vip.fairy.commerceflow.simulator.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("payment_admin_role")
public class PaymentAdminRole {
  /** 数据库主键。 */
  @TableId(type = IdType.AUTO)
  private Long id;
  /** 对外稳定的角色编码。 */
  private String roleCode;
  /** 后台显示的名称。 */
  private String roleName;
  /** 授权层级，PLATFORM 或 MERCHANT。 */
  private String roleScope;
  /** 供管理员识别用途的说明。 */
  private String description;
  /** 是否允许该角色的授权生效。 */
  private boolean enabled;
  /** 是否为不可删除的内置角色。 */
  private boolean systemRole;
  /** 权限配置变更时递增，用于识别过期会话。 */
  private long revision;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getRoleCode() {
    return roleCode;
  }

  public void setRoleCode(String roleCode) {
    this.roleCode = roleCode;
  }

  public String getRoleName() {
    return roleName;
  }

  public void setRoleName(String roleName) {
    this.roleName = roleName;
  }

  public String getRoleScope() {
    return roleScope;
  }

  public void setRoleScope(String roleScope) {
    this.roleScope = roleScope;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isSystemRole() {
    return systemRole;
  }

  public void setSystemRole(boolean systemRole) {
    this.systemRole = systemRole;
  }

  public long getRevision() {
    return revision;
  }

  public void setRevision(long revision) {
    this.revision = revision;
  }
}
