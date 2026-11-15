package vip.fairy.commerceflow.commerce.catalog.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import vip.fairy.commerceflow.commerce.catalog.domain.Product;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

public interface ProductMapper extends BaseMapper<Product> {

  List<Product> selectForMerchant(Long merchantId);

  int updateProfile(@Param("merchantId") Long merchantId,
                    @Param("productId") Long productId,
                    @Param("expectedVersion") long expectedVersion,
                    @Param("name") String name,
                    @Param("brief") String brief,
                    @Param("description") String description,
                    @Param("imageUrl") String imageUrl,
                    @Param("thumbnailUrl") String thumbnailUrl);

  int updatePrice(@Param("merchantId") Long merchantId,
                  @Param("productId") Long productId,
                  @Param("expectedVersion") long expectedVersion,
                  @Param("price") BigDecimal price);

  int updateStatus(@Param("merchantId") Long merchantId,
                   @Param("productId") Long productId,
                   @Param("expectedVersion") long expectedVersion,
                   @Param("status") String status);

  int advanceVersion(@Param("merchantId") Long merchantId,
                     @Param("productId") Long productId,
                     @Param("expectedVersion") long expectedVersion);
}
