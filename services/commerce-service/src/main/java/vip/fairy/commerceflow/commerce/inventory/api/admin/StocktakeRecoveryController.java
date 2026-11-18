package vip.fairy.commerceflow.commerce.inventory.api.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.inventory.application.StocktakeRecoveryService;
import vip.fairy.commerceflow.commerce.inventory.application.StocktakeRecoveryService.Proposal;
import vip.fairy.commerceflow.commerce.inventory.application.StocktakeRecoveryService.Review;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.StocktakeV1;

@RestController
@RequestMapping("/api/admin/inventory-command-recoveries/stocktakes")
@PreAuthorize("hasRole('ADMIN')")
public class StocktakeRecoveryController {

  private final StocktakeRecoveryService stocktakes;

  public StocktakeRecoveryController(StocktakeRecoveryService stocktakes) {
    this.stocktakes = stocktakes;
  }

  @GetMapping("/baseline/{productId}")
  StocktakeV1.Baseline baseline(@PathVariable long productId) {
    return stocktakes.baseline(productId);
  }

  @PostMapping
  Review submit(@RequestBody Proposal proposal, Authentication actor) {
    return stocktakes.submit(proposal, actor.getName());
  }

  @GetMapping("/{reference}")
  Review get(@PathVariable String reference) {
    return stocktakes.get(reference);
  }

  @PostMapping("/{reference}/approve")
  StocktakeV1.Receipt approve(@PathVariable String reference, Authentication actor) {
    stocktakes.approve(reference, actor.getName());
    return stocktakes.applyApproved(reference);
  }
}
