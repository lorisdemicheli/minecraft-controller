package it.lorisdemicheli.minecraft_servers_controller.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.config.TestOptions;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ServerException;
import it.lorisdemicheli.minecraft_servers_controller.integration.CurseForgeClient;
import tools.jackson.databind.ObjectMapper;

/** Against a local fake of the CurseForge API: no network access. */
class CurseForgeClientTest {

  HttpServer server;
  AtomicReference<String> apiKey = new AtomicReference<>();
  int status = 200;
  MinecraftServerOptions options = TestOptions.valid();

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/mods/search", exchange -> {
      apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
      byte[] body = ("{\"data\":[{\"id\":7,\"name\":\"ATM9\",\"summary\":\"s\",\"downloadCount\":5,"
          + "\"logo\":{\"url\":\"https://x/logo.png\"},\"links\":{\"websiteUrl\":"
          + "\"https://www.curseforge.com/minecraft/modpacks/atm9\"}}]}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    options.setCurseForgeApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void searchUsesTheServerSideKey() {
    options.setCurseForgeApiKey("server-key");
    var hits = new CurseForgeClient(options, new ObjectMapper()).searchModpacks("all the mods");
    assertEquals(1, hits.size());
    assertEquals("ATM9", hits.get(0).name());
    assertEquals("https://www.curseforge.com/minecraft/modpacks/atm9", hits.get(0).websiteUrl());
    assertEquals("server-key", apiKey.get());
  }

  @Test
  void disabledWithoutKey() {
    CurseForgeClient client = new CurseForgeClient(options, new ObjectMapper());
    assertFalse(client.enabled());
    assertThrows(ConflictException.class, () -> client.searchModpacks("x"));
  }

  @Test
  void refusedKeyIsAServerError() {
    options.setCurseForgeApiKey("bad");
    status = 403;
    assertThrows(ServerException.class,
        () -> new CurseForgeClient(options, new ObjectMapper()).searchModpacks("x"));
  }
}
