package vip.fairy.commerceflow.commerce.operations.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.operations.application.ExceptionWorkQuery;
import vip.fairy.commerceflow.commerce.operations.domain.ExceptionWork;
import vip.fairy.commerceflow.commerce.operations.domain.ExceptionWork.Filter;
import vip.fairy.commerceflow.commerce.operations.domain.ExceptionWork.Kind;
import vip.fairy.commerceflow.commerce.operations.domain.ExceptionWork.Priority;
import vip.fairy.commerceflow.commerce.operations.domain.ExceptionWork.State;
import vip.fairy.commerceflow.commerce.shared.api.AdminListPage;
import vip.fairy.commerceflow.commerce.shared.application.CurrentActorIdResolver;

@RestController
public class ExceptionWorkController {

  private final ExceptionWorkQuery work;
  private final CurrentActorIdResolver actors;

  ExceptionWorkController(ExceptionWorkQuery work, CurrentActorIdResolver actors) {
    this.work = work;
    this.actors = actors;
  }

  @GetMapping("/api/admin/exception-work")
  @PreAuthorize("hasRole('ADMIN')")
  AdminListPage<ExceptionWork> admin(@RequestParam(required = false) Long merchantId,
                                     @ModelAttribute @Valid Criteria criteria) {
    if (merchantId != null && merchantId < 1) throw new IllegalArgumentException("Store must be positive");
    return work.list(merchantId, criteria.filter(), criteria.pageNumber(), criteria.pageSize());
  }

  @GetMapping("/api/merchant/exception-work")
  @PreAuthorize("hasRole('MERCHANT')")
  AdminListPage<ExceptionWork> merchant(Authentication actor, @ModelAttribute @Valid Criteria criteria) {
    return work.list(actors.requireId(actor), criteria.filter(), criteria.pageNumber(), criteria.pageSize());
  }

  record Criteria(Kind kind, Priority priority, State state, @Positive Long orderId, Integer page, Integer size) {

    Filter filter() {
      return new Filter(kind, priority, state, orderId);
    }

    int pageNumber() {
      return page == null ? 0 : page;
    }

    int pageSize() {
      return size == null ? 20 : size;
    }
  }
}
