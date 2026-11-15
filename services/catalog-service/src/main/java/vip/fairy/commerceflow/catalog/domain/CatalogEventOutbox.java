package vip.fairy.commerceflow.catalog.domain;

/** Catalog 事件可靠投递记录。 */
public class CatalogEventOutbox {

  private Long id;
  private String eventId;
  private String payload;
  private int attempts;

  public Long getId() {
    return id;
  }

  public String getEventId() {
    return eventId;
  }

  public String getPayload() {
    return payload;
  }

  public int getAttempts() {
    return attempts;
  }
}
