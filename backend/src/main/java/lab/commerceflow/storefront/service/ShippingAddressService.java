package lab.commerceflow.storefront.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lab.commerceflow.storefront.domain.AppUser;
import lab.commerceflow.storefront.domain.ShippingAddress;
import lab.commerceflow.storefront.mapper.CartMapper;
import lab.commerceflow.storefront.mapper.ShippingAddressMapper;
import lab.commerceflow.storefront.web.dto.ShippingAddressRequest;
import lab.commerceflow.storefront.web.dto.ShippingAddressResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Service
public class ShippingAddressService {
  private final ShippingAddressMapper addresses;
  private final CartMapper carts;
  public ShippingAddressService(ShippingAddressMapper addresses, CartMapper carts) {
    this.addresses = addresses; this.carts = carts;
  }

  public List<ShippingAddressResponse> list(AppUser user) {
    return addresses.selectList(new QueryWrapper<ShippingAddress>().eq("user_id", user.getId())
        .orderByDesc("is_default").orderByAsc("id")).stream().map(ShippingAddressResponse::from).toList();
  }

  @Transactional
  public ShippingAddressResponse create(AppUser user, ShippingAddressRequest request) {
    boolean first = addresses.selectCount(new QueryWrapper<ShippingAddress>().eq("user_id", user.getId())) == 0;
    boolean makeDefault = first || request.isDefault();
    if (makeDefault) addresses.clearDefault(user.getId());
    ShippingAddress address = new ShippingAddress();
    address.setUserId(user.getId()); apply(address, request); address.setDefault(makeDefault);
    address.setCreatedAt(Instant.now()); address.setUpdatedAt(Instant.now()); addresses.insert(address);
    return ShippingAddressResponse.from(address);
  }

  @Transactional
  public ShippingAddressResponse update(AppUser user, Long id, ShippingAddressRequest request) {
    ShippingAddress address = require(user, id);
    boolean makeDefault = request.isDefault() || address.isDefault();
    if (request.isDefault()) addresses.clearDefault(user.getId());
    apply(address, request); address.setDefault(makeDefault); address.setUpdatedAt(Instant.now()); addresses.updateById(address);
    return ShippingAddressResponse.from(address);
  }

  @Transactional
  public ShippingAddressResponse setDefault(AppUser user, Long id) {
    ShippingAddress address = require(user, id); addresses.clearDefault(user.getId());
    address.setDefault(true); address.setUpdatedAt(Instant.now()); addresses.updateById(address);
    return ShippingAddressResponse.from(address);
  }

  @Transactional
  public void delete(AppUser user, Long id) {
    ShippingAddress address = require(user, id); addresses.deleteById(id);
    if (address.isDefault()) addresses.promoteFirst(user.getId());
  }

  @Transactional
  public ShippingAddressResponse selectForCheckout(AppUser user, Long id) {
    ShippingAddress address = require(user, id); carts.ensureCart(user.getId()); carts.selectAddress(user.getId(), id);
    return ShippingAddressResponse.from(address);
  }

  public Long selectedAddressId(AppUser user) { return carts.selectedAddressId(user.getId()); }

  private ShippingAddress require(AppUser user, Long id) {
    ShippingAddress address = addresses.selectById(id);
    if (address == null || !address.getUserId().equals(user.getId())) throw new ResourceNotFoundException("Shipping address not found");
    return address;
  }

  private void apply(ShippingAddress a, ShippingAddressRequest r) {
    a.setRecipientName(r.recipientName().trim()); a.setPhone(r.phone().trim());
    a.setCountryCode(r.countryCode().trim().toUpperCase()); a.setStateProvince(r.stateProvince().trim());
    a.setCity(r.city().trim()); a.setDistrict(r.district().trim()); a.setAddressLine1(r.addressLine1().trim());
    a.setAddressLine2(r.addressLine2() == null ? "" : r.addressLine2().trim());
    a.setPostalCode(r.postalCode().trim()); a.setLabel(r.label().trim());
  }
}
