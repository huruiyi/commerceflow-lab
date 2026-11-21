package vip.fairy.commerceflow.simulator.invoice;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Command;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.IssueInput;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Kind;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.RedInput;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Result;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Simulation;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Status;
import vip.fairy.commerceflow.simulator.invoice.InvoiceService.Submission;
import vip.fairy.commerceflow.simulator.invoice.InvoiceSimulatorApplication.Settings;

@RestController
@RequestMapping("/api")
class InvoiceController {
  private static final byte[] EMPTY_BODY = new byte[0];
  private final InvoiceService service;
  private final Settings settings;
  private final ObjectMapper json;

  InvoiceController(InvoiceService service, Settings settings, ObjectMapper json) {
    this.service = service;
    this.settings = settings;
    this.json = json;
  }

  @PostMapping(value = "/invoice-requests/{requestId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Result> issue(@PathVariable String requestId, @RequestBody byte[] body, HttpServletRequest request)
    throws InterruptedException {
    authenticate(request, body);
    IssueInput input = parse(body, IssueInput.class);
    Command command = new Command(requestId, Kind.ISSUE, input.applicationNo(), input.paymentNo(), null,
      input.amount(), input.currency(), input.simulation());
    return submit(command);
  }

  @PostMapping(value = "/red-invoice-requests/{requestId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Result> red(@PathVariable String requestId, @RequestBody byte[] body, HttpServletRequest request)
    throws InterruptedException {
    authenticate(request, body);
    RedInput input = parse(body, RedInput.class);
    Command command = new Command(requestId, Kind.RED, input.refundNo(), null, input.originalInvoiceNo(),
      input.amount(), input.currency(), input.simulation());
    return submit(command);
  }

  @GetMapping("/invoice-requests/{requestId}")
  Result request(@PathVariable String requestId, HttpServletRequest request) {
    authenticate(request, EMPTY_BODY);
    return service.request(requestId);
  }

  @GetMapping("/invoices/{invoiceNo}")
  Result ticket(@PathVariable String invoiceNo, HttpServletRequest request) {
    authenticate(request, EMPTY_BODY);
    return service.ticket(invoiceNo);
  }

  @ExceptionHandler(InvoiceException.class)
  ResponseEntity<ErrorResponse> error(InvoiceException ex) {
    return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.getMessage()));
  }

  private ResponseEntity<Result> submit(Command command) throws InterruptedException {
    Submission submission = service.submit(command);
    if (submission.newlyAccepted() && command.simulation() == Simulation.ACCEPT_THEN_TIMEOUT) {
      Thread.sleep(settings.timeoutDelayMs());
    }
    Result result = submission.result();
    return ResponseEntity.status(result.status() == Status.ACCEPTED ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result);
  }

  private void authenticate(HttpServletRequest request, byte[] body) {
    if (!InvoiceSignature.matches(settings.requestSecret(), request.getHeader(InvoiceSignature.TIMESTAMP_HEADER),
      request.getHeader(InvoiceSignature.SIGNATURE_HEADER), request.getMethod(), request.getRequestURI(), body)) {
      throw new InvoiceException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE");
    }
  }

  private <T> T parse(byte[] body, Class<T> type) {
    try {
      T input = json.readValue(body, type);
      if (input != null) {
        return input;
      }
    } catch (JacksonException ex) {
      throw new InvoiceException(HttpStatus.BAD_REQUEST, "INVALID_JSON");
    }
    throw new InvoiceException(HttpStatus.BAD_REQUEST, "INVALID_JSON");
  }

  private record ErrorResponse(String code) {
  }
}
