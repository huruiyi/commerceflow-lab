package com.tanerinal.education.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "customer_orders")
public class CustomerOrder {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private AppUser user;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "product_id", nullable = false)
  private Product product;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false, length = 500)
  private String note;

  @Column(nullable = false, columnDefinition = "integer default 1")
  private int quantity = 1;

  protected CustomerOrder() {
  }

  public CustomerOrder(AppUser user, Product product, Instant createdAt, String note, int quantity) {
    this.user = user;
    this.product = product;
    this.createdAt = createdAt;
    this.note = note;
    this.quantity = quantity;
  }

  public Long getId() { return id; }
  public AppUser getUser() { return user; }
  public Product getProduct() { return product; }
  public Instant getCreatedAt() { return createdAt; }
  public String getNote() { return note; }
  public int getQuantity() { return quantity; }
  public void changeQuantity(int quantity) { this.quantity = quantity; }
}
