package vip.fairy.commerceflow.notification.infrastructure.security;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NotificationWritePause {

  private final Path marker;

  NotificationWritePause(@Value("${commerceflow.notification.write-pause-file:}") String marker) {
    this.marker = marker.isBlank() ? null : Path.of(marker).toAbsolutePath();
  }

  public boolean paused() {
    return marker != null && (!Files.isDirectory(marker.getParent()) || !Files.notExists(marker));
  }
}
