package lab.commerceflow.storefront.domain;

import java.time.Instant;

public class RefundEventView {
  private Long id; private String eventType; private String fromStatus; private String toStatus; private String detail; private Instant createdAt;
  public Long getId(){return id;} public String getEventType(){return eventType;} public String getFromStatus(){return fromStatus;}
  public String getToStatus(){return toStatus;} public String getDetail(){return detail;} public Instant getCreatedAt(){return createdAt;}
}
