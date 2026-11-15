package vip.fairy.commerceflow.commerce.catalog.application;

import vip.fairy.commerceflow.commerce.shared.application.ExternalServiceUnavailableException;

public class CatalogServiceUnavailableException extends ExternalServiceUnavailableException {

  public CatalogServiceUnavailableException(Throwable cause) {
    super("CATALOG_SERVICE_UNAVAILABLE", "Catalog service is temporarily unavailable", cause);
  }
}
