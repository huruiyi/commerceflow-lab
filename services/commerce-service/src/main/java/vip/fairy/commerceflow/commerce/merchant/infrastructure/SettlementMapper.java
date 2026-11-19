package vip.fairy.commerceflow.commerce.merchant.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.commerce.merchant.domain.Settlement.Account;
import vip.fairy.commerceflow.commerce.merchant.domain.Settlement.Entry;
import vip.fairy.commerceflow.commerce.merchant.domain.Settlement.Statement;

public interface SettlementMapper {

  Instant now();

  Account lockAccount(Long merchant);

  void enable(@Param("merchant") Long merchant, @Param("rate") int rate);

  List<Entry> orderEntries(Long orderId);

  List<Entry> pending(Long merchant);

  List<Entry> statementEntries(String statement);

  void append(Entry entry);

  Instant lastCutoff(Long merchant);

  Statement statement(String id);

  List<Statement> statements(Long merchant);

  void freeze(Statement statement);

  int assign(@Param("merchant") Long merchant, @Param("statement") String statement,
             @Param("keys") List<String> keys);
}
