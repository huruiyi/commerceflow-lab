package vip.fairy.commerceflow.reference.catalog;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.Comparison;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.NodePage;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.Publication;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.PublishRequest;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.Release;

@RestController
@RequestMapping("/api/admin/reference-data/datasets/{dataset}")
class ReferenceAdminController {

  private final ReferenceCatalog catalog;
  private final ReferenceImport importer;

  ReferenceAdminController(ReferenceCatalog catalog, ReferenceImport importer) {
    this.catalog = catalog;
    this.importer = importer;
  }

  @GetMapping("/versions")
  List<Release> releases(@PathVariable String dataset, @RequestParam(required = false) Long before) {
    return catalog.releases(dataset, before);
  }

  @GetMapping("/publications")
  List<Publication> publications(@PathVariable String dataset, @RequestParam(required = false) Long before) {
    return catalog.publications(dataset, before);
  }

  @PostMapping(value = "/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  Release upload(@PathVariable String dataset, @RequestParam String version, @RequestParam String sourceUrl,
                 @RequestParam String sourceRevision, @RequestParam MultipartFile file, Authentication auth) {
    return importer.importFile(dataset, version, sourceUrl, sourceRevision, file, (Long) auth.getPrincipal());
  }

  @GetMapping("/versions/{id}/comparison")
  Comparison comparison(@PathVariable String dataset, @PathVariable long id) {
    return catalog.compare(dataset, id);
  }

  @GetMapping("/versions/{id}/nodes")
  NodePage nodes(@PathVariable String dataset, @PathVariable long id,
                 @RequestParam(required = false) String parentCode, @RequestParam(required = false) String q,
                 @RequestParam(required = false) String after, @RequestParam(defaultValue = "100") int size) {
    catalog.release(dataset, id);
    return catalog.nodes(dataset, id, parentCode, q, after, size, false);
  }

  @PostMapping("/versions/{id}/publish")
  Release publish(@PathVariable String dataset, @PathVariable long id, @RequestBody PublishRequest request,
                  Authentication auth) {
    return catalog.publish(dataset, id, request.expectedCurrentReleaseId(), (Long) auth.getPrincipal());
  }
}
