package vip.fairy.commerceflow.simulator.invoice;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Command;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Receipt;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Request;
import vip.fairy.commerceflow.simulator.invoice.InvoiceLedger.Ticket;

@Mapper
interface InvoiceMapper {
  void insertRequest(Command command);

  Request findRequest(String requestId);

  Request lockRequest(String requestId);

  void accept(@Param("requestId") String requestId, @Param("delayMs") int delayMs);

  Ticket findTicket(String invoiceNo);

  Ticket lockTicket(String invoiceNo);

  BigDecimal sumReservedRedAmounts(String originalInvoiceNo);

  List<Request> findDueRequests();

  void insertTicket(@Param("request") Request request, @Param("invoiceNo") String invoiceNo);

  void complete(
    @Param("requestId") String requestId,
    @Param("invoiceNo") String invoiceNo,
    @Param("failureCode") String failureCode
  );

  void insertReceipt(Receipt receipt);

  List<Receipt> findDueReceipts();

  void acknowledgeReceipt(String eventId);

  void deferReceipt(@Param("eventId") String eventId, @Param("delayMs") int delayMs);
}
