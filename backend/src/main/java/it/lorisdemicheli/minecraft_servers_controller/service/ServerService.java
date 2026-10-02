package it.lorisdemicheli.minecraft_servers_controller.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceDto;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceNotFoundException;
import it.lorisdemicheli.minecraft_servers_controller.storage.ServerNames;
import it.lorisdemicheli.minecraft_servers_controller.storage.ServerStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Lifecycle of servers: create / read / update / delete and start / stop. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServerService {

  private static final long GIB = 1024L * 1024 * 1024;

  private final KubernetesGateway kubernetes;
  private final ServerManifestFactory factory;
  private final ServerStorage storage;
  private final MinecraftServerOptions options;

  // ---------------------------------------------------------------- CRUD

  public ServerInstanceDto create(ServerInstanceDto dto) {
    validate(dto, true);
    String name = dto.getName();

    if (storage.freeBytes() < options.getStorage().getMinFreeGb() * GIB) {
      throw new ConflictException("Not enough free space on the data volume");
    }

    storage.createServerDir(name);
    kubernetes.createService(namespace(), factory.service(dto)); // 409 if the server exists
    try {
      return toDto(kubernetes.createStatefulSet(namespace(), factory.statefulSet(dto)));
    } catch (RuntimeException e) {
      try {
        kubernetes.deleteService(namespace(), name);
      } catch (RuntimeException cleanup) {
        log.warn("Could not roll back the service of server {}", name, cleanup);
      }
      throw e;
    }
  }

  public ServerInstanceDto read(String name) {
    return toDto(statefulSet(name));
  }

  public List<ServerInstanceDto> list() {
    return kubernetes.listStatefulSets(namespace(), ServerManifestFactory.SELECTOR).stream()
        .map(sts -> {
          Optional<ServerInstanceDto> dto = tryToDto(sts);
          if (dto.isEmpty()) {
            log.warn("Ignoring StatefulSet {}: no valid configuration annotation (created by an "
                + "older version? recreate the server)", sts.getMetadata().getName());
          }
          return dto;
        }) //
        .flatMap(Optional::stream) //
        .sorted(Comparator.comparing(ServerInstanceDto::getName)) //
        .toList();
  }

  public ServerInstanceDto update(String name, ServerInstanceDto dto) {
    dto.setName(name);
    validate(dto, false);
    V1StatefulSet sts = statefulSet(name);
    factory.apply(sts, dto);
    return toDto(kubernetes.replaceStatefulSet(namespace(), name, sts));
  }

  /**
   * Removes the server. With {@code deleteData} its folder is moved to the trash (purged after the
   * retention period); otherwise the data stays in place and a new server with the same name
   * adopts it.
   */
  public void delete(String name, boolean deleteData) {
    ServerNames.requireValid(name);
    kubernetes.deleteStatefulSet(namespace(), name);
    try {
      kubernetes.deleteService(namespace(), name);
    } catch (ResourceNotFoundException e) {
      // already gone
    }
    if (deleteData) {
      storage.moveToTrash(name);
    }
  }

  // ---------------------------------------------------------------- lifecycle

  public void start(String name) {
    kubernetes.scaleStatefulSet(namespace(), ServerNames.requireValid(name), 1);
  }

  /** Graceful: the server gets time to save the world. */
  public void stop(String name) {
    kubernetes.scaleStatefulSet(namespace(), ServerNames.requireValid(name), 0);
  }

  /** Immediate kill. */
  public void terminate(String name) {
    stop(name);
    try {
      kubernetes.deletePodNow(namespace(), ServerManifestFactory.podName(name));
    } catch (ResourceNotFoundException e) {
      // no pod: already stopped
    }
  }

  // ---------------------------------------------------------------- internals

  private String namespace() {
    return options.getNamespace();
  }

  private V1StatefulSet statefulSet(String name) {
    return kubernetes.getStatefulSet(namespace(), ServerNames.requireValid(name));
  }

  private ServerInstanceDto toDto(V1StatefulSet sts) {
    return tryToDto(sts).orElseThrow(() -> new ConflictException("Server "
        + sts.getMetadata().getName() + " has no valid configuration: recreate it"));
  }

  private Optional<ServerInstanceDto> tryToDto(V1StatefulSet sts) {
    return factory.readConfig(sts).map(config -> {
      ServerInstanceDto dto = config.toDto(sts.getMetadata().getName());
      dto.setState(ServerStates.of(sts));
      return dto;
    });
  }

  private void validate(ServerInstanceDto dto, boolean creating) {
    if (dto == null) {
      throw new InvalidRequestException("Request body is required");
    }
    if (creating) {
      ServerNames.requireNew(dto.getName());
    } else {
      ServerNames.requireValid(dto.getName());
    }
    if (dto.getType() == null) {
      throw new InvalidRequestException("type is required");
    }
    if (dto.getCpu() < 100 || dto.getCpu() > 64_000) {
      throw new InvalidRequestException("cpu must be between 100 and 64000 (milli cpu)");
    }
    if (dto.getMemory() < 512 || dto.getMemory() > 262_144) {
      throw new InvalidRequestException("memory must be between 512 and 262144 (MB)");
    }
    switch (dto.getType()) {
      case MODRINTH -> {
        if (isBlank(dto.getModrinthProjectId())) {
          throw new InvalidRequestException("modrinthProjectId is required for MODRINTH servers");
        }
      }
      case CURSEFORGE -> {
        if (isBlank(dto.getCurseforgePageUrl())) {
          throw new InvalidRequestException("curseforgePageUrl is required for CURSEFORGE servers");
        }
        if (isBlank(options.getCurseForgeApiKey())) {
          throw new ConflictException("The CurseForge API key is not configured on the server");
        }
      }
      default -> {
      }
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
