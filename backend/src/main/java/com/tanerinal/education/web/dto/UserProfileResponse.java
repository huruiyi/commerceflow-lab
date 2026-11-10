package com.tanerinal.education.web.dto;

import com.tanerinal.education.domain.AppUser;

public record UserProfileResponse(Long id, String firstName, String lastName, String email,
                                  String phone, String role) {
  public static UserProfileResponse from(AppUser user) {
    return new UserProfileResponse(user.getId(), user.getFirstName(), user.getLastName(),
        user.getEmail(), user.getPhone(), user.getRole());
  }
}
