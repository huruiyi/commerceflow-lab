package vip.fairy.commerceflow.commerce.invoice.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.invoice.application.CommerceInvoiceService;
import vip.fairy.commerceflow.commerce.invoice.application.InvoiceChannel;

@RestController
@ConditionalOnProperty(prefix = "commerceflow.invoice", name = "enabled", havingValue = "true")
public class InvoiceReceiptController {

  private final InvoiceChannel channel;
  private final CommerceInvoiceService invoices;
  private final ObjectMapper json;

  public InvoiceReceiptController(InvoiceChannel channel, CommerceInvoiceService invoices, ObjectMapper json) {
    this.channel = channel;
    this.invoices = invoices;
    this.json = json;
  }

  @PostMapping(value = "/internal/v1/invoice-receipts", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Void> receive(@RequestBody byte[] body, HttpServletRequest request) {
    if (!channel.authenticReceipt(request.getHeader("X-Invoice-Timestamp"),
      request.getHeader("X-Invoice-Signature"), request.getRequestURI(), body)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    try {
      var receipt = json.readTree(body);
      String requestId = receipt.path("result").path("requestId").asText("");
      if (!requestId.matches("REQ-[0-9a-f-]{36}") || !("evt-" + requestId).equals(receipt.path("eventId").asText())) {
        return ResponseEntity.badRequest().build();
      }
      invoices.nudge(requestId);
      return ResponseEntity.noContent().build();
    } catch (JacksonException ex) {
      return ResponseEntity.badRequest().build();
    }
  }
}
