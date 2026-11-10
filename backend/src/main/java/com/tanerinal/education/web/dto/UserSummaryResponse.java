package com.tanerinal.education.web.dto;

import com.tanerinal.education.domain.AppUser;

public record UserSummaryResponse(Long id, String name, String email, String phone) {
  public static UserSummaryResponse from(AppUser user) {
    return new UserSummaryResponse(user.getId(), user.getFirstName() + " " + user.getLastName(),
        user.getEmail(), user.getPhone());
  }
}
