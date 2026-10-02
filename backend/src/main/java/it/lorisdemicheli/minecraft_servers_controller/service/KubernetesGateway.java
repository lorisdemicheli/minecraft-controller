package it.lorisdemicheli.minecraft_servers_controller.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceAlreadyExistsException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceNotFoundException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ServerException;
import lombok.RequiredArgsConstructor;

/**
 * The only class that talks to the Kubernetes API. Plain blocking calls (the client is blocking
 * anyway); Kubernetes errors are translated to the application's exceptions.
 */
@Component
@RequiredArgsConstructor
public class KubernetesGateway {

  private static final long EXEC_TIMEOUT_SECONDS = 20;

  private final AppsV1Api appsApi;
  private final CoreV1Api coreApi;
  private final Exec exec;
  private final ApiClient apiClient;

  private final ExecutorService streamReaders = Executors.newVirtualThreadPerTaskExecutor();

  // ---------------------------------------------------------------- StatefulSets

  public V1StatefulSet getStatefulSet(String namespace, String name) {
    return call("Server " + name, () -> appsApi.readNamespacedStatefulSet(name, namespace).execute());
  }

  public List<V1StatefulSet> listStatefulSets(String namespace, String labelSelector) {
    return call("Servers",
        () -> appsApi.listNamespacedStatefulSet(namespace).labelSelector(labelSelector).execute())
            .getItems();
  }

  public V1StatefulSet createStatefulSet(String namespace, V1StatefulSet statefulSet) {
    return call("Server", () -> appsApi.createNamespacedStatefulSet(namespace, statefulSet).execute());
  }

  public V1StatefulSet replaceStatefulSet(String namespace, String name,
      V1StatefulSet statefulSet) {
    return call("Server " + name,
        () -> appsApi.replaceNamespacedStatefulSet(name, namespace, statefulSet).execute());
  }

  public void deleteStatefulSet(String namespace, String name) {
    call("Server " + name, () -> appsApi.deleteNamespacedStatefulSet(name, namespace).execute());
  }

  /** Start (1) or stop (0) a server, through the scale subresource. */
  public void scaleStatefulSet(String namespace, String name, int replicas) {
    call("Server " + name, () -> {
      V1Scale scale = appsApi.readNamespacedStatefulSetScale(name, namespace).execute();
      if (scale.getSpec() == null) {
        scale.setSpec(new V1ScaleSpec());
      }
      scale.getSpec().setReplicas(replicas);
      return appsApi.replaceNamespacedStatefulSetScale(name, namespace, scale).execute();
    });
  }

  // ---------------------------------------------------------------- Services and Pods

  public V1Service createService(String namespace, V1Service service) {
    return call("Server", () -> coreApi.createNamespacedService(namespace, service).execute());
  }

  public void deleteService(String namespace, String name) {
    call("Service " + name, () -> coreApi.deleteNamespacedService(name, namespace).execute());
  }

  /** Kills the pod without waiting for the graceful shutdown. */
  public void deletePodNow(String namespace, String pod) {
    call("Pod " + pod,
        () -> coreApi.deleteNamespacedPod(pod, namespace).gracePeriodSeconds(0).execute());
  }

  /** Best effort: empty when metrics-server is missing or has no data yet. */
  public Optional<PodMetrics> podMetrics(String namespace, String pod) {
    try {
      GenericKubernetesApi<PodMetrics, PodMetricsList> metricsClient = new GenericKubernetesApi<>(
          PodMetrics.class, PodMetricsList.class, "metrics.k8s.io", "v1beta1", "pods", apiClient);
      return Optional.ofNullable(metricsClient.get(namespace, pod).throwsApiException().getObject());
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  // ---------------------------------------------------------------- exec

  /** Runs a command (no shell involved) inside a pod and waits for it. */
  public ExecResult exec(String namespace, String pod, String container, String... command) {
    Process process = null;
    try {
      process = exec.exec(namespace, pod, command, container, false, false);
      Process p = process;
      CompletableFuture<String> out =
          CompletableFuture.supplyAsync(() -> readAll(p.getInputStream()), streamReaders);
      CompletableFuture<String> err =
          CompletableFuture.supplyAsync(() -> readAll(p.getErrorStream()), streamReaders);
      String stdout = out.get(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      String stderr = err.get(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!p.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new ServerException("Command timed out in pod " + pod);
      }
      return new ExecResult(p.exitValue(), stdout, stderr);
    } catch (ApiException e) {
      throw translate("Pod " + pod, e);
    } catch (IOException | ExecutionException | TimeoutException e) {
      throw new ServerException("Command failed in pod " + pod + ": " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServerException("Interrupted while running a command in pod " + pod, e);
    } finally {
      if (process != null) {
        process.destroy();
      }
    }
  }

  private static String readAll(InputStream in) {
    try (in) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---------------------------------------------------------------- errors

  @FunctionalInterface
  private interface ApiCall<T> {
    T run() throws ApiException;
  }

  private static <T> T call(String what, ApiCall<T> call) {
    try {
      return call.run();
    } catch (ApiException e) {
      throw translate(what, e);
    }
  }

  private static RuntimeException translate(String what, ApiException e) {
    String detail = e.getResponseBody() != null ? e.getResponseBody() : e.getMessage();
    return switch (e.getCode()) {
      case 404 -> new ResourceNotFoundException(what + " not found", e);
      case 409 -> new ResourceAlreadyExistsException(what + " already exists", e);
      case 400, 422 -> new InvalidRequestException("Kubernetes rejected the request: " + detail, e);
      default -> new ServerException("Kubernetes API error " + e.getCode() + ": " + detail, e);
    };
  }
}
