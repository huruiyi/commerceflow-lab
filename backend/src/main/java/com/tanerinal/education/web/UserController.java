package com.tanerinal.education.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tanerinal.education.domain.AppUser;
import com.tanerinal.education.mapper.AppUserMapper;
import com.tanerinal.education.web.dto.UserSummaryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {
  private final AppUserMapper users;

  public UserController(AppUserMapper users) {
    this.users = users;
  }

  @GetMapping
  List<UserSummaryResponse> list() {
    return users.selectList(new LambdaQueryWrapper<AppUser>()
            .orderByAsc(AppUser::getFirstName, AppUser::getLastName)).stream()
        .map(UserSummaryResponse::from)
        .toList();
  }
}
