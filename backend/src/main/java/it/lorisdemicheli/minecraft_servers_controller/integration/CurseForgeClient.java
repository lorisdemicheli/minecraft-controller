package it.lorisdemicheli.minecraft_servers_controller.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ServerException;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Modpack search on CurseForge done by the controller, so that the API key stays on the server
 * (it used to be compiled into the web console, i.e. public).
 */
@Slf4j
@Component
public class CurseForgeClient {

  private static final int MINECRAFT_GAME_ID = 432;
  private static final int MODPACK_CLASS_ID = 4471;
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  public record Modpack(long id, String name, String summary, String logoUrl, long downloadCount,
      String websiteUrl) {
  }

  private final MinecraftServerOptions options;
  private final ObjectMapper mapper;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();

  public CurseForgeClient(MinecraftServerOptions options, ObjectMapper mapper) {
    this.options = options;
    this.mapper = mapper;
  }

  public boolean enabled() {
    String key = options.getCurseForgeApiKey();
    return key != null && !key.isBlank();
  }

  public List<Modpack> searchModpacks(String query) {
    if (!enabled()) {
      throw new ConflictException("The CurseForge API key is not configured on the server");
    }
    if (query == null || query.isBlank() || query.length() > 100) {
      throw new InvalidRequestException("query is required (max 100 characters)");
    }
    URI uri = URI.create(options.getCurseForgeApiUrl() + "/v1/mods/search?gameId=" + MINECRAFT_GAME_ID
        + "&classId=" + MODPACK_CLASS_ID + "&pageSize=12&searchFilter="
        + URLEncoder.encode(query.strip(), StandardCharsets.UTF_8));
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
        .header("x-api-key", options.getCurseForgeApiKey()).header("Accept", "application/json")
        .GET().build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (java.io.IOException e) {
      log.warn("CurseForge search failed: {}", e.getMessage());
      throw new ServerException("CurseForge is not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServerException("Interrupted", e);
    }
    if (response.statusCode() == 401 || response.statusCode() == 403) {
      log.error("CurseForge refused the configured API key ({})", response.statusCode());
      throw new ServerException("The CurseForge API key was refused");
    }
    if (response.statusCode() != 200) {
      log.warn("CurseForge search answered {}", response.statusCode());
      throw new ServerException("CurseForge search failed (" + response.statusCode() + ")");
    }
    List<Modpack> result = new ArrayList<>();
    for (JsonNode hit : mapper.readTree(response.body()).path("data")) {
      result.add(new Modpack(hit.path("id").asLong(), hit.path("name").asString(),
          hit.path("summary").asString(), text(hit.path("logo").path("url")),
          hit.path("downloadCount").asLong(), text(hit.path("links").path("websiteUrl"))));
    }
    return result;
  }

  private static String text(JsonNode node) {
    return node.isMissingNode() || node.isNull() ? null : node.asString();
  }
}
