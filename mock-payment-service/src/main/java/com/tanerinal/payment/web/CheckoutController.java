package com.tanerinal.payment.web;

import com.tanerinal.payment.service.ChannelTransactionService;
import com.tanerinal.payment.web.dto.ChannelTransactionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {
  private final ChannelTransactionService transactions;

  public CheckoutController(ChannelTransactionService transactions) {
    this.transactions = transactions;
  }

  @GetMapping("/{token}")
  public ChannelTransactionResponse checkout(@PathVariable String token) {
    return transactions.checkout(token);
  }

  @PostMapping("/{token}/complete")
  public ChannelTransactionResponse complete(@PathVariable String token,
      @RequestParam(defaultValue = "true") boolean success) {
    return transactions.simulatePay(token, success);
  }
}
