package vip.fairy.commerceflow.catalog.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.catalog.domain.ProductTranslation;

public interface ProductTranslationMapper extends BaseMapper<ProductTranslation> {

  int upsert(@Param("productId") Long productId, @Param("locale") String locale,
             @Param("name") String name, @Param("brief") String brief,
             @Param("description") String description);
}
