package vip.fairy.commerceflow.catalog.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.catalog.domain.Product;

import java.math.BigDecimal;
import java.util.List;

public interface ProductMapper extends BaseMapper<Product> {

  Product lockListing(long productId);

  List<Product> selectForMerchant(Long merchantId);

  long countForMerchant(@Param("merchantId") Long merchantId);

  List<Product> selectPageForMerchant(@Param("merchantId") Long merchantId, @Param("size") int size,
                                      @Param("offset") long offset);

  Product findCreation(@Param("merchantId") Long merchantId, @Param("requestId") String requestId);

  int insertListing(Product product);

  int updateProfile(@Param("merchantId") Long merchantId, @Param("productId") Long productId,
                    @Param("expectedVersion") long expectedVersion, @Param("name") String name,
                    @Param("brief") String brief, @Param("description") String description,
                    @Param("imageUrl") String imageUrl, @Param("thumbnailUrl") String thumbnailUrl);

  int updatePrice(@Param("merchantId") Long merchantId, @Param("productId") Long productId,
                  @Param("expectedVersion") long expectedVersion, @Param("price") BigDecimal price);

  int updateStatus(@Param("merchantId") Long merchantId, @Param("productId") Long productId,
                   @Param("expectedVersion") long expectedVersion, @Param("status") String status);

  int updateMonitorProfile(@Param("merchantId") Long merchantId, @Param("productId") Long productId,
                           @Param("expectedVersion") long expectedVersion, @Param("profile") String profile);

  int advanceVersion(@Param("merchantId") Long merchantId, @Param("productId") Long productId,
                     @Param("expectedVersion") long expectedVersion);
}
