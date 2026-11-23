package it.lorisdemicheli.minecraft_servers_controller.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceDto;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;
import it.lorisdemicheli.minecraft_servers_controller.storage.ServerNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Application rules for servers: validation, reads and mapping between the API's DTOs and the
 * Kubernetes objects. The multi-step operations (create, update, delete, start, stop) are run by
 * {@link ServerLifecycleManager}, which handles locking, rollback and recovery.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServerService {

  private final KubernetesGateway kubernetes;
  private final ServerManifestFactory factory;
  private final ServerLifecycleManager lifecycle;
  private final MinecraftServerOptions options;

  // ---------------------------------------------------------------- CRUD

  public ServerInstanceDto create(ServerInstanceDto dto) {
    validate(dto, true);
    return toDto(lifecycle.create(dto));
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
    if (dto == null) {
      throw new InvalidRequestException("Request body is required");
    }
    dto.setName(name);
    validate(dto, false);
    return toDto(lifecycle.update(dto));
  }

  /**
   * Removes the server. With {@code deleteData} its folder is moved to the trash (purged after the
   * retention period) once the pod has stopped; otherwise the data stays in place and a new server
   * with the same name adopts it.
   */
  public ServerLifecycleManager.DeleteOutcome delete(String name, boolean deleteData) {
    return lifecycle.delete(ServerNames.requireValid(name), deleteData);
  }

  // ---------------------------------------------------------------- lifecycle

  public void start(String name) {
    lifecycle.start(ServerNames.requireValid(name));
  }

  /** Graceful: the server gets time to save the world. */
  public void stop(String name) {
    lifecycle.stop(ServerNames.requireValid(name));
  }

  /** Immediate kill. */
  public void terminate(String name) {
    lifecycle.terminate(ServerNames.requireValid(name));
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
    if (creating && !dto.isEula()) {
      throw new InvalidRequestException("The Minecraft EULA must be accepted (eula=true)");
    }
    if (!isBlank(dto.getVersion()) && !VERSION.matcher(dto.getVersion()).matches()) {
      throw new InvalidRequestException(
          "version must be LATEST, SNAPSHOT or a Minecraft version such as 1.21.4");
    }
    switch (dto.getType()) {
      case MODRINTH -> {
        if (isBlank(dto.getModrinthProjectId())
            || !MODRINTH_PROJECT.matcher(dto.getModrinthProjectId()).matches()) {
          throw new InvalidRequestException(
              "modrinthProjectId is required for MODRINTH servers (project id or slug)");
        }
      }
      case CURSEFORGE -> {
        if (isBlank(dto.getCurseforgePageUrl())
            || !CURSEFORGE_URL.matcher(dto.getCurseforgePageUrl()).matches()) {
          throw new InvalidRequestException("curseforgePageUrl is required for CURSEFORGE servers"
              + " (https://www.curseforge.com/minecraft/modpacks/...)");
        }
        if (isBlank(options.getCurseForgeApiKey())) {
          throw new ConflictException("The CurseForge API key is not configured on the server");
        }
      }
      default -> {
      }
    }
  }

  /** Values accepted by the itzg image's VERSION. */
  private static final Pattern VERSION =
      Pattern.compile("(?i)LATEST|SNAPSHOT|[0-9]+(\\.[0-9]+){1,2}(-(pre|rc)[0-9]+)?|[0-9]{2}w[0-9]{2}[a-z]");
  private static final Pattern MODRINTH_PROJECT = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
  private static final Pattern CURSEFORGE_URL =
      Pattern.compile("https://(www\\.)?curseforge\\.com/minecraft/modpacks/[A-Za-z0-9_-]+/?");

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
