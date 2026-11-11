package lab.commerceflow.payment.web;

import lab.commerceflow.payment.security.PaymentAdminPrincipal;
import lab.commerceflow.payment.service.ChannelTransactionService;
import lab.commerceflow.payment.web.dto.ChannelTransactionResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/transactions")
public class AdminTransactionController {
  private final ChannelTransactionService transactions;

  public AdminTransactionController(ChannelTransactionService transactions) {
    this.transactions = transactions;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('TRANSACTION_READ')")
  public List<ChannelTransactionResponse> list(@AuthenticationPrincipal PaymentAdminPrincipal actor) {
    return transactions.listFor(actor);
  }

  @PostMapping("/{transactionNo}/close")
  @PreAuthorize("hasAuthority('TRANSACTION_CLOSE')")
  public ChannelTransactionResponse close(@PathVariable String transactionNo,
      @AuthenticationPrincipal PaymentAdminPrincipal actor, HttpServletRequest request) {
    return transactions.closeByAdmin(transactionNo, actor, request);
  }
}
