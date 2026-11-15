package vip.fairy.commerceflow.commerce.catalog.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import vip.fairy.commerceflow.commerce.catalog.domain.ProductTranslation;
import org.apache.ibatis.annotations.Param;

public interface ProductTranslationMapper extends BaseMapper<ProductTranslation> {

  int upsert(@Param("productId") Long productId,
             @Param("locale") String locale,
             @Param("name") String name,
             @Param("brief") String brief,
             @Param("description") String description);
}
