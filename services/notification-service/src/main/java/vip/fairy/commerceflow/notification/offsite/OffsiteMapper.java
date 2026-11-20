package vip.fairy.commerceflow.notification.offsite;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OffsiteMapper {

  int insertEvent(OffsiteIntake.Event event, String fingerprint);
  String fingerprint(String eventId);
  void insertDelivery(Command command, String eventId, String status);
  List<Delivery> due();
  int claim(String requestId, String lease, int attempts);
  void finish(String requestId, String lease, String status, String providerId, String error, int delaySeconds);

  record Command(String requestId, String channel, String recipient, String reference,
                 String subject, String message) {
  }

  record Delivery(String requestId, String channel, String recipient, String reference,
                  String subject, String message, int attempts, String providerId) {

    Command command() {
      return new Command(requestId, channel, recipient, reference, subject, message);
    }
  }
}
