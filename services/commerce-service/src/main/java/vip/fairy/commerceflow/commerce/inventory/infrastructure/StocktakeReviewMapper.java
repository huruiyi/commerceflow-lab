package vip.fairy.commerceflow.commerce.inventory.infrastructure;

import org.apache.ibatis.annotations.Param;

public interface StocktakeReviewMapper {

  int create(@Param("reference") String reference, @Param("proposal") String proposal,
             @Param("actor") String actor);

  Row find(String reference);

  Row lock(String reference);

  void approve(@Param("reference") String reference, @Param("actor") String actor);

  void complete(@Param("reference") String reference, @Param("receipt") String receipt);

  record Row(String reference, String proposal, String countedBy, String approvedBy, String receipt) {
  }
}
