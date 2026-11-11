package lab.commerceflow.payment.web;

import lab.commerceflow.payment.service.ChannelTransactionService;
import lab.commerceflow.payment.security.SignedRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import lab.commerceflow.payment.web.dto.ChannelTransactionResponse;
import lab.commerceflow.payment.web.dto.CreateChannelTransactionRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/channel/transactions")
public class ChannelTransactionController {
  private final ChannelTransactionService transactions;

  public ChannelTransactionController(ChannelTransactionService transactions) {
    this.transactions = transactions;
  }

  @PostMapping
  public ChannelTransactionResponse create(HttpServletRequest servletRequest,
      @Valid @RequestBody CreateChannelTransactionRequest request) {
    return transactions.create(signed(servletRequest), request);
  }

  @GetMapping
  public ChannelTransactionResponse query(HttpServletRequest servletRequest,
      @RequestParam String merchantRequestNo) {
    return transactions.query(signed(servletRequest), merchantRequestNo);
  }

  @PostMapping("/{transactionNo}/close")
  public ChannelTransactionResponse close(HttpServletRequest servletRequest,
      @PathVariable String transactionNo) {
    return transactions.closeByMerchant(signed(servletRequest), transactionNo);
  }

  private static SignedRequestContext signed(HttpServletRequest request) {
    return (SignedRequestContext) request.getAttribute(SignedRequestContext.ATTRIBUTE);
  }
}
