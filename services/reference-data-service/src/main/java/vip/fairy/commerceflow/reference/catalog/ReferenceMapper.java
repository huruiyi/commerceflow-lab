package vip.fairy.commerceflow.reference.catalog;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.Change;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.ChangeCounts;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.Dataset;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.Node;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.Publication;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.Release;

@Mapper
interface ReferenceMapper {

  List<Dataset> datasets();

  Dataset dataset(@Param("dataset") String dataset);

  Dataset lockDataset(@Param("dataset") String dataset);

  Release release(@Param("dataset") String dataset, @Param("id") long id);

  Release findVersion(@Param("dataset") String dataset, @Param("version") String version);

  List<Release> releases(@Param("dataset") String dataset, @Param("before") Long before);

  long insertRelease(@Param("dataset") String dataset, @Param("version") String version,
                     @Param("sourceUrl") String sourceUrl, @Param("sourceRevision") String sourceRevision,
                     @Param("sha256") String sha256, @Param("actor") long actor);

  void insertNodes(@Param("id") long id, @Param("nodes") List<Node> nodes);

  String invalidParent(@Param("id") long id, @Param("allowSkippedLevel") boolean allowSkippedLevel);

  void recordCount(@Param("id") long id, @Param("count") int count);

  List<Node> nodes(@Param("id") long id, @Param("parent") String parent, @Param("query") String query,
                   @Param("after") String after, @Param("limit") int limit, @Param("flat") boolean flat);

  Node node(@Param("id") long id, @Param("code") String code);

  ChangeCounts changeCounts(@Param("current") Long current, @Param("draft") long draft);

  List<Change> changeSample(@Param("current") Long current, @Param("draft") long draft);

  void markPublished(@Param("id") long id);

  void activate(@Param("dataset") String dataset, @Param("id") long id);

  void recordPublication(@Param("dataset") String dataset, @Param("id") long id,
                         @Param("previous") Long previous, @Param("actor") long actor);

  List<Publication> publications(@Param("dataset") String dataset, @Param("before") Long before);
}
