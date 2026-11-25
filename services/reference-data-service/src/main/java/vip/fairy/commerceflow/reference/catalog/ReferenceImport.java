package vip.fairy.commerceflow.reference.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.Node;
import vip.fairy.commerceflow.reference.catalog.ReferenceContracts.Release;

@Service
class ReferenceImport {

  private static final int BATCH_SIZE = 1000;
  private static final int MAX_ROWS = 1_000_000;
  private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final List<String> HEADER = List.of("code", "parent_code", "level", "name");
  private static final List<String> METADATA_HEADER =
    List.of("code", "parent_code", "level", "name", "source_metadata");
  private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
    .setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).get();

  private final ReferenceMapper store;

  ReferenceImport(ReferenceMapper store) {
    this.store = store;
  }

  @Transactional
  Release importFile(String key, String version, String sourceUrl, String sourceRevision,
                     MultipartFile file, long actor) {
    if (!CODE.matcher(version).matches() || sourceRevision.isBlank() || sourceRevision.length() > 160
      || !validSource(sourceUrl) || file.isEmpty()) {
      throw invalid("Provide a version, source URL, source revision and a nonempty UTF-8 CSV");
    }
    if (store.lockDataset(key) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset not found");
    }
    try {
      String hash = checksum(file);
      Release existing = store.findVersion(key, version);
      if (existing != null) {
        if (existing.sha256().equals(hash) && existing.sourceUrl().equals(sourceUrl)
          && existing.sourceRevision().equals(sourceRevision)) {
          return existing;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Version already exists with different content");
      }
      long id = store.insertRelease(key, version, sourceUrl, sourceRevision, hash, actor);
      int count = loadRows(file, id);
      String invalidParent = store.invalidParent(id, "cn-administrative-divisions".equals(key));
      if (invalidParent != null) {
        throw invalid("Missing or incorrect parent for " + invalidParent);
      }
      store.recordCount(id, count);
      return store.release(key, id);
    } catch (DataIntegrityViolationException exception) {
      throw invalid("The CSV contains duplicate codes or invalid rows");
    } catch (IOException | UncheckedIOException | IllegalArgumentException exception) {
      throw invalid("Could not read the UTF-8 CSV");
    }
  }

  private int loadRows(MultipartFile file, long id) throws IOException {
    var decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    try (var reader = new PushbackReader(new InputStreamReader(file.getInputStream(), decoder))) {
      int first = reader.read();
      if (first != -1 && first != '\uFEFF') {
        reader.unread(first);
      }
      try (var parser = CSVParser.builder().setReader(reader).setFormat(FORMAT).get()) {
        boolean hasMetadata = parser.getHeaderNames().equals(METADATA_HEADER);
        if (!hasMetadata && !parser.getHeaderNames().equals(HEADER)) {
          throw invalid("Expected CSV header: code,parent_code,level,name[,source_metadata]");
        }
        List<Node> batch = new ArrayList<>(BATCH_SIZE);
        int count = 0;
        for (CSVRecord row : parser) {
          if (++count > MAX_ROWS) {
            throw invalid("A version accepts up to 1,000,000 rows");
          }
          batch.add(parse(row, hasMetadata));
          if (batch.size() == BATCH_SIZE) {
            store.insertNodes(id, batch);
            batch.clear();
          }
        }
        if (count == 0) {
          throw invalid("The dataset contains no rows");
        }
        if (!batch.isEmpty()) {
          store.insertNodes(id, batch);
        }
        return count;
      }
    }
  }

  private static Node parse(CSVRecord row, boolean hasMetadata) {
    int expectedColumns = hasMetadata ? METADATA_HEADER.size() : HEADER.size();
    if (row.size() != expectedColumns || !row.get("level").matches("[1-5]")) {
      throw invalid("Invalid level or column count at row " + row.getRecordNumber());
    }
    String code = row.get("code");
    String parent = row.get("parent_code");
    String name = row.get("name");
    int level = Integer.parseInt(row.get("level"));
    if (!CODE.matcher(code).matches() || (level == 1 ? !parent.isEmpty() : !CODE.matcher(parent).matches())
      || name.isBlank() || name.length() > 200 || name.indexOf(0) >= 0) {
      throw invalid("Invalid code, parent or name at row " + row.getRecordNumber());
    }
    String metadata = hasMetadata ? row.get("source_metadata") : "";
    if (metadata.length() > 8000) {
      throw invalid("Source metadata is too long at row " + row.getRecordNumber());
    }
    return new Node(code, parent.isEmpty() ? null : parent, level, name, metadata.isBlank() ? null : metadata);
  }

  private static boolean validSource(String source) {
    if (source.length() > 500) {
      return false;
    }
    try {
      URI uri = URI.create(source);
      return Set.of("http", "https").contains(Objects.toString(uri.getScheme(), ""))
        && uri.getHost() != null && uri.getUserInfo() == null;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static String checksum(MultipartFile file) throws IOException {
    try (InputStream in = file.getInputStream()) {
      var hash = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[65536];
      int length;
      while ((length = in.read(buffer)) != -1) {
        hash.update(buffer, 0, length);
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static ResponseStatusException invalid(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
