package vip.fairy.commerceflow.navigation;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import static vip.fairy.commerceflow.navigation.NavigationSnapshot.Probe;

@Component
class EndpointProbe {

  private static final int TIMEOUT_MS = 1200;
  private final ObjectMapper mapper;

  EndpointProbe(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  Probe check(String protocol, String host, int port, String path, boolean actuator) {
    long start = System.nanoTime();
    String address = hostPort(host, port);
    String target = path.isEmpty() ? address : "http://" + address + path;
    try {
      if (!path.isEmpty()) {
        return http(target, actuator, start);
      }
      try (var socket = new Socket()) {
        socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
        return result(target, "REACHABLE", protocol + " TCP 连接成功", start);
      }
    } catch (SocketTimeoutException exception) {
      return result(target, "TIMEOUT", "连接或响应超时", start);
    } catch (IOException exception) {
      return result(target, "UNREACHABLE", "无法连接或读取响应", start);
    }
  }

  private Probe http(String target, boolean actuator, long start) throws IOException {
    var connection = (HttpURLConnection) URI.create(target).toURL().openConnection(java.net.Proxy.NO_PROXY);
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(TIMEOUT_MS);
    connection.setReadTimeout(TIMEOUT_MS);
    try {
      int code = connection.getResponseCode();
      if (actuator && (code < 200 || code >= 300)) {
        return result(target, "FAILED", "Actuator HTTP " + code, start);
      }
      if (code == 401 || code == 403) {
        return result(target, "AUTH_REQUIRED", "HTTP " + code, start);
      }
      if (code >= 300 && code < 400) {
        return result(target, "REDIRECT", "HTTP " + code, start);
      }
      if (code < 200 || code >= 300) {
        return result(target, "FAILED", "HTTP " + code, start);
      }
      if (actuator) {
        try (var body = connection.getInputStream()) {
          String status = mapper.readTree(body.readNBytes(4096)).path("status").asString("");
          if (!"UP".equals(status)) {
            return result(target, "FAILED", "Actuator 未返回 UP", start);
          }
        } catch (tools.jackson.core.JacksonException exception) {
          return result(target, "FAILED", "无法解析 Actuator 健康响应", start);
        }
      }
      return result(target, "REACHABLE", actuator ? "Actuator UP" : "HTTP " + code, start);
    } finally {
      connection.disconnect();
    }
  }

  static String hostPort(String host, int port) {
    return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
  }

  private static Probe result(String address, String status, String detail, long start) {
    return new Probe(address, status, detail, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }
}
