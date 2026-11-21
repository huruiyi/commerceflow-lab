package vip.fairy.commerceflow.commerce.invoice.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.Application;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.Attempt;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.Outcome;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.Kind;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoicePortal.Query;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoicePortal.ReviewAction;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoicePortal.Summary;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.RefundFact;
import vip.fairy.commerceflow.commerce.invoice.domain.InvoiceJournal.State;

public interface InvoiceJournalMapper {

  void create(@Param("orderId") long orderId, @Param("customerId") long customerId,
              @Param("applicationNo") String applicationNo, @Param("orderNumber") String orderNumber);

  Application find(long orderId);

  Application lock(long orderId);

  Application lockDue(long orderId);

  List<Long> due();

  List<Attempt> attempts(long orderId);

  List<Summary> search(Query query);

  int attemptCount(@Param("orderId") long orderId, @Param("kind") Kind kind,
                   @Param("referenceNo") String referenceNo, @Param("afterId") long afterId);

  void insertReviewAction(@Param("orderId") long orderId, @Param("actorId") long actorId,
                          @Param("priorReason") String priorReason, @Param("priorDetail") String priorDetail,
                          @Param("note") String note);

  List<ReviewAction> reviewActions(long orderId);

  void reopen(@Param("orderId") long orderId, @Param("state") State state, @Param("restartRetries") boolean restart);

  void insertAttempt(Attempt attempt);

  int finish(@Param("requestId") String requestId, @Param("status") Outcome status,
             @Param("invoiceNo") String invoiceNo, @Param("failureCode") String failureCode);

  void advance(@Param("orderId") long orderId, @Param("status") State status, @Param("delayMs") Integer delayMs);

  void review(@Param("orderId") long orderId, @Param("reason") String reason, @Param("detail") String detail);

  void nudge(String requestId);

  List<RefundFact> refunds(long orderId);

  void observe(RefundFact fact);

  void includeRefund(@Param("requestId") String requestId, @Param("orderId") long orderId,
                     @Param("refundNo") String refundNo);

  List<String> includedRefunds(String requestId);
}
