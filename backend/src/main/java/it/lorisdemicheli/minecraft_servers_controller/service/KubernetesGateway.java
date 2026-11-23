package it.lorisdemicheli.minecraft_servers_controller.service;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import io.kubernetes.client.Exec;
import io.kubernetes.client.custom.PodMetrics;
import io.kubernetes.client.custom.PodMetricsList;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Scale;
import io.kubernetes.client.openapi.models.V1ScaleSpec;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.kubernetes.client.openapi.models.V1Status;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import io.micrometer.core.instrument.MeterRegistry;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;
import it.lorisdemicheli.minecraft_servers_controller.exception.KubernetesUnavailableException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceAlreadyExistsException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceNotFoundException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ServerException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * The only class that talks to the Kubernetes API. Plain blocking calls (cheap on virtual
 * threads); Kubernetes errors are translated to the application's exceptions.
 *
 * <p>Calls that are safe to repeat (reads, deletes, scale) are retried on transient errors, see
 * {@link KubernetesRetry}. Creates and replaces are not: their callers check the actual state.
 * Deletes are idempotent: they return {@code false} when the object was already gone.
 */
@Slf4j
@Component
public class KubernetesGateway {

  private static final int MAX_CONFLICT_RETRIES = 3;

  private final AppsV1Api appsApi;
  private final CoreV1Api coreApi;
  private final Exec exec;
  private final ApiClient apiClient;
  private final MeterRegistry meters;
  private final KubernetesRetry retry;
  private final Duration execTimeout;
  private final int execMaxOutputBytes;

  private final ExecutorService streamReaders = Executors.newVirtualThreadPerTaskExecutor();

  @Autowired
  public KubernetesGateway(AppsV1Api appsApi, CoreV1Api coreApi, Exec exec, ApiClient apiClient,
      MinecraftServerOptions options, MeterRegistry meters) {
    this(appsApi, coreApi, exec, apiClient, options, meters,
        new KubernetesRetry(options.getKubernetes().getRetryMaxAttempts(),
            options.getKubernetes().getRetryInitialBackoff(),
            options.getKubernetes().getRetryMaxBackoff()));
  }

  KubernetesGateway(AppsV1Api appsApi, CoreV1Api coreApi, Exec exec, ApiClient apiClient,
      MinecraftServerOptions options, MeterRegistry meters, KubernetesRetry retry) {
    this.appsApi = appsApi;
    this.coreApi = coreApi;
    this.exec = exec;
    this.apiClient = apiClient;
    this.meters = meters;
    this.retry = retry;
    this.execTimeout = options.getKubernetes().getExecTimeout();
    this.execMaxOutputBytes = options.getKubernetes().getExecMaxOutputBytes();
  }

  @PreDestroy
  void close() {
    streamReaders.shutdownNow();
  }

  // ---------------------------------------------------------------- StatefulSets

  public V1StatefulSet getStatefulSet(String namespace, String name) {
    return idempotent("Server " + name,
        () -> appsApi.readNamespacedStatefulSet(name, namespace).execute());
  }

  public Optional<V1StatefulSet> findStatefulSet(String namespace, String name) {
    return absentIfNotFound(() -> getStatefulSet(namespace, name));
  }

  public List<V1StatefulSet> listStatefulSets(String namespace, String labelSelector) {
    return idempotent("Servers",
        () -> appsApi.listNamespacedStatefulSet(namespace).labelSelector(labelSelector).execute())
            .getItems();
  }

  /** Not retried: on a transient error the caller must check whether it was created anyway. */
  public V1StatefulSet createStatefulSet(String namespace, V1StatefulSet statefulSet) {
    String name = statefulSet.getMetadata().getName();
    return once("Server " + name,
        () -> appsApi.createNamespacedStatefulSet(namespace, statefulSet).execute());
  }

  /**
   * Optimistic update (the object carries its resourceVersion): a concurrent change gives a
   * {@link ConflictException}. Not retried here, the caller re-reads and re-applies.
   */
  public V1StatefulSet replaceStatefulSet(String namespace, String name,
      V1StatefulSet statefulSet) {
    return once("Server " + name,
        () -> appsApi.replaceNamespacedStatefulSet(name, namespace, statefulSet).execute());
  }

  /** @return false if it did not exist */
  public boolean deleteStatefulSet(String namespace, String name) {
    return deleted(() -> idempotent("Server " + name,
        () -> appsApi.deleteNamespacedStatefulSet(name, namespace).execute()));
  }

  /** Start (1) or stop (0) a server, through the scale subresource. Safe to repeat. */
  public void scaleStatefulSet(String namespace, String name, int replicas) {
    for (int attempt = 1;; attempt++) {
      try {
        idempotent("Server " + name, () -> {
          V1Scale scale = appsApi.readNamespacedStatefulSetScale(name, namespace).execute();
          if (scale.getSpec() == null) {
            scale.setSpec(new V1ScaleSpec());
          }
          scale.getSpec().setReplicas(replicas);
          return appsApi.replaceNamespacedStatefulSetScale(name, namespace, scale).execute();
        });
        return;
      } catch (ConflictException e) {
        if (attempt >= MAX_CONFLICT_RETRIES) {
          throw e;
        }
        log.debug("Scale of {} changed concurrently, re-reading", name);
      }
    }
  }

  // ---------------------------------------------------------------- Services and Pods

  /** Not retried, see {@link #createStatefulSet}. */
  public V1Service createService(String namespace, V1Service service) {
    String name = service.getMetadata().getName();
    return once("Service " + name, () -> coreApi.createNamespacedService(namespace, service).execute());
  }

  public Optional<V1Service> findService(String namespace, String name) {
    return absentIfNotFound(() -> idempotent("Service " + name,
        () -> coreApi.readNamespacedService(name, namespace).execute()));
  }

  public List<V1Service> listServices(String namespace, String labelSelector) {
    return idempotent("Services",
        () -> coreApi.listNamespacedService(namespace).labelSelector(labelSelector).execute())
            .getItems();
  }

  /** @return false if it did not exist */
  public boolean deleteService(String namespace, String name) {
    return deleted(() -> idempotent("Service " + name,
        () -> coreApi.deleteNamespacedService(name, namespace).execute()));
  }

  public boolean podExists(String namespace, String pod) {
    return absentIfNotFound(() -> idempotent("Pod " + pod,
        () -> coreApi.readNamespacedPod(pod, namespace).execute())).isPresent();
  }

  /** Kills the pod without waiting for the graceful shutdown. @return false if there was none */
  public boolean deletePodNow(String namespace, String pod) {
    return deleted(() -> idempotent("Pod " + pod,
        () -> coreApi.deleteNamespacedPod(pod, namespace).gracePeriodSeconds(0).execute()));
  }

  /** Best effort: empty when metrics-server is missing or has no data yet. */
  public Optional<PodMetrics> podMetrics(String namespace, String pod) {
    try {
      GenericKubernetesApi<PodMetrics, PodMetricsList> metricsClient = new GenericKubernetesApi<>(
          PodMetrics.class, PodMetricsList.class, "metrics.k8s.io", "v1beta1", "pods", apiClient);
      return Optional.ofNullable(metricsClient.get(namespace, pod).throwsApiException().getObject());
    } catch (Exception e) {
      log.trace("No metrics for pod {}", pod, e);
      return Optional.empty();
    }
  }

  // ---------------------------------------------------------------- exec

  /**
   * Runs a command (no shell involved) inside a pod and waits for it, within one overall deadline
   * shared by reading stdout, reading stderr and waiting for the exit code. At most
   * {@code execMaxOutputBytes} of each stream are kept in memory; the rest is drained and dropped.
   * On timeout or interruption the connection is closed and the readers cancelled. Not retried.
   */
  public ExecResult exec(String namespace, String pod, String container, String... command) {
    long deadline = System.nanoTime() + execTimeout.toNanos();
    Process process;
    try {
      process = exec.exec(namespace, pod, command, container, false, false);
    } catch (ApiException e) {
      throw translate("Pod " + pod, e);
    } catch (IOException e) {
      count("io");
      throw new KubernetesUnavailableException("Cannot run a command in pod " + pod, e);
    }

    Future<BoundedOutput> out = streamReaders
        .submit(() -> BoundedOutput.read(process.getInputStream(), execMaxOutputBytes));
    Future<BoundedOutput> err = streamReaders
        .submit(() -> BoundedOutput.read(process.getErrorStream(), execMaxOutputBytes));
    try {
      BoundedOutput stdout = out.get(remaining(deadline), TimeUnit.NANOSECONDS);
      BoundedOutput stderr = err.get(remaining(deadline), TimeUnit.NANOSECONDS);
      if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) {
        throw new TimeoutException();
      }
      if (stdout.truncated() || stderr.truncated()) {
        log.warn("Output of a command in pod {} exceeded {} bytes and was truncated", pod,
            execMaxOutputBytes);
      }
      return new ExecResult(process.exitValue(), stdout.text(), stderr.text(),
          stdout.truncated() || stderr.truncated());
    } catch (TimeoutException e) {
      count("exec-timeout");
      log.warn("Command in pod {} did not finish within {}s", pod, execTimeout.toSeconds());
      throw new ServerException(
          "Command timed out in pod " + pod + " after " + execTimeout.toSeconds() + "s");
    } catch (ExecutionException e) {
      throw new ServerException("Cannot read the output of a command in pod " + pod, e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServerException("Interrupted while running a command in pod " + pod, e);
    } finally {
      out.cancel(true);
      err.cancel(true);
      closeQuietly(process.getOutputStream());
      closeQuietly(process.getInputStream());
      closeQuietly(process.getErrorStream());
      process.destroy(); // closes the websocket
    }
  }

  private static long remaining(long deadline) {
    return Math.max(0, deadline - System.nanoTime());
  }

  private static void closeQuietly(Closeable c) {
    try {
      if (c != null) {
        c.close();
      }
    } catch (IOException | RuntimeException ignored) {
      // closing is best effort
    }
  }

  /** At most {@code max} bytes of a stream; the rest is consumed (so the writer never blocks). */
  record BoundedOutput(String text, boolean truncated) {

    static BoundedOutput read(InputStream in, int max) throws IOException {
      try (in) {
        ByteArrayOutputStream kept = new ByteArrayOutputStream(Math.min(max, 8192));
        byte[] buf = new byte[8192];
        boolean truncated = false;
        int n;
        while ((n = in.read(buf)) != -1) {
          int room = max - kept.size();
          if (room > 0) {
            kept.write(buf, 0, Math.min(room, n));
          }
          truncated |= n > room;
        }
        return new BoundedOutput(kept.toString(StandardCharsets.UTF_8), truncated);
      }
    }
  }

  // ---------------------------------------------------------------- errors

  @FunctionalInterface
  private interface Call<T> {
    T run();
  }

  private <T> T idempotent(String what, KubernetesRetry.ApiCall<T> call) {
    try {
      return retry.run(what, call);
    } catch (ApiException e) {
      throw translate(what, e);
    }
  }

  private <T> T once(String what, KubernetesRetry.ApiCall<T> call) {
    try {
      return call.run();
    } catch (ApiException e) {
      throw translate(what, e);
    }
  }

  private static <T> Optional<T> absentIfNotFound(Call<T> call) {
    try {
      return Optional.ofNullable(call.run());
    } catch (ResourceNotFoundException e) {
      return Optional.empty();
    }
  }

  private static boolean deleted(Call<?> call) {
    try {
      call.run();
      return true;
    } catch (ResourceNotFoundException e) {
      return false;
    }
  }

  /**
   * Maps a Kubernetes error to the application's exceptions. The messages are safe to return to
   * the client: Kubernetes' own message is only included for validation errors (it describes the
   * manifest), the raw response body is only logged.
   */
  RuntimeException translate(String what, ApiException e) {
    V1Status status = status(e);
    String reason = status == null ? null : status.getReason();
    String message = status == null ? null : status.getMessage();
    count(e.getCode() == 0 ? "io" : Integer.toString(e.getCode()));

    return switch (e.getCode()) {
      case 404 -> new ResourceNotFoundException(what + " not found", e);
      case 409 -> "AlreadyExists".equals(reason)
          ? new ResourceAlreadyExistsException(what + " already exists", e)
          : new ConflictException(what + " was modified concurrently, try again", e);
      case 400, 422 -> {
        log.warn("Kubernetes rejected a request on {}: {}", what, message);
        yield new InvalidRequestException(
            "Kubernetes rejected the request" + (message == null ? "" : ": " + message), e);
      }
      case 401, 403 -> {
        log.error("Kubernetes denied an operation on {} ({}): check the controller's RBAC: {}",
            what, e.getCode(), message);
        yield new ServerException("The controller is not allowed to perform this operation", e);
      }
      default -> {
        if (KubernetesRetry.isTransient(e)) {
          log.warn("Kubernetes API unavailable on {} (code {}): {}", what, e.getCode(),
              e.getCode() == 0 ? e.getMessage() : message);
          yield new KubernetesUnavailableException(
              "The Kubernetes API is not available right now, try again", e);
        }
        log.error("Kubernetes API error on {} (code {}): {}", what, e.getCode(),
            e.getResponseBody());
        yield new ServerException("Kubernetes API error " + e.getCode() + " on " + what, e);
      }
    };
  }

  private V1Status status(ApiException e) {
    String body = e.getResponseBody();
    if (body == null || body.isBlank() || !body.trim().startsWith("{")) {
      return null;
    }
    try {
      return apiClient.getJSON().deserialize(body, V1Status.class);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private void count(String code) {
    meters.counter("minecraft.controller.kubernetes.errors", "code", code).increment();
  }
}
