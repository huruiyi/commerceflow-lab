package vip.fairy.commerceflow.commerce.identity.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import vip.fairy.commerceflow.commerce.identity.domain.AppUser;

import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.commerce.identity.api.UserSummaryResponse;

import java.util.List;

public interface AppUserMapper extends BaseMapper<AppUser> {

  List<String> selectRoleCodes(Long userId);

  int grantMerchant(Long userId);

  long countForAdmin(@Param("term") String term);

  List<UserSummaryResponse> pageForAdmin(@Param("term") String term, @Param("size") int size,
                                        @Param("offset") long offset);
}
