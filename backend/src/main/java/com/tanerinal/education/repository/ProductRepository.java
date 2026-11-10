package com.tanerinal.education.repository;

import com.tanerinal.education.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
