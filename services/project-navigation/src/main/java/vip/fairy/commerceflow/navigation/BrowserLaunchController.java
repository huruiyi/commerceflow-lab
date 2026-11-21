package vip.fairy.commerceflow.navigation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
class BrowserLaunchController {

  private final BrowserLauncher launcher;
  private final List<String> localOrigins;

  BrowserLaunchController(BrowserLauncher launcher, @Value("${server.port}") int port) {
    this.launcher = launcher;
    localOrigins = List.of("http://127.0.0.1:" + port, "http://localhost:" + port);
  }

  @PostMapping(path = "/api/navigation/open-browser", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void open(@RequestHeader(value = "Origin", defaultValue = "") String origin, @RequestBody BrowserRequest request) {
    if (!localOrigins.contains(origin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    launcher.open(request.browser(), request.serviceId(), request.path(), request.port());
  }

  record BrowserRequest(String browser, String serviceId, String path, int port) {
  }
}
