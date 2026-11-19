package vip.fairy.commerceflow.commerce.fulfillment.infrastructure;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FulfillmentOwnershipMapper {

  String writeOwner();
}
