package vip.fairy.commerceflow.commerce.catalog.domain;

public class ProductTranslation {
  private Long productId;
  private String locale;
  private String name;
  private String brief;
  private String description;

  public String getName() {
    return name;
  }

  public String getBrief() {
    return brief;
  }

  public String getDescription() {
    return description;
  }
}
