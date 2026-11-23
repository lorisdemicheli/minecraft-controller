package it.lorisdemicheli.minecraft_servers_controller.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import it.lorisdemicheli.minecraft_servers_controller.exception.KubernetesUnavailableException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceNotFoundException;
import it.lorisdemicheli.minecraft_servers_controller.security.JwtComponent;
import it.lorisdemicheli.minecraft_servers_controller.service.KubernetesGateway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "it.lorisdemicheli.minecraft-servers.security.password=test-password",
    "it.lorisdemicheli.minecraft-servers.security.jwt-secret=test-secret-test-secret-test-secret-123",
    "it.lorisdemicheli.minecraft-servers.security.login-max-attempts=3",
    "it.lorisdemicheli.minecraft-servers.storage.data-dir=target/it-data",
    "management.server.port=0"})
class ApiIntegrationTest {

  @LocalServerPort
  int port;

  @LocalManagementPort
  int managementPort;

  @MockitoBean
  KubernetesGateway kubernetes;

  @Autowired
  JwtComponent jwt;

  ObjectMapper mapper = new ObjectMapper();
  HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void setUp() {
    when(kubernetes.listStatefulSets(anyString(), anyString())).thenReturn(List.of());
  }

  HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpRequest.Builder get(String path) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
  }

  HttpRequest.Builder authed(String path) {
    return get(path).header("Authorization", "Bearer " + jwt.generateToken("admin"));
  }

  HttpRequest.Builder login(String user, String password) {
    return get("/login").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers
        .ofString("{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}"));
  }

  JsonNode json(HttpResponse<String> response) {
    return mapper.readTree(response.body());
  }

  @Test
  void requestsWithoutTokenGet401Json() throws Exception {
    HttpResponse<String> r = send(get("/servers"));
    assertEquals(401, r.statusCode());
    assertEquals(401, json(r).get("status").asInt());
    assertEquals("Authentication required", json(r).get("message").asString());
  }

  @Test
  void badTokenIsDistinguished() throws Exception {
    HttpResponse<String> r = send(get("/servers").header("Authorization", "Bearer nope"));
    assertEquals(401, r.statusCode());
    assertEquals("Invalid token", json(r).get("message").asString());
  }

  @Test
  void validTokenWorks() throws Exception {
    HttpResponse<String> r = send(authed("/servers"));
    assertEquals(200, r.statusCode());
    assertEquals("[]", r.body());
  }

  @Test
  void loginReturnsAWorkingTokenAndFailuresAreThrottled() throws Exception {
    HttpResponse<String> ok = send(login("admin", "test-password"));
    assertEquals(200, ok.statusCode());
    String token = json(ok).get("token").asString();
    assertEquals(200, send(get("/servers").header("Authorization", "Bearer " + token)).statusCode());

    // the admin can log in again and again (regression: the first login used to erase the password)
    assertEquals(200, send(login("admin", "test-password")).statusCode());
    assertEquals(200, send(login("admin", "test-password")).statusCode());

    for (int i = 0; i < 3; i++) {
      HttpResponse<String> bad = send(login("admin", "wrong"));
      assertEquals(401, bad.statusCode());
      assertFalse(bad.body().contains("wrong"));
    }
    HttpResponse<String> throttled = send(login("admin", "test-password"));
    assertEquals(429, throttled.statusCode());
    assertTrue(throttled.headers().firstValue("Retry-After").isPresent());
  }

  @Test
  void applicationErrorsAreMappedWithoutInternals() throws Exception {
    when(kubernetes.getStatefulSet(anyString(), anyString()))
        .thenThrow(new ResourceNotFoundException("Server ghost not found"));
    HttpResponse<String> notFound = send(authed("/servers/ghost"));
    assertEquals(404, notFound.statusCode());
    assertEquals("Server ghost not found", json(notFound).get("message").asString());

    when(kubernetes.listStatefulSets(anyString(), anyString()))
        .thenThrow(new KubernetesUnavailableException("The Kubernetes API is not available"));
    assertEquals(503, send(authed("/servers")).statusCode());

    HttpResponse<String> badName = send(authed("/servers/Bad_Name"));
    assertEquals(400, badName.statusCode());
  }

  @Test
  void missingParametersAre400Not500() throws Exception {
    HttpResponse<String> r = send(authed("/servers/alpha/files/content"));
    assertEquals(400, r.statusCode());
    assertFalse(r.body().contains("Exception"), "no class names or stack traces");
  }

  @Test
  void unexpectedErrorsHideTheirMessage() throws Exception {
    when(kubernetes.listStatefulSets(anyString(), anyString()))
        .thenThrow(new IllegalStateException("db password is hunter2"));
    HttpResponse<String> r = send(authed("/servers"));
    assertEquals(500, r.statusCode());
    assertFalse(r.body().contains("hunter2"));
    assertTrue(json(r).has("errorId"));
  }

  @Test
  void sseEndpointErrorsAreStillJson() throws Exception {
    when(kubernetes.getStatefulSet(anyString(), anyString()))
        .thenThrow(new ResourceNotFoundException("Server ghost not found"));
    HttpResponse<String> r =
        send(authed("/servers/ghost/console/info/stream").header("Accept", "text/event-stream"));
    assertEquals(404, r.statusCode());
    assertTrue(r.body().contains("Server ghost not found"));
  }

  @Test
  void logStreamKeepsTheWireFormatOfTheConsole() throws Exception {
    java.nio.file.Path logs = java.nio.file.Path.of("target/it-data/alpha/logs");
    java.nio.file.Files.createDirectories(logs);
    java.nio.file.Files.writeString(logs.resolve("latest.log"), "[12:00:00] [Server thread/INFO]: one\ntwo\n");

    HttpResponse<java.io.InputStream> r = http.send(
        authed("/servers/alpha/console/logs").header("Accept", "text/event-stream").build(),
        HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, r.statusCode());
    assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
    StringBuilder received = new StringBuilder();
    try (java.io.InputStream in = r.body()) {
      byte[] buf = new byte[256];
      while (!received.toString().contains("data:two\n\n")) {
        int n = in.read(buf);
        if (n < 0) {
          break;
        }
        received.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
      }
    }
    // one "data:" event per line, as the console parser expects
    assertTrue(received.toString().contains("data:[12:00:00] [Server thread/INFO]: one\n\n"),
        received.toString());
    assertTrue(received.toString().contains("data:two\n\n"), received.toString());
  }

  @Test
  void healthProbesAreOnTheManagementPortWithoutAuthentication() throws Exception {
    for (String probe : new String[] {"liveness", "readiness"}) {
      HttpResponse<String> r = send(HttpRequest.newBuilder(
          URI.create("http://localhost:" + managementPort + "/actuator/health/" + probe)));
      assertEquals(200, r.statusCode(), probe + ": " + r.body());
      assertTrue(r.body().contains("UP"));
    }
    // and not on the API port
    assertEquals(401, send(get("/actuator/env")).statusCode());
  }
}
