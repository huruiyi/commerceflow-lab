package vip.fairy.commerceflow.reference.catalog;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.Instant;
import java.util.List;

public final class ReferenceContracts {

  private ReferenceContracts() {
  }

  /** Published pointer for a versioned reference dataset. */
  public record Dataset(String datasetKey, String name, Long currentReleaseId) {
  }

  /** Immutable import metadata and the time this snapshot first became public. */
  public record Release(long id, String datasetKey, String version, String sourceUrl, String sourceRevision,
                        String sha256, long createdBy, Instant createdAt, Instant publishedAt, int rowCount) {
  }

  /** Source code and its immediate parent within a snapshot. */
  public record Node(String code, String parentCode, int level, String name, @JsonRawValue String sourceMetadata) {
  }

  public record NodePage(String datasetKey, long releaseId, List<Node> rows, String nextAfter) {
  }

  public record ChangeCounts(long added, long changed, long removed) {
  }

  public record Change(String code, String kind, String previousName, String nextName,
                       String previousParentCode, String nextParentCode, Integer previousLevel, Integer nextLevel) {
  }

  public record Comparison(Long currentReleaseId, ChangeCounts counts, List<Change> sample) {
  }

  public record PublishRequest(Long expectedCurrentReleaseId) {
  }

  /** A publication or rollback of the dataset pointer. */
  public record Publication(long id, long releaseId, Long previousReleaseId, long publishedBy,
                            Instant publishedAt) {
  }
}
