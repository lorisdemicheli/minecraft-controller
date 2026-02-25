package it.lorisdemicheli.minecraft_servers_controller.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import it.lorisdemicheli.minecraft_servers_controller.domain.PlayerDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerDescriptionDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceInfoDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerPopulationDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerState;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerVersionDto;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class MinecraftConsoleService {

  private static final String LOG_FILE = "/data/logs/latest.log";
  private static final int LOG_FIRST_LINES = 50;

  private final ObjectMapper objectMapper;
  private final KubernetesAsyncService kubernetesService;
  private final Map<String, Flux<String>> logStream = new ConcurrentHashMap<>();
  private final Map<String, Flux<ServerSentEvent<ServerInstanceInfoDto>>> infoStream = new ConcurrentHashMap<>();

  public Mono<String> sendMinecraftCommand(String namespace, String pod, String container,
      String command) {
    String cmd[] = {"gosu", "minecraft", "mc-send-to-console", command};
    return kubernetesService.execCommand(namespace, pod, container, cmd);
  }

  public Flux<String> getStreamLogs(String namespace, String pod, String container) {
    String key = getKey(namespace, pod, container);

    if (!logStream.containsKey(key)) {
      Flux<String> newLog = kubernetesService.execStream(namespace, pod, container, //
          new String[] {"tail", "-n", Integer.toString(LOG_FIRST_LINES), "-f", LOG_FILE}) //
          .doFinally(signal -> {
            logStream.remove(key);
          }) //
          .subscribeOn(Schedulers.boundedElastic()) //
          .publish() //
          .refCount(1, Duration.ofSeconds(5));
      logStream.put(key, newLog);
      return newLog;
    } else {
      return getLogs(namespace, pod, container, LOG_FIRST_LINES, 0) //
          .flatMapIterable(list -> list) //
          .concatWith(logStream.get(key));
    }
  }

  public Mono<List<String>> getLogs(String namespace, String pod, String container, int limit,
      int skip) {
    String command = String.format("head -n -%d %s | tail -n %d", skip, LOG_FILE, limit);
    return kubernetesService
        .execStream(namespace, pod, container, new String[] {"sh", "-c", command}) //
        .collectList();
  }

  public Flux<ServerSentEvent<ServerInstanceInfoDto>> getStreamServerInfo(String serverName, String namespace, String pod,
      String container) {
    ;
    return infoStream.computeIfAbsent(getKey(namespace, pod, container), //
        k -> Flux.interval(Duration.ZERO, Duration.ofSeconds(10))
    .switchMap(i -> 
        getServerState(namespace, pod)
            .flatMap(state -> getMonoServerInfo(namespace, pod, container, state))
            .onErrorResume(e -> {
                return Mono.empty(); 
            })
    )
    // 3. .map() invece di .flatMap() perché il builder crea un oggetto, non un Flux/Mono
    .map(info -> ServerSentEvent.<ServerInstanceInfoDto>builder(info)
        .event(serverName)
        .build())
    .doFinally(signal -> {
        infoStream.remove(getKey(namespace, pod, container));
    })
    .replay(1)
    .refCount());
  }
//  public Flux<ServerSentEvent<ServerInstanceInfoDto>> getStreamServerInfo(
//      String serverName, String namespace, String pod, String container) {
//
//      String key = getKey(namespace, pod, container);
//
//      return infoStream.computeIfAbsent(key, k -> 
//          Flux.interval(Duration.ZERO, Duration.ofSeconds(10))
//              .flatMap(i -> getServerState(namespace, pod))
//              .flatMap(state -> getMonoServerInfo(pod, container, state))
//              .map(info -> ServerSentEvent.<ServerInstanceInfoDto>builder(info)
//                  .event(serverName)
//                  .build())
//              .doFinally(signal -> {
//                  // Rimuoviamo dalla mappa solo quando il flusso termina davvero 
//                  // o non ci sono più sottoscrittori
//                  infoStream.remove(key);
//              })
//              .replay(1)
//              .refCount()
//      );
//  }

  public Mono<ServerInstanceInfoDto> getServerInfo(String namespace, String pod, String container) {
    return getServerState(namespace, pod) //
        .flatMap(state -> getMonoServerInfo(namespace, pod, container, state));
  }

  private Mono<ServerState> getServerState(String namespace, String podName) {
    return kubernetesService.getNamespacedPod(namespace, podName).map(pod -> {
      if (pod.getMetadata().getDeletionTimestamp() != null) {
        return ServerState.SHUTDOWN;
      }

      boolean isReady = pod.getStatus().getConditions() //
          .stream() //
          .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));

      if (!isReady) {
        return ServerState.STARTING;
      }

      return ServerState.RUNNING;
    }).onErrorReturn(ServerState.STOPPED);
  }

  private Mono<ServerInstanceInfoDto> getMonoServerInfo(String namespace, String pod,
      String container, ServerState state) {
    if (state != ServerState.RUNNING) {
      return Mono.just(new ServerInstanceInfoDto(state));
    }

    String[] cmd = {"mc-monitor", "status", "--host", "localhost", "--port", "25565", "--json"};
    return kubernetesService //
        .execCommand(namespace, pod, container, cmd) //
        .map(json -> {
          ServerInstanceInfoDto instanceDto = new ServerInstanceInfoDto(state);
          JsonNode root = objectMapper.readTree(json);
          JsonNode serverInfo = root.path("server_info");
          if (!serverInfo.isMissingNode() && !serverInfo.isNull()) {
            if (serverInfo.has("version")) {
              instanceDto.setVersion(
                  objectMapper.treeToValue(serverInfo.get("version"), ServerVersionDto.class));
            }
            if (serverInfo.has("players")) {
              ServerPopulationDto population = new ServerPopulationDto();
              JsonNode players = serverInfo.path("players");
              population.setOnline(players.path("online").asInt());
              population.setMax(players.path("max").asInt());
              population.setPlayers(objectMapper.convertValue(players.path("Samples"),
                  new TypeReference<List<PlayerDto>>() {}));
              instanceDto.setPopulation(population);
            }
            if (serverInfo.has("description")) {
              instanceDto.setDescription(objectMapper.treeToValue(serverInfo.get("description"),
                  ServerDescriptionDto.class));
            }
            if (serverInfo.has("favicon")) {
              instanceDto.setIcon(serverInfo.get("favicon").asString());
            }
          }
          return instanceDto;
        });
  }

  private String getKey(String namespace, String pod, String container) {
    return String.format("%s-%s-%s", namespace, pod, container);
  }

}
