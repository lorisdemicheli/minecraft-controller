package it.lorisdemicheli.minecraft_servers_controller.service;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import io.kubernetes.client.custom.ContainerMetrics;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
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
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Live view of a server: info, console commands and logs.
 *
 * <p><b>Info stream lifecycle.</b> All viewers of a server share one {@link InfoStream}: a single
 * polling loop (Kubernetes + exec in the pod) publishing into a replay-latest sink. The number of
 * subscribers is tracked explicitly and every change happens inside
 * {@link ConcurrentHashMap#compute}, so creation, reuse and removal cannot race. When the last
 * viewer leaves, the polling continues for a grace period (a page reload reuses it), then it is
 * stopped and the entry removed. If the server is deleted the stream completes and is removed.
 */
@Slf4j
@Service
public class ServerConsoleService {

  private static final String LOG_PATH = "logs/latest.log";
  private static final int LOG_FIRST_LINES = 50;
  private static final int MAX_HISTORY_LINES = 5000;
  private static final int MAX_COMMAND_LENGTH = 1000;
  private static final String HEARTBEAT = "keepalive";
  private static final String[] STATUS_COMMAND =
      {"mc-monitor", "status", "--host", "localhost", "--port", "25565", "--json"};

  private final KubernetesGateway kubernetes;
  private final ServerManifestFactory factory;
  private final ServerStorage storage;
  private final MinecraftServerOptions options;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meters;
  private final Scheduler scheduler;

  private final ConcurrentHashMap<String, InfoStream> infoStreams = new ConcurrentHashMap<>();
  private final AtomicInteger logStreams = new AtomicInteger();

  @Autowired
  public ServerConsoleService(KubernetesGateway kubernetes, ServerManifestFactory factory,
      ServerStorage storage, MinecraftServerOptions options, ObjectMapper objectMapper,
      MeterRegistry meters) {
    this(kubernetes, factory, storage, options, objectMapper, meters, Schedulers.boundedElastic());
  }

  ServerConsoleService(KubernetesGateway kubernetes, ServerManifestFactory factory,
      ServerStorage storage, MinecraftServerOptions options, ObjectMapper objectMapper,
      MeterRegistry meters, Scheduler scheduler) {
    this.kubernetes = kubernetes;
    this.factory = factory;
    this.storage = storage;
    this.options = options;
    this.objectMapper = objectMapper;
    this.meters = meters;
    this.scheduler = scheduler;
    Gauge.builder("minecraft.controller.sse.info.streams", infoStreams, Map::size)
        .description("Shared info polling loops").register(meters);
    Gauge.builder("minecraft.controller.sse.info.subscribers", this, ServerConsoleService::infoSubscribers)
        .description("Clients connected to an info stream").register(meters);
    Gauge.builder("minecraft.controller.sse.log.streams", logStreams, AtomicInteger::get)
        .description("Clients connected to a log stream").register(meters);
  }

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
   * Info pushed every few seconds, shared by all viewers of the server (see the class comment).
   * Idle connections get an SSE comment as heartbeat.
   */
  public Flux<ServerSentEvent<ServerInstanceInfoDto>> infoStream(String name) {
    ServerNames.requireValid(name);
    if (!infoStreams.containsKey(name)) {
      kubernetes.getStatefulSet(namespace(), name); // 404 now rather than an empty stream
    }
    return Flux.defer(() -> {
      InfoStream stream = join(name);
      return withHeartbeat(stream.sink.asFlux()).doFinally(signal -> leave(stream));
    });
  }

  /** Number of shared info polling loops currently alive. */
  int activeInfoStreams() {
    return infoStreams.size();
  }

  private double infoSubscribers() {
    return infoStreams.values().stream().mapToInt(s -> s.subscribers).sum();
  }

  private final class InfoStream {
    final String name;
    final Sinks.Many<ServerSentEvent<ServerInstanceInfoDto>> sink =
        Sinks.many().replay().latest();
    // guarded by infoStreams.compute on this name
    int subscribers;
    Disposable poller;
    Disposable stopTimer;

    InfoStream(String name) {
      this.name = name;
    }
  }

  private InfoStream join(String name) {
    InfoStream[] joined = new InfoStream[1];
    infoStreams.compute(name, (key, stream) -> {
      InfoStream s = stream == null ? new InfoStream(key) : stream;
      s.subscribers++;
      if (s.stopTimer != null) {
        s.stopTimer.dispose();
        s.stopTimer = null;
      }
      if (s.poller == null) {
        s.poller = startPolling(s);
        log.debug("Info polling started for server {}", key);
      }
      joined[0] = s;
      return s;
    });
    return joined[0];
  }

  private void leave(InfoStream stream) {
    infoStreams.computeIfPresent(stream.name, (key, current) -> {
      if (current != stream) {
        return current; // already replaced (server deleted and stream recreated)
      }
      if (--current.subscribers == 0) {
        current.stopTimer = scheduler.schedule(() -> stopIfUnused(current),
            options.getConsole().getInfoStreamGracePeriod().toMillis(), TimeUnit.MILLISECONDS);
      }
      return current;
    });
  }

  private void stopIfUnused(InfoStream stream) {
    infoStreams.computeIfPresent(stream.name, (key, current) -> {
      if (current != stream || current.subscribers > 0) {
        return current;
      }
      log.debug("Info polling stopped for server {} (no viewers)", key);
      shutdown(current);
      return null;
    });
  }

  /** Server deleted: completes every viewer and forgets the stream. */
  private void remove(InfoStream stream) {
    infoStreams.computeIfPresent(stream.name, (key, current) -> {
      if (current != stream) {
        return current;
      }
      shutdown(current);
      return null;
    });
  }

  private static void shutdown(InfoStream stream) {
    if (stream.poller != null) {
      stream.poller.dispose();
    }
    if (stream.stopTimer != null) {
      stream.stopTimer.dispose();
    }
    stream.sink.emitComplete(Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100)));
  }

  private Disposable startPolling(InfoStream stream) {
    String name = stream.name;
    return Flux.interval(Duration.ZERO, options.getConsole().getInfoPollInterval(), scheduler)
        .onBackpressureDrop() // a slow poll skips ticks instead of queueing them
        .concatMap(tick -> Mono.fromCallable(() -> info(name)) //
            .onErrorResume(e -> {
              if (e instanceof ResourceNotFoundException) {
                return Mono.error(e);
              }
              meters.counter("minecraft.controller.console.poll.errors").increment();
              log.debug("Cannot read info of server {}", name, e);
              return Mono.empty();
            }))
        .subscribe(
            info -> stream.sink.emitNext(
                ServerSentEvent.<ServerInstanceInfoDto>builder(info).event(name).build(),
                Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100))),
            error -> {
              log.debug("Info stream of server {} ended: {}", name, error.getMessage());
              remove(stream);
            });
  }

  private void readStatus(String name, ServerInstanceInfoDto info) {
    try {
      ExecResult result = kubernetes.exec(namespace(), ServerManifestFactory.podName(name),
          ServerManifestFactory.CONTAINER_NAME, STATUS_COMMAND);
      if (!result.ok() || result.truncated() || result.stdout().isBlank()) {
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

  /**
   * Writes one line to the server console. Line breaks are refused: they would smuggle more
   * commands into the console pipe. The command text is not logged (it can contain passwords of
   * login plugins).
   */
  public void sendCommand(String name, String command) {
    if (command == null || command.isBlank()) {
      throw new InvalidRequestException("Command is empty");
    }
    String line = command.strip();
    if (line.length() > MAX_COMMAND_LENGTH) {
      throw new InvalidRequestException("Command too long (max " + MAX_COMMAND_LENGTH + ")");
    }
    if (line.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      throw new InvalidRequestException("Command must be a single line without control characters");
    }
    V1StatefulSet sts = kubernetes.getStatefulSet(namespace(), ServerNames.requireValid(name));
    if (ServerStates.of(sts) != ServerState.RUNNING) {
      throw new ConflictException("Server is not running");
    }
    ExecResult result = kubernetes.exec(namespace(), ServerManifestFactory.podName(name),
        ServerManifestFactory.CONTAINER_NAME, factory.consoleCommand(line));
    if (!result.ok()) {
      String detail = result.stderr().strip();
      log.warn("Console command failed: server={} exit={} stderr={}", name, result.exitCode(),
          abbreviate(detail, 500));
      throw new ServerException("Command failed: " + abbreviate(detail, 200));
    }
    log.info("Console command sent: server={} length={}", name, line.length());
  }

  // ---------------------------------------------------------------- logs

  /**
   * The last lines, then everything new. It reads {@code logs/latest.log} straight from the shared
   * volume, so it works the same with the server running or stopped. Very long lines are cut.
   */
  public Flux<ServerSentEvent<String>> logStream(String name) {
    LogFile.Source file = logFile(name);
    return Flux.defer(() -> {
      logStreams.incrementAndGet();
      LogFile.Chunk first = LogFile.tail(file, LOG_FIRST_LINES);
      AtomicLong position = new AtomicLong(first.position());
      Flux<String> follow = Flux.interval(options.getConsole().getLogPollInterval(), scheduler) //
          .onBackpressureDrop() //
          .concatMapIterable(tick -> poll(file, position));
      Flux<ServerSentEvent<String>> lines = Flux.fromIterable(first.lines()).concatWith(follow)
          .map(line -> ServerSentEvent.builder(truncate(line)).build());
      return withHeartbeat(lines).doFinally(signal -> logStreams.decrementAndGet());
    }).subscribeOn(scheduler);
  }

  /** Skips the last {@code skip} lines, then returns the {@code limit} lines before them. */
  public List<String> logHistory(String name, int limit, int skip) {
    int safeLimit = Math.min(Math.max(limit, 0), MAX_HISTORY_LINES);
    return LogFile.window(logFile(name), safeLimit, Math.max(skip, 0)).stream()
        .map(this::truncate).toList();
  }

  private static List<String> poll(LogFile.Source file, AtomicLong position) {
    try {
      LogFile.Chunk chunk = LogFile.readFrom(file, position.get());
      position.set(chunk.position());
      return chunk.lines();
    } catch (UncheckedIOException e) {
      return List.of(); // transient (rotation in progress): try again on the next tick
    }
  }

  private LogFile.Source logFile(String name) {
    if (!storage.exists(name)) {
      throw new ResourceNotFoundException("Server not found: " + name);
    }
    return storage.logSource(name, LOG_PATH);
  }

  private String truncate(String line) {
    int max = options.getConsole().getMaxLogLineLength();
    return line.length() <= max ? line : line.substring(0, max) + " … [truncated]";
  }

  // ---------------------------------------------------------------- helpers

  private <T> Flux<ServerSentEvent<T>> withHeartbeat(Flux<ServerSentEvent<T>> events) {
    Flux<ServerSentEvent<T>> heartbeat =
        Flux.interval(options.getConsole().getHeartbeatInterval(), scheduler)
            .onBackpressureDrop()
            .map(tick -> ServerSentEvent.<T>builder().comment(HEARTBEAT).build());
    // the heartbeat must stop when the events complete (server deleted)
    return events.publish(shared -> Flux.merge(shared, heartbeat.takeUntilOther(shared.ignoreElements())));
  }

  private static String abbreviate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }

  private String namespace() {
    return options.getNamespace();
  }
}
