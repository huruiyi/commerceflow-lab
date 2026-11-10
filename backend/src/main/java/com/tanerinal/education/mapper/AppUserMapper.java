package com.tanerinal.education.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tanerinal.education.domain.AppUser;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AppUserMapper extends BaseMapper<AppUser> {
  @Select("""
      SELECT r.code
      FROM roles r
      JOIN user_roles ur ON ur.role_id = r.id
      WHERE ur.user_id = #{userId}
      ORDER BY r.code
      """)
  List<String> selectRoleCodes(Long userId);
}
