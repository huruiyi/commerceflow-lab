package vip.fairy.commerceflow.navigation;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NavigationController {

  private final NavigationMonitor monitor;

  NavigationController(NavigationMonitor monitor) {
    this.monitor = monitor;
  }

  @GetMapping("/api/navigation/snapshot")
  ResponseEntity<NavigationSnapshot> snapshot() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(monitor.snapshot());
  }
}
