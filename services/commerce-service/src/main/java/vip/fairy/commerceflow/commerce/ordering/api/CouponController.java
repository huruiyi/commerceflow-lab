package vip.fairy.commerceflow.commerce.ordering.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import vip.fairy.commerceflow.commerce.ordering.application.CouponService;
import vip.fairy.commerceflow.commerce.ordering.domain.CouponCampaign;
import vip.fairy.commerceflow.commerce.ordering.domain.CustomerCoupon;

@RestController
public class CouponController {

  private final CouponService coupons;
  private final CurrentUserService users;

  public CouponController(CouponService coupons, CurrentUserService users) {
    this.coupons = coupons;
    this.users = users;
  }

  @GetMapping("/api/merchant/coupon-campaigns")
  @PreAuthorize("hasRole('MERCHANT')")
  List<CouponCampaign> merchantCampaigns(Authentication actor) {
    return coupons.campaigns(users.require(actor).getId());
  }

  @PostMapping("/api/merchant/coupon-campaigns")
  @PreAuthorize("hasRole('MERCHANT')")
  @ResponseStatus(HttpStatus.CREATED)
  CouponCampaign create(Authentication actor, @Valid @RequestBody CampaignRequest request) {
    return coupons.create(users.require(actor).getId(), request.title(), request.minimumAmount(),
      request.discountAmount(), request.supply(), request.startsAt(), request.expiresAt());
  }

  @PostMapping("/api/merchant/coupon-campaigns/{id}/disable")
  @PreAuthorize("hasRole('MERCHANT')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void disable(Authentication actor, @PathVariable UUID id) {
    coupons.disable(users.require(actor).getId(), id.toString());
  }

  @GetMapping("/api/coupons/campaigns")
  @PreAuthorize("hasRole('CUSTOMER')")
  List<CouponCampaign> campaigns() {
    return coupons.campaigns();
  }

  @GetMapping("/api/coupons")
  @PreAuthorize("hasRole('CUSTOMER')")
  List<CustomerCoupon> wallet(Authentication actor) {
    return coupons.wallet(users.require(actor).getId());
  }

  @PostMapping("/api/coupons/campaigns/{id}/claim")
  @PreAuthorize("hasRole('CUSTOMER')")
  CustomerCoupon claim(Authentication actor, @PathVariable UUID id) {
    return coupons.claim(users.require(actor).getId(), id.toString());
  }

  record CampaignRequest(@NotBlank @Size(max = 100) String title,
                         @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal minimumAmount,
                         @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal discountAmount,
                         @Positive @Max(10000) int supply,
                         @NotNull Instant startsAt, @NotNull Instant expiresAt) {
  }
}
