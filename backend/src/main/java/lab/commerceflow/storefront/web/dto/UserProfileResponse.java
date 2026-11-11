package lab.commerceflow.storefront.web.dto;

import lab.commerceflow.storefront.domain.AppUser;

public record UserProfileResponse(Long id, String firstName, String lastName, String email,
                                  String phone, String role) {
  public static UserProfileResponse from(AppUser user) {
    return new UserProfileResponse(user.getId(), user.getFirstName(), user.getLastName(),
        user.getEmail(), user.getPhone(), user.getRole());
  }
}
