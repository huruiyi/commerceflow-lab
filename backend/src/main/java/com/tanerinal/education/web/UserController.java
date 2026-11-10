package com.tanerinal.education.web;

import com.tanerinal.education.repository.AppUserRepository;
import com.tanerinal.education.web.dto.UserSummaryResponse;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {
  private final AppUserRepository users;

  public UserController(AppUserRepository users) {
    this.users = users;
  }

  @GetMapping
  List<UserSummaryResponse> list() {
    return users.findAll(Sort.by("firstName", "lastName")).stream()
        .map(UserSummaryResponse::from)
        .toList();
  }
}
