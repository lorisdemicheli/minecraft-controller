package it.lorisdemicheli.minecraft_servers_controller.service;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import io.kubernetes.client.custom.ContainerMetrics;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.domain.PlayerDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerDescriptionDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceInfoDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerPopulationDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerState;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerVersionDto;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceNotFoundException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ServerException;
import it.lorisdemicheli.minecraft_servers_controller.storage.LogFile;
import it.lorisdemicheli.minecraft_servers_controller.storage.ServerNames;
import it.lorisdemicheli.minecraft_servers_controller.storage.ServerStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Live view of a server: info, console commands and logs. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServerConsoleService {

  private static final String LOG_PATH = "logs/latest.log";
  private static final int LOG_FIRST_LINES = 50;
  private static final int MAX_HISTORY_LINES = 5000;
  private static final Duration LOG_POLL = Duration.ofMillis(500);
  private static final Duration INFO_POLL = Duration.ofSeconds(10);
  private static final String[] STATUS_COMMAND =
      {"mc-monitor", "status", "--host", "localhost", "--port", "25565", "--json"};

  private final KubernetesGateway kubernetes;
  private final ServerManifestFactory factory;
  private final ServerStorage storage;
  private final MinecraftServerOptions options;
  private final ObjectMapper objectMapper;

  private final Map<String, Flux<ServerSentEvent<ServerInstanceInfoDto>>> infoStreams =
      new ConcurrentHashMap<>();

  // ---------------------------------------------------------------- info

  public ServerInstanceInfoDto info(String name) {
    V1StatefulSet sts = kubernetes.getStatefulSet(namespace(), ServerNames.requireValid(name));
    ServerState state = ServerStates.of(sts);

    ServerInstanceInfoDto info = new ServerInstanceInfoDto(state);
    factory.readConfig(sts).ifPresent(config -> {
      info.setMaxCpu((long) config.cpu());
      info.setMaxMemory((long) config.memory());
    });

    if (state == ServerState.RUNNING) {
      readStatus(name, info);
      readUsage(name, info);
    }
    return info;
  }

  /**
   * Info pushed every few seconds. The polling is shared between all viewers of the same server
   * and stops when the last one leaves.
   */
  public Flux<ServerSentEvent<ServerInstanceInfoDto>> infoStream(String name) {
    info(name); // fail fast with a 404 instead of an empty stream
    return infoStreams.computeIfAbsent(name,
        n -> Flux.interval(Duration.ZERO, INFO_POLL, Schedulers.boundedElastic()) //
            .onBackpressureDrop() //
            .concatMap(tick -> Mono.fromCallable(() -> info(n)) //
                .onErrorResume(e -> {
                  if (e instanceof ResourceNotFoundException) {
                    return Mono.<ServerInstanceInfoDto>error(e);
                  }
                  log.debug("Cannot read info of server {}", n, e);
                  return Mono.<ServerInstanceInfoDto>empty();
                }))
            .onErrorResume(ResourceNotFoundException.class, e -> Flux.empty()) // server deleted
            .map(info -> ServerSentEvent.<ServerInstanceInfoDto>builder(info).event(n).build())
            .replay(1).refCount(1, Duration.ofSeconds(10)));
  }

  private void readStatus(String name, ServerInstanceInfoDto info) {
    try {
      ExecResult result = kubernetes.exec(namespace(), ServerManifestFactory.podName(name),
          ServerManifestFactory.CONTAINER_NAME, STATUS_COMMAND);
      if (!result.ok() || result.stdout().isBlank()) {
        return;
      }
      JsonNode serverInfo = objectMapper.readTree(result.stdout()).path("server_info");
      if (serverInfo.isMissingNode() || serverInfo.isNull()) {
        return;
      }
      if (serverInfo.has("version")) {
        info.setVersion(objectMapper.treeToValue(serverInfo.get("version"), ServerVersionDto.class));
      }
      if (serverInfo.has("players")) {
        JsonNode players = serverInfo.path("players");
        ServerPopulationDto population = new ServerPopulationDto();
        population.setOnline(players.path("online").asInt());
        population.setMax(players.path("max").asInt());
        population.setPlayers(objectMapper.convertValue(players.path("Samples"),
            new TypeReference<List<PlayerDto>>() {}));
        info.setPopulation(population);
      }
      if (serverInfo.has("description")) {
        info.setDescription(
            objectMapper.treeToValue(serverInfo.get("description"), ServerDescriptionDto.class));
      }
      if (serverInfo.has("favicon")) {
        info.setIcon(serverInfo.get("favicon").asString());
      }
    } catch (RuntimeException e) {
      log.debug("Cannot read the status of server {}", name, e);
    }
  }

  /** CPU in milli cpu and memory in MB, like the values the server was configured with. */
  private void readUsage(String name, ServerInstanceInfoDto info) {
    kubernetes.podMetrics(namespace(), ServerManifestFactory.podName(name)).ifPresent(metrics -> {
      if (metrics.getContainers() == null) {
        return;
      }
      BigDecimal cores = BigDecimal.ZERO;
      BigDecimal bytes = BigDecimal.ZERO;
      for (ContainerMetrics container : metrics.getContainers()) {
        Map<String, Quantity> usage = container.getUsage();
        if (usage == null) {
          continue;
        }
        if (usage.get("cpu") != null) {
          cores = cores.add(usage.get("cpu").getNumber());
        }
        if (usage.get("memory") != null) {
          bytes = bytes.add(usage.get("memory").getNumber());
        }
      }
      info.setCpuUsage(cores.multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.HALF_UP)
          .longValue());
      info.setMemoryUsage(
          bytes.divide(BigDecimal.valueOf(1024 * 1024), 0, RoundingMode.HALF_UP).longValue());
    });
  }

  // ---------------------------------------------------------------- commands

  public void sendCommand(String name, String command) {
    if (command == null || command.isBlank()) {
      throw new InvalidRequestException("Command is empty");
    }
    V1StatefulSet sts = kubernetes.getStatefulSet(namespace(), ServerNames.requireValid(name));
    if (ServerStates.of(sts) != ServerState.RUNNING) {
      throw new ConflictException("Server is not running");
    }
    ExecResult result = kubernetes.exec(namespace(), ServerManifestFactory.podName(name),
        ServerManifestFactory.CONTAINER_NAME, "gosu", "minecraft", "mc-send-to-console",
        command.strip());
    if (!result.ok()) {
      throw new ServerException("Command failed: " + result.stderr());
    }
  }

  // ---------------------------------------------------------------- logs

  /**
   * The last lines, then everything new. It reads {@code logs/latest.log} straight from the shared
   * volume, so it works the same with the server running or stopped.
   */
  public Flux<String> logStream(String name) {
    Path file = logFile(name);
    return Flux.defer(() -> {
      LogFile.Chunk first = LogFile.tail(file, LOG_FIRST_LINES);
      AtomicLong position = new AtomicLong(first.position());
      Flux<String> follow = Flux.interval(LOG_POLL, Schedulers.boundedElastic()) //
          .onBackpressureDrop() //
          .concatMapIterable(tick -> poll(file, position));
      return Flux.fromIterable(first.lines()).concatWith(follow);
    }).subscribeOn(Schedulers.boundedElastic());
  }

  /** Skips the last {@code skip} lines, then returns the {@code limit} lines before them. */
  public List<String> logHistory(String name, int limit, int skip) {
    int safeLimit = Math.min(Math.max(limit, 0), MAX_HISTORY_LINES);
    return LogFile.window(logFile(name), safeLimit, Math.max(skip, 0));
  }

  private static List<String> poll(Path file, AtomicLong position) {
    try {
      LogFile.Chunk chunk = LogFile.readFrom(file, position.get());
      position.set(chunk.position());
      return chunk.lines();
    } catch (UncheckedIOException e) {
      return List.of(); // transient (rotation in progress): try again on the next tick
    }
  }

  private Path logFile(String name) {
    if (!storage.exists(name)) {
      throw new ResourceNotFoundException("Server not found: " + name);
    }
    return storage.path(name, LOG_PATH);
  }

  private String namespace() {
    return options.getNamespace();
  }
}
