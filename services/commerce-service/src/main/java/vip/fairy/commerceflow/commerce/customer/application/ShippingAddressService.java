package vip.fairy.commerceflow.commerce.customer.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import vip.fairy.commerceflow.commerce.cart.infrastructure.CartMapper;
import vip.fairy.commerceflow.commerce.customer.api.ShippingAddressRequest;
import vip.fairy.commerceflow.commerce.customer.api.ShippingAddressResponse;
import vip.fairy.commerceflow.commerce.customer.domain.ShippingAddress;
import vip.fairy.commerceflow.commerce.customer.infrastructure.ShippingAddressMapper;
import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ShippingAddressService {

  private final ShippingAddressMapper addresses;
  private final CartMapper carts;

  public ShippingAddressService(ShippingAddressMapper addresses, CartMapper carts) {
    this.addresses = addresses;
    this.carts = carts;
  }

  public List<ShippingAddressResponse> list(AppUser user) {
    return addresses.selectList(new LambdaQueryWrapper<ShippingAddress>()
        .eq(ShippingAddress::getUserId, user.getId())
        .orderByDesc(ShippingAddress::getDefaultAddress)
        .orderByAsc(ShippingAddress::getId))
      .stream()
      .map(ShippingAddressResponse::from)
      .toList();
  }

  @Transactional
  public ShippingAddressResponse create(AppUser user, ShippingAddressRequest request) {
    boolean first = addresses.selectCount(new LambdaQueryWrapper<ShippingAddress>()
      .eq(ShippingAddress::getUserId, user.getId())) == 0;
    boolean makeDefault = first || request.isDefault();
    if (makeDefault) {
      addresses.clearDefault(user.getId());
    }
    ShippingAddress address = new ShippingAddress();
    address.setUserId(user.getId());
    apply(address, request);
    address.setDefault(makeDefault);
    address.setCreatedAt(Instant.now());
    address.setUpdatedAt(Instant.now());
    addresses.insert(address);
    return ShippingAddressResponse.from(address);
  }

  @Transactional
  public ShippingAddressResponse update(AppUser user, Long id, ShippingAddressRequest request) {
    ShippingAddress address = require(user, id);
    boolean makeDefault = request.isDefault() || address.isDefault();
    if (request.isDefault()) {
      addresses.clearDefault(user.getId());
    }
    apply(address, request);
    address.setDefault(makeDefault);
    address.setUpdatedAt(Instant.now());
    addresses.updateById(address);
    return ShippingAddressResponse.from(address);
  }

  @Transactional
  public ShippingAddressResponse setDefault(AppUser user, Long id) {
    ShippingAddress address = require(user, id);
    addresses.clearDefault(user.getId());
    address.setDefault(true);
    address.setUpdatedAt(Instant.now());
    addresses.updateById(address);
    return ShippingAddressResponse.from(address);
  }

  @Transactional
  public void delete(AppUser user, Long id) {
    ShippingAddress address = require(user, id);
    addresses.deleteById(id);
    if (address.isDefault()) {
      addresses.promoteFirst(user.getId());
    }
  }

  @Transactional
  public ShippingAddressResponse selectForCheckout(AppUser user, Long id) {
    ShippingAddress address = require(user, id);
    carts.ensureCart(user.getId());
    carts.selectAddress(user.getId(), id);
    return ShippingAddressResponse.from(address);
  }

  public Long selectedAddressId(AppUser user) {
    return carts.selectedAddressId(user.getId());
  }

  private ShippingAddress require(AppUser user, Long id) {
    ShippingAddress address = addresses.selectById(id);
    if (address == null || !address.getUserId().equals(user.getId())) {
      throw new ResourceNotFoundException("Shipping address not found");
    }
    return address;
  }

  private void apply(ShippingAddress address, ShippingAddressRequest request) {
    address.setRecipientName(request.recipientName().trim());
    address.setPhone(request.phone().trim());
    address.setCountryCode(request.countryCode().trim().toUpperCase());
    address.setStateProvince(request.stateProvince().trim());
    address.setCity(request.city().trim());
    address.setDistrict(request.district().trim());
    address.setAddressLine1(request.addressLine1().trim());
    address.setAddressLine2(request.addressLine2() == null ? "" : request.addressLine2().trim());
    address.setPostalCode(request.postalCode().trim());
    address.setLabel(request.label().trim());
  }
}
