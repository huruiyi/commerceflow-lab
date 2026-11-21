package vip.fairy.commerceflow.navigation;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

@Component
class BrowserLauncher {

  private static final List<String> LOOPBACK_HOSTS = List.of("127.0.0.1", "localhost", "[::1]");
  private final NavigationMonitor monitor;

  BrowserLauncher(NavigationMonitor monitor) {
    this.monitor = monitor;
  }

  void open(String browserId, String serviceId, String requestedPath, int port) {
    Browser browser = Arrays.stream(Browser.values())
        .filter(candidate -> candidate.id.equals(browserId))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST));
    URI target = monitor.snapshot().services().stream()
        .filter(service -> service.id().equals(serviceId))
        .flatMap(service -> service.links().stream())
        .filter(NavigationSnapshot.Link::browserLaunch)
        .map(link -> URI.create(link.url()))
        .filter(uri -> isLocalBrowserLink(uri, requestedPath, port))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    Path executable = browser.executables()
        .filter(path -> Files.isRegularFile(path) && Files.isExecutable(path))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));

    try {
      new ProcessBuilder(executable.toString(), target.toASCIIString())
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start();
    } catch (IOException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
    }
  }

  private static boolean isLocalBrowserLink(URI uri, String requestedPath, int port) {
    return "http".equals(uri.getScheme())
        && uri.getHost() != null && LOOPBACK_HOSTS.contains(uri.getHost())
        && port > 0 && uri.getPort() == port
        && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
        && uri.getRawPath().equals(requestedPath);
  }

  private enum Browser {
    CHROME("chrome", "Google/Chrome/Application/chrome.exe", "Google Chrome", "Google Chrome",
        List.of("/usr/bin/google-chrome", "/usr/bin/google-chrome-stable")),
    EDGE("edge", "Microsoft/Edge/Application/msedge.exe", "Microsoft Edge", "Microsoft Edge",
        List.of("/usr/bin/microsoft-edge", "/usr/bin/microsoft-edge-stable")),
    FIREFOX("firefox", "Mozilla Firefox/firefox.exe", "Firefox", "firefox",
        List.of("/usr/bin/firefox", "/usr/bin/firefox-esr", "/snap/bin/firefox"));

    private final String id;
    private final String windowsPath;
    private final String macApp;
    private final String macExecutable;
    private final List<String> linuxPaths;

    Browser(String id, String windowsPath, String macApp, String macExecutable, List<String> linuxPaths) {
      this.id = id;
      this.windowsPath = windowsPath;
      this.macApp = macApp;
      this.macExecutable = macExecutable;
      this.linuxPaths = linuxPaths;
    }

    Stream<Path> executables() {
      String system = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      if (system.startsWith("windows")) {
        return Stream.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")
            .map(System::getenv)
            .filter(Objects::nonNull)
            .map(folder -> Path.of(folder).resolve(windowsPath))
            .filter(Path::isAbsolute)
            .distinct();
      }
      if (system.startsWith("mac")) {
        String relativePath = macApp + ".app/Contents/MacOS/" + macExecutable;
        return Stream.of(Path.of("/Applications", relativePath),
            Path.of(System.getProperty("user.home"), "Applications", relativePath));
      }
      return linuxPaths.stream().map(Path::of);
    }
  }
}
