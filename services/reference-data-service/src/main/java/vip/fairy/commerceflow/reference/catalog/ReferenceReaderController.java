package vip.fairy.commerceflow.reference.catalog;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.Dataset;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.Node;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.NodePage;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.Release;

@RestController
@RequestMapping("/api/reference-data")
class ReferenceReaderController {

  private final ReferenceCatalog catalog;

  ReferenceReaderController(ReferenceCatalog catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/datasets")
  List<Dataset> datasets() {
    return catalog.datasets();
  }

  @GetMapping("/datasets/{dataset}/versions/current")
  Release current(@PathVariable String dataset) {
    return catalog.resolvePublished(dataset, null);
  }

  @GetMapping("/datasets/{dataset}/nodes")
  NodePage nodes(@PathVariable String dataset, @RequestParam(required = false) Long releaseId,
                 @RequestParam(required = false) String parentCode, @RequestParam(required = false) String q,
                 @RequestParam(required = false) String after, @RequestParam(defaultValue = "100") int size,
                 @RequestParam(defaultValue = "false") boolean flat) {
    Release release = catalog.resolvePublished(dataset, releaseId);
    return catalog.nodes(dataset, release.id(), parentCode, q, after, size, flat);
  }

  @GetMapping("/datasets/{dataset}/nodes/{code}")
  Node node(@PathVariable String dataset, @PathVariable String code, @RequestParam(required = false) Long releaseId) {
    return catalog.node(catalog.resolvePublished(dataset, releaseId).id(), code);
  }
}
