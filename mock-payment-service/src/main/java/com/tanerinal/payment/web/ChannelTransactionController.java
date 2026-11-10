package com.tanerinal.payment.web;

import com.tanerinal.payment.service.ChannelTransactionService;
import com.tanerinal.payment.web.dto.ChannelTransactionResponse;
import com.tanerinal.payment.web.dto.CreateChannelTransactionRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
  public ChannelTransactionResponse create(@RequestHeader("X-App-Id") String appId,
      @RequestHeader("X-Demo-App-Secret") String appSecret,
      @Valid @RequestBody CreateChannelTransactionRequest request) {
    return transactions.create(appId, appSecret, request);
  }

  @GetMapping
  public ChannelTransactionResponse query(@RequestHeader("X-App-Id") String appId,
      @RequestHeader("X-Demo-App-Secret") String appSecret,
      @RequestParam String merchantRequestNo) {
    return transactions.query(appId, appSecret, merchantRequestNo);
  }

  @PostMapping("/{transactionNo}/close")
  public ChannelTransactionResponse close(@RequestHeader("X-App-Id") String appId,
      @RequestHeader("X-Demo-App-Secret") String appSecret, @PathVariable String transactionNo) {
    return transactions.closeByMerchant(appId, appSecret, transactionNo);
  }
}
