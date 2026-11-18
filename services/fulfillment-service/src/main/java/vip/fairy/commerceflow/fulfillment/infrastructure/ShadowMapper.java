package vip.fairy.commerceflow.fulfillment.infrastructure;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.fulfillment.domain.OrderMerchant;
import vip.fairy.commerceflow.fulfillment.domain.ShadowKey;
import vip.fairy.commerceflow.fulfillment.domain.ShadowRow;
import vip.fairy.commerceflow.fulfillment.domain.Watermark;

public interface ShadowMapper {

  long sourceSequence();

  List<ShadowKey> changes(@Param("after") long after, @Param("through") long through);

  long changeCount(@Param("after") long after, @Param("through") long through);

  List<ShadowRow> rows();

  ShadowRow command(@Param("commandId") String commandId);

  Watermark watermark();

  int advance(Watermark watermark);

  List<Long> orders();

  List<OrderMerchant> ownership();

  List<ShadowRow> quarantine();

  int isolate(@Param("row") ShadowRow row, @Param("sequence") long sequence, @Param("reason") String reason);

  int clearIsolation(ShadowKey key);

  int delete(ShadowKey key);

  int upsert(@Param("tableName") String tableName, @Param("values") Map<String, Object> values);
}
