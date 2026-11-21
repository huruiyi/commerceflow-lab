package vip.fairy.commerceflow.simulator.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Command;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Kind;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Receipt;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Request;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Result;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Simulation;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Status;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Ticket;
import vip.fairy.commerceflow.simulator.invoice.InvoiceSimulatorApplication.Settings;

@Service
class InvoiceService {
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999999.99");
  private final InvoiceMapper mapper;
  private final Settings settings;

  InvoiceService(InvoiceMapper mapper, Settings settings) {
    this.mapper = mapper;
    this.settings = settings;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Submission submit(Command input) {
    Command command = validate(input);
    mapper.insertRequest(command);
    Request existing = mapper.lockRequest(command.requestId());
    if (!command.equals(existing.command())) {
      throw new InvoiceException(HttpStatus.CONFLICT, "REQUEST_CONTENT_MISMATCH");
    }
    if (existing.status() != Status.RECEIVED) {
      return new Submission(existing.result(), false);
    }

    Ticket original = command.kind() == Kind.RED ? lockOriginal(command) : null;
    try {
      mapper.accept(command.requestId(), settings.processingDelayMs());
    } catch (DuplicateKeyException ex) {
      throw new InvoiceException(HttpStatus.CONFLICT, "BUSINESS_REFERENCE_ALREADY_ACTIVE");
    }
    if (original != null && mapper.sumReservedRedAmounts(original.invoiceNo()).compareTo(original.amount()) > 0) {
      throw new InvoiceException(HttpStatus.CONFLICT, "RED_AMOUNT_EXCEEDS_AVAILABLE");
    }
    return new Submission(mapper.findRequest(command.requestId()).result(), true);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void complete(Request candidate) {
    // Red admissions and completions take the same original-ticket lock before checking the red balance.
    if (candidate.originalInvoiceNo() != null) {
      mapper.lockTicket(candidate.originalInvoiceNo());
    }
    Request request = mapper.lockRequest(candidate.requestId());
    if (request.status() != Status.ACCEPTED) {
      return;
    }
    String invoiceNo = null;
    String failureCode = null;
    if (request.simulation() == Simulation.FAIL) {
      failureCode = "SIMULATED_CHANNEL_FAILURE";
    } else {
      invoiceNo = (request.kind() == Kind.ISSUE ? "INV-" : "RED-") + UUID.randomUUID();
      mapper.insertTicket(request, invoiceNo);
    }
    mapper.complete(request.requestId(), invoiceNo, failureCode);
    mapper.insertReceipt(new Receipt("evt-" + request.requestId(), request.requestId()));
  }

  Result request(String requestId) {
    Request request = mapper.findRequest(identifier(requestId, "INVALID_REQUEST_ID"));
    if (request == null) {
      throw new InvoiceException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND");
    }
    return request.result();
  }

  Result ticket(String invoiceNo) {
    Ticket ticket = mapper.findTicket(identifier(invoiceNo, "INVALID_INVOICE_NO"));
    if (ticket == null) {
      throw new InvoiceException(HttpStatus.NOT_FOUND, "INVOICE_NOT_FOUND");
    }
    return request(ticket.requestId());
  }

  private Ticket lockOriginal(Command command) {
    Ticket original = mapper.lockTicket(command.originalInvoiceNo());
    if (original == null || original.kind() != Kind.ISSUE) {
      throw new InvoiceException(HttpStatus.NOT_FOUND, "ORIGINAL_INVOICE_NOT_FOUND");
    }
    if (!original.currency().equals(command.currency())) {
      throw new InvoiceException(HttpStatus.CONFLICT, "CURRENCY_MISMATCH");
    }
    return original;
  }

  private static Command validate(Command input) {
    String requestId = identifier(input.requestId(), "INVALID_REQUEST_ID");
    String referenceNo = identifier(input.referenceNo(), "INVALID_REFERENCE_NO");
    String paymentNo = input.kind() == Kind.ISSUE ? identifier(input.paymentNo(), "INVALID_PAYMENT_NO") : null;
    String originalNo = input.kind() == Kind.RED ? identifier(input.originalInvoiceNo(), "INVALID_ORIGINAL_INVOICE_NO") : null;
    BigDecimal amount = input.amount();
    if (amount == null || amount.signum() <= 0 || amount.scale() > 2 || amount.compareTo(MAX_AMOUNT) > 0) {
      throw new InvoiceException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT");
    }
    if (input.currency() == null || !input.currency().matches("[A-Z]{3}")) {
      throw new InvoiceException(HttpStatus.BAD_REQUEST, "INVALID_CURRENCY");
    }
    Simulation simulation = input.simulation() == null ? Simulation.SUCCESS : input.simulation();
    return new Command(requestId, input.kind(), referenceNo, paymentNo, originalNo,
      amount.setScale(2, RoundingMode.UNNECESSARY), input.currency(), simulation);
  }

  private static String identifier(String value, String errorCode) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
      throw new InvoiceException(HttpStatus.BAD_REQUEST, errorCode);
    }
    return value;
  }

  record Submission(Result result, boolean newlyAccepted) {
  }
}
