package vip.fairy.commerceflow.commerce.shared.api;

import java.util.List;

public record AdminListPage<T>(List<T> rows, long total, int page, int size) {

  public static int boundedSize(int requested) {
    return Math.clamp(requested, 1, 100);
  }

  public static int availablePage(int requested, long total, int size) {
    long last = total == 0 ? 0 : (total - 1) / size;
    return (int) Math.clamp(requested, 0L, last);
  }
}
