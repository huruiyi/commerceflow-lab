package vip.fairy.commerceflow.commerce.merchant.application;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.StoreTradeV1;
import vip.fairy.commerceflow.commerce.merchant.domain.MerchantStore;
import vip.fairy.commerceflow.commerce.merchant.infrastructure.MerchantStoreMapper;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

@Service
public class MerchantStoreService implements StoreTradeV1 {

  private final MerchantStoreMapper stores;

  public MerchantStoreService(MerchantStoreMapper stores) {
    this.stores = stores;
  }

  public MerchantStore find(Long owner) {
    return stores.find(owner);
  }

  public List<MerchantStore> list() {
    return stores.list();
  }

  public MerchantStore publicStore(Long owner) {
    var store = find(owner);
    if (!active(store)) throw new ResourceNotFoundException("Store not found");
    return store;
  }

  @Transactional
  public MerchantStore edit(Long owner, String name, String description) {
    var store = stores.lock(owner);
    if (store == null) stores.insert(owner, name.strip(), description.strip());
    else stores.edit(owner, name.strip(), description.strip());
    return stores.find(owner);
  }

  @Transactional
  public MerchantStore decide(Long owner, Long admin, String status) {
    var store = stores.lock(owner);
    if (store == null) throw new ResourceNotFoundException("Store not found");
    if (store.status().equals(status)) return store;
    boolean allowed = switch (status) {
      case "ACTIVE" -> Set.of("PENDING", "DISABLED").contains(store.status());
      case "REJECTED" -> "PENDING".equals(store.status());
      case "DISABLED" -> "ACTIVE".equals(store.status());
      default -> false;
    };
    if (!allowed) throw new BusinessRuleException("Invalid store transition");
    stores.decide(owner, status, admin);
    return stores.find(owner);
  }

  @Override
  public Set<Long> activeMerchantIds() {
    return Set.copyOf(stores.activeIds());
  }

  @Override
  public void requireActive(Long merchantId, boolean lock) {
    if (!active(lock ? stores.lock(merchantId) : stores.find(merchantId))) {
      throw new BusinessRuleException("Store is not open for new transactions");
    }
  }

  private boolean active(MerchantStore store) {
    return store != null && "ACTIVE".equals(store.status());
  }
}
