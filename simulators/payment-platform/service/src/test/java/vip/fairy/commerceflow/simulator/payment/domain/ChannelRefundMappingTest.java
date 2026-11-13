package vip.fairy.commerceflow.simulator.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChannelRefundMappingTest {

  @Test
  void mapsDatabasePrimaryKeyToNumericId() throws NoSuchFieldException {
    TableId idMapping = ChannelRefund.class.getDeclaredField("id").getAnnotation(TableId.class);

    assertNotNull(idMapping);
    assertEquals(IdType.AUTO, idMapping.type());
    assertNull(ChannelRefund.class.getDeclaredField("channelRefundNo").getAnnotation(TableId.class));
  }
}
