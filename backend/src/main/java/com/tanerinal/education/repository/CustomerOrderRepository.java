package com.tanerinal.education.repository;

import com.tanerinal.education.domain.CustomerOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {
  @Query("select o from CustomerOrder o join fetch o.product where o.user.id = :userId order by o.createdAt desc")
  List<CustomerOrder> findAllForUser(@Param("userId") Long userId);

  @Query("select o from CustomerOrder o join fetch o.product where o.id = :orderId and o.user.id = :userId")
  Optional<CustomerOrder> findForUser(@Param("orderId") Long orderId, @Param("userId") Long userId);
}
