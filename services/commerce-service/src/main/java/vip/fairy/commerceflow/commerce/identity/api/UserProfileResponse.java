package vip.fairy.commerceflow.commerce.identity.api;

import vip.fairy.commerceflow.commerce.identity.domain.AppUser;

public record UserProfileResponse(Long id, String firstName, String lastName, String email,
                                  String phone, String role, boolean invoiceEnabled, boolean invoiceOperations) {
  public static UserProfileResponse from(AppUser user, boolean invoiceEnabled, boolean invoiceOperations) {
    return new UserProfileResponse(user.getId(), user.getFirstName(), user.getLastName(),
      user.getEmail(), user.getPhone(), user.getRole(), invoiceEnabled, invoiceOperations);
  }
}
