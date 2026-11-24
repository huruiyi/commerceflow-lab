package vip.fairy.commerceflow.commerce.operations.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.operations.domain.ExceptionWork;
import vip.fairy.commerceflow.commerce.operations.domain.ExceptionWork.Filter;
import vip.fairy.commerceflow.commerce.operations.infrastructure.ExceptionWorkMapper;
import vip.fairy.commerceflow.commerce.shared.api.AdminListPage;

@Service
public class ExceptionWorkQuery {

  private final ExceptionWorkMapper work;

  ExceptionWorkQuery(ExceptionWorkMapper work) {
    this.work = work;
  }

  @Transactional(readOnly = true)
  public AdminListPage<ExceptionWork> list(Long merchant, Filter filter, int requestedPage, int requestedSize) {
    int size = AdminListPage.boundedSize(requestedSize);
    long total = work.count(merchant, filter);
    int page = AdminListPage.availablePage(requestedPage, total, size);
    return new AdminListPage<>(work.page(merchant, filter, (long) page * size, size), total, page, size);
  }
}
