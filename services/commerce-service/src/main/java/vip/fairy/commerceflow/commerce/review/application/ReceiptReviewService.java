package vip.fairy.commerceflow.commerce.review.application;

import vip.fairy.commerceflow.commerce.catalog.application.ProductCatalogService;
import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.ordering.api.ShopOrderResponse;
import vip.fairy.commerceflow.commerce.ordering.application.PurchaseOrderService;
import vip.fairy.commerceflow.commerce.receipt.application.OrderReceiptLifecycleService;
import vip.fairy.commerceflow.commerce.review.api.ProductReviewResponse;
import vip.fairy.commerceflow.commerce.review.api.ReviewRequest;
import vip.fairy.commerceflow.commerce.review.domain.ProductReview;
import vip.fairy.commerceflow.commerce.review.domain.ProductReviewView;
import vip.fairy.commerceflow.commerce.review.domain.ReviewableItem;
import vip.fairy.commerceflow.commerce.review.infrastructure.ReviewMapper;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReceiptReviewService {
  private final ReviewMapper reviews;
  private final ProductCatalogService catalog;
  private final PurchaseOrderService purchaseOrders;
  private final OrderReceiptLifecycleService receiptLifecycle;

  public ReceiptReviewService(ReviewMapper reviews,
                              ProductCatalogService catalog, PurchaseOrderService purchaseOrders,
                              OrderReceiptLifecycleService receiptLifecycle) {
    this.reviews = reviews;
    this.catalog = catalog;
    this.purchaseOrders = purchaseOrders;
    this.receiptLifecycle = receiptLifecycle;
  }

  @Transactional
  public ShopOrderResponse receive(AppUser user, Long orderId) {
    receiptLifecycle.confirmReceipt(user.getId(), user.getId(), orderId);
    return purchaseOrders.require(user, orderId);
  }

  @Transactional
  public ProductReviewResponse review(AppUser user, Long itemId, ReviewRequest request) {
    ReviewableItem item = reviews.reviewable(user.getId(), itemId)
      .orElseThrow(() -> new BusinessRuleException("Only received order items can be reviewed"));
    try {
      reviews.insert(new ProductReview(
        itemId,
        item.getProductId(),
        user.getId(),
        request.rating(),
        request.comment()));
    } catch (DuplicateKeyException exception) {
      throw new BusinessRuleException("This order item was already reviewed");
    }
    catalog.invalidate();
    ProductReviewView review = reviews.byItem(itemId);
    return ProductReviewResponse.from(review);
  }
}
