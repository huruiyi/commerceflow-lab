package lab.commerceflow.storefront.service;

import lab.commerceflow.storefront.domain.AppUser;
import lab.commerceflow.storefront.domain.ProductReviewView;
import lab.commerceflow.storefront.domain.ReviewableItem;
import lab.commerceflow.storefront.mapper.ReviewMapper;
import lab.commerceflow.storefront.mapper.ShopOrderMapper;
import lab.commerceflow.storefront.web.dto.ProductReviewResponse;
import lab.commerceflow.storefront.web.dto.ReviewRequest;
import lab.commerceflow.storefront.web.dto.ShopOrderResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReceiptReviewService {
  private final ShopOrderMapper orders;
  private final ReviewMapper reviews;
  private final ProductCatalogService catalog;
  private final PurchaseOrderService purchaseOrders;
  private final OrderReceiptLifecycleService receiptLifecycle;

  public ReceiptReviewService(ShopOrderMapper orders, ReviewMapper reviews,
                              ProductCatalogService catalog, PurchaseOrderService purchaseOrders,
                              OrderReceiptLifecycleService receiptLifecycle) {
    this.orders = orders;
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
      reviews.insert(itemId, item.getProductId(), user.getId(), request.rating(), request.comment());
    } catch (DuplicateKeyException exception) {
      throw new BusinessRuleException("This order item was already reviewed");
    }
    catalog.invalidate();
    ProductReviewView review = reviews.byItem(itemId);
    return ProductReviewResponse.from(review);
  }

}
