package vip.fairy.commerceflow.commerce.catalog.infrastructure;

import vip.fairy.commerceflow.commerce.catalog.domain.ProductTranslation;
import org.apache.ibatis.annotations.*;

public interface ProductTranslationMapper {

  ProductTranslation find(@Param("productId") Long productId, @Param("locale") String locale);
}
