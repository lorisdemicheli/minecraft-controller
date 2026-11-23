package it.lorisdemicheli.minecraft_servers_controller.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerLabel;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerConfig;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerState;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.KubernetesUnavailableException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceAlreadyExistsException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceNotFoundException;
import it.lorisdemicheli.minecraft_servers_controller.storage.ServerStorage;
import lombok.extern.slf4j.Slf4j;

/**
 * Coordinates the operations that touch both Kubernetes and the data volume. They cannot share a
 * transaction, so every step is idempotent and every failure is either compensated or left in a
 * state that the next attempt (or {@link #reconcile()}) completes:
 *
 * <ul>
 * <li><b>create</b>: directory, Service, StatefulSet. If the StatefulSet cannot be created, the
 * Service is removed and the directory too, but only if this call created it and it is still
 * empty. A Service left by an interrupted create is adopted by the next create of the same name.
 * When Kubernetes does not answer, the actual state is read back before deciding.</li>
 * <li><b>delete</b>: StatefulSet and Service (both "already gone" tolerant), then the data goes to
 * the trash only once no pod can still write to it. Until then a marker on the volume remembers
 * the pending move, so it survives a controller restart.</li>
 * <li><b>reconcile</b> (scheduled): completes pending deletions and removes Services that have no
 * StatefulSet any more (after a grace period, so that a create in progress is never touched).</li>
 * </ul>
 *
 * Operations on the same server are serialized; they only coordinate within this process, which is
 * why the chart runs a single controller replica.
 */
@Slf4j
@Component
public class ServerLifecycleManager {

  /** A force-deleted pod disappears from the API before its container is actually gone. */
  static final Duration POD_SHUTDOWN_SAFETY_DELAY = Duration.ofSeconds(30);
  private static final int MAX_CONFLICT_RETRIES = 3;
  private static final long GIB = 1024L * 1024 * 1024;

  public enum DeleteOutcome {
    /** Kubernetes objects removed; data moved to the trash (or there was none). */
    DELETED,
    /** Kubernetes objects removed; data left in place on purpose. */
    DATA_KEPT,
    /** Kubernetes objects removed; data will move to the trash as soon as the pod has stopped. */
    DATA_PENDING
  }

  private final KubernetesGateway kubernetes;
  private final ServerManifestFactory factory;
  private final ServerStorage storage;
  private final MinecraftServerOptions options;
  private final MeterRegistry meters;
  private final Clock clock;
  private final NamedLocks locks = new NamedLocks();
  private final Map<ServerState, AtomicInteger> serversByState = new EnumMap<>(ServerState.class);

  public ServerLifecycleManager(KubernetesGateway kubernetes, ServerManifestFactory factory,
      ServerStorage storage, MinecraftServerOptions options, MeterRegistry meters, Clock clock) {
    this.kubernetes = kubernetes;
    this.factory = factory;
    this.storage = storage;
    this.options = options;
    this.meters = meters;
    this.clock = clock;
    for (ServerState state : ServerState.values()) {
      AtomicInteger count = new AtomicInteger();
      serversByState.put(state, count);
      Gauge.builder("minecraft.controller.servers", count, AtomicInteger::get)
          .description("Managed servers by state (refreshed by the reconciler)")
          .tag("state", state.name()).register(meters);
    }
    Gauge.builder("minecraft.controller.pending.deletions", storage,
        s -> s.pendingDeletions().size()).register(meters);
  }

  // ---------------------------------------------------------------- create

  /** Creates a server from an already validated configuration. */
  public V1StatefulSet create(ServerInstanceDto dto) {
    String name = dto.getName();
    return locks.with(name, () -> timed("create", () -> {
      if (storage.isPendingDeletion(name)) {
        throw new ConflictException(
            "Server " + name + " is still being deleted: try again in a few minutes");
      }
      if (kubernetes.findStatefulSet(namespace(), name).isPresent()) {
        throw new ResourceAlreadyExistsException("Server " + name + " already exists");
      }
      if (storage.freeBytes() < options.getStorage().getMinFreeGb() * GIB) {
        throw new ConflictException("Not enough free space on the data volume");
      }

      boolean dirCreated = storage.createServerDir(name);
      try {
        ensureService(dto);
        V1StatefulSet sts = createStatefulSet(dto);
        log.info("Server created: server={} type={} cpu={}m memory={}MB dataAdopted={}", name,
            dto.getType(), dto.getCpu(), dto.getMemory(), !dirCreated);
        return sts;
      } catch (RuntimeException e) {
        log.warn("Creation of server {} failed, rolling back: {}", name, e.getMessage());
        rollbackCreate(name, dirCreated, e);
        throw e;
      }
    }));
  }

  private void ensureService(ServerInstanceDto dto) {
    String name = dto.getName();
    try {
      kubernetes.createService(namespace(), factory.service(dto));
    } catch (ResourceAlreadyExistsException e) {
      V1Service existing = kubernetes.findService(namespace(), name).orElseThrow(() -> e);
      if (!isManaged(existing.getMetadata())) {
        throw new ConflictException(
            "A Service named " + name + " exists and is not managed by the controller", e);
      }
      // no StatefulSet (checked by the caller): left by an interrupted create
      log.info("Adopting the Service of server {} left by an incomplete operation", name);
    } catch (KubernetesUnavailableException e) {
      // the answer got lost, the Service may exist anyway
      if (kubernetes.findService(namespace(), name).filter(s -> isManaged(s.getMetadata()))
          .isEmpty()) {
        throw e;
      }
    }
  }

  private V1StatefulSet createStatefulSet(ServerInstanceDto dto) {
    try {
      return kubernetes.createStatefulSet(namespace(), factory.statefulSet(dto));
    } catch (KubernetesUnavailableException e) {
      ServerConfig wanted = ServerConfig.from(dto);
      return kubernetes.findStatefulSet(namespace(), dto.getName())
          .filter(sts -> factory.readConfig(sts).map(wanted::equals).orElse(false))
          .orElseThrow(() -> e);
    }
  }

  /**
   * Undoes a failed create without ever touching an existing server: nothing is removed if a
   * StatefulSet with that name exists, the directory only if this call created it and it is empty.
   */
  private void rollbackCreate(String name, boolean dirCreated, RuntimeException cause) {
    try {
      if (kubernetes.findStatefulSet(namespace(), name).isPresent()) {
        return;
      }
      Optional<V1Service> service = kubernetes.findService(namespace(), name);
      if (service.isPresent() && isManaged(service.get().getMetadata())) {
        kubernetes.deleteService(namespace(), name);
      }
    } catch (RuntimeException e) {
      cause.addSuppressed(e);
      meters.counter("minecraft.controller.rollback.failures").increment();
      log.warn("Rollback of server {}: could not remove its Service (the reconciler will retry)",
          name, e);
      return; // do not remove the directory without knowing the StatefulSet state
    }
    if (dirCreated) {
      try {
        if (!storage.deleteServerDirIfEmpty(name)) {
          log.warn("Rollback of server {}: data directory is not empty, kept", name);
        }
      } catch (RuntimeException e) {
        cause.addSuppressed(e);
        meters.counter("minecraft.controller.rollback.failures").increment();
        log.warn("Rollback of server {}: could not remove its data directory", name, e);
      }
    }
  }

  // ---------------------------------------------------------------- update

  /**
   * Applies a new configuration (a running server restarts). Concurrent changes of the
   * StatefulSet are retried on a fresh copy.
   */
  public V1StatefulSet update(ServerInstanceDto dto) {
    String name = dto.getName();
    return locks.with(name, () -> timed("update", () -> {
      for (int attempt = 1;; attempt++) {
        V1StatefulSet sts = kubernetes.getStatefulSet(namespace(), name);
        factory.apply(sts, dto);
        try {
          V1StatefulSet updated = kubernetes.replaceStatefulSet(namespace(), name, sts);
          log.info("Server updated: server={} type={} cpu={}m memory={}MB", name, dto.getType(),
              dto.getCpu(), dto.getMemory());
          return updated;
        } catch (ConflictException e) {
          if (attempt >= MAX_CONFLICT_RETRIES) {
            throw e;
          }
        } catch (KubernetesUnavailableException e) {
          ServerConfig wanted = ServerConfig.from(dto);
          return kubernetes.findStatefulSet(namespace(), name)
              .filter(s -> factory.readConfig(s).map(wanted::equals).orElse(false))
              .orElseThrow(() -> e);
        }
      }
    }));
  }

  // ---------------------------------------------------------------- delete

  /**
   * Removes the server. With {@code deleteData} its folder goes to the trash (purged after the
   * retention period) once its pod has stopped; otherwise the data stays in place and a new server
   * with the same name adopts it. Safe to call again after a partial failure.
   */
  public DeleteOutcome delete(String name, boolean deleteData) {
    return locks.with(name, () -> timed("delete", () -> {
      boolean podWasThere = kubernetes.podExists(namespace(), ServerManifestFactory.podName(name));
      boolean stsDeleted = kubernetes.deleteStatefulSet(namespace(), name);
      boolean serviceDeleted = deleteManagedService(name);
      boolean hasData = storage.exists(name);

      if (!stsDeleted && !serviceDeleted && !(deleteData && hasData)
          && !storage.isPendingDeletion(name)) {
        throw new ResourceNotFoundException("Server " + name + " not found");
      }
      log.info("Server deleted: server={} deleteData={}", name, deleteData);
      if (!deleteData) {
        return DeleteOutcome.DATA_KEPT;
      }
      if (!hasData) {
        storage.clearPendingDeletion(name);
        return DeleteOutcome.DELETED;
      }
      storage.markPendingDeletion(name, clock.instant()); // first, so that a crash from here on is recoverable
      return !podWasThere && finishDeletion(name, true) ? DeleteOutcome.DELETED
          : DeleteOutcome.DATA_PENDING;
    }));
  }

  private boolean deleteManagedService(String name) {
    Optional<V1Service> service = kubernetes.findService(namespace(), name);
    if (service.isEmpty()) {
      return false;
    }
    if (!isManaged(service.get().getMetadata())) {
      log.warn("Not deleting Service {}: it is not managed by the controller", name);
      return false;
    }
    return kubernetes.deleteService(namespace(), name);
  }

  /**
   * Moves the data of a deleted server to the trash if that is safe now. Never for a server that
   * exists again (the marker is dropped, the data belongs to it).
   */
  private boolean finishDeletion(String name, boolean podKnownAbsent) {
    if (kubernetes.findStatefulSet(namespace(), name).isPresent()) {
      log.warn("Server {} exists again: its pending data deletion is cancelled", name);
      storage.clearPendingDeletion(name);
      return false;
    }
    if (kubernetes.podExists(namespace(), ServerManifestFactory.podName(name))) {
      return false; // still shutting down
    }
    if (!podKnownAbsent) {
      Instant since = storage.pendingDeletionSince(name).orElse(Instant.EPOCH);
      if (since.plus(POD_SHUTDOWN_SAFETY_DELAY).isAfter(clock.instant())) {
        return false;
      }
    }
    boolean moved = storage.moveToTrash(name);
    storage.clearPendingDeletion(name);
    log.info("Data of deleted server moved to the trash: server={} moved={}", name, moved);
    return true;
  }

  // ---------------------------------------------------------------- start / stop

  public void start(String name) {
    locks.with(name, () -> timed("start", () -> {
      if (storage.isPendingDeletion(name)) {
        throw new ConflictException("Server " + name + " is being deleted");
      }
      kubernetes.getStatefulSet(namespace(), name); // 404 before touching the volume
      // without its folder kubelet would create it owned by root
      storage.createServerDir(name);
      kubernetes.scaleStatefulSet(namespace(), name, 1);
      log.info("Server started: server={}", name);
      return null;
    }));
  }

  /** Graceful: the server gets time to save the world. */
  public void stop(String name) {
    locks.with(name, () -> timed("stop", () -> {
      kubernetes.scaleStatefulSet(namespace(), name, 0);
      log.info("Server stopped: server={}", name);
      return null;
    }));
  }

  /** Immediate kill. */
  public void terminate(String name) {
    locks.with(name, () -> timed("terminate", () -> {
      kubernetes.scaleStatefulSet(namespace(), name, 0);
      boolean killed = kubernetes.deletePodNow(namespace(), ServerManifestFactory.podName(name));
      log.info("Server terminated: server={} podKilled={}", name, killed);
      return null;
    }));
  }

  // ---------------------------------------------------------------- reconcile

  /** Completes what interrupted operations left behind. Every step is safe to repeat. */
  @Scheduled(initialDelayString = "PT30S",
      fixedDelayString = "#{@minecraftServerOptions.lifecycle.reconcileInterval.toMillis()}")
  public void reconcile() {
    try {
      for (String name : storage.pendingDeletions()) {
        locks.tryWith(name, () -> {
          try {
            finishDeletion(name, false);
          } catch (RuntimeException e) {
            log.warn("Cannot complete the deletion of server {} yet: {}", name, e.getMessage());
          }
        });
      }
      List<V1StatefulSet> statefulSets =
          kubernetes.listStatefulSets(namespace(), ServerManifestFactory.SELECTOR);
      countStates(statefulSets);
      removeOrphanServices(statefulSets);
    } catch (RuntimeException e) {
      log.warn("Reconciliation failed, will retry: {}", e.getMessage());
    }
  }

  private void removeOrphanServices(List<V1StatefulSet> statefulSets) {
    Set<String> servers = statefulSets.stream().map(s -> s.getMetadata().getName())
        .collect(Collectors.toSet());
    Instant cutoff = clock.instant().minus(options.getLifecycle().getOrphanGracePeriod());
    for (V1Service service : kubernetes.listServices(namespace(), ServerManifestFactory.SELECTOR)) {
      String name = service.getMetadata().getName();
      OffsetDateTime created = service.getMetadata().getCreationTimestamp();
      if (servers.contains(name) || created == null || created.toInstant().isAfter(cutoff)) {
        continue;
      }
      locks.tryWith(name, () -> {
        if (kubernetes.findStatefulSet(namespace(), name).isEmpty()) {
          kubernetes.deleteService(namespace(), name);
          log.info("Removed orphan Service of server {} (no StatefulSet)", name);
        }
      });
    }
  }

  private void countStates(List<V1StatefulSet> statefulSets) {
    Map<ServerState, Long> counts = statefulSets.stream()
        .collect(Collectors.groupingBy(ServerStates::of, Collectors.counting()));
    serversByState.forEach((state, gauge) -> gauge.set(counts.getOrDefault(state, 0L).intValue()));
  }

  // ---------------------------------------------------------------- internals

  private static boolean isManaged(V1ObjectMeta meta) {
    Map<String, String> labels = meta == null ? null : meta.getLabels();
    return labels != null && MinecraftServerLabel.MANAGED_BY_VALUE
        .equals(labels.get(MinecraftServerLabel.LABEL_MANAGED_BY));
  }

  private <T> T timed(String operation, Supplier<T> action) {
    Timer.Sample sample = Timer.start(meters);
    String outcome = "error";
    try {
      T result = action.get();
      outcome = "success";
      return result;
    } finally {
      sample.stop(meters.timer("minecraft.controller.operations", "operation", operation,
          "outcome", outcome));
    }
  }

  private String namespace() {
    return options.getNamespace();
  }
}
