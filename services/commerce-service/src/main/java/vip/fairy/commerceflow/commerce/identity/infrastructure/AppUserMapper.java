package vip.fairy.commerceflow.commerce.identity.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import vip.fairy.commerceflow.commerce.identity.domain.AppUser;

import java.util.List;

public interface AppUserMapper extends BaseMapper<AppUser> {

  List<String> selectRoleCodes(Long userId);
}
