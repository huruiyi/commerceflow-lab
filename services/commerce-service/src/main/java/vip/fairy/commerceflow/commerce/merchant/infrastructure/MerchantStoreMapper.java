package vip.fairy.commerceflow.commerce.merchant.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.commerce.merchant.domain.MerchantStore;

public interface MerchantStoreMapper {

  MerchantStore find(Long id);

  MerchantStore lock(Long id);

  List<MerchantStore> list();

  List<Long> activeIds();

  void insert(@Param("id") Long merchantId, @Param("name") String name, @Param("description") String description);

  void edit(@Param("id") Long merchantId, @Param("name") String name, @Param("description") String description);

  void decide(@Param("id") Long merchantId, @Param("status") String status, @Param("admin") Long admin);
}
