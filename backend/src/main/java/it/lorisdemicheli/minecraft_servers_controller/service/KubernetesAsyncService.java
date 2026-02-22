package it.lorisdemicheli.minecraft_servers_controller.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;
import org.springframework.stereotype.Service;
import io.kubernetes.client.Exec;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.kubernetes.client.openapi.models.V1Status;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceAlreadyExistsException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class KubernetesAsyncService {

  private final Exec exec;
  private final AppsV1Api appsApi;
  private final CoreV1Api coreApi;
  
  
//  public Mono<V1Pod> getPodMetrics(String namespace, String pod) {
//    coreApi.getpo
//  }
  

  public Mono<V1Pod> getNamespacedPod(String namespace, String pod) {
    return Mono.fromCallable(() -> {
      return coreApi //
          .readNamespacedPod(pod, namespace) //
          .execute();
    }).onErrorMap(this::errorMapper);
  }

  public Mono<V1StatefulSet> getNamespacedStatefulSet(String namespace, String statefulSet) {
    return Mono.fromCallable(() -> {
      return appsApi //
          .readNamespacedStatefulSet(statefulSet, namespace) //
          .execute();
    }).onErrorMap(this::errorMapper);
  }

  public Mono<List<V1StatefulSet>> getNamespacedStatefulSets(String namespace, String label) {
    return Mono.fromCallable(() -> {
      return appsApi //
          .listNamespacedStatefulSet(namespace) //
          .labelSelector(label) //
          .execute() //
          .getItems();
    }).onErrorMap(this::errorMapper);
  }

  public Mono<V1Pod> createNamespacedPod(String namespace, V1Pod pod) {
    return Mono.fromCallable(() -> {
      return coreApi //
          .createNamespacedPod(namespace, pod) //
          .execute();
    }).onErrorMap(this::errorMapper);
  }

  public Mono<V1PersistentVolumeClaim> createNamespacedPersistentVolumeClaim(String namespace,
      V1PersistentVolumeClaim persistentVolumeClaim) {
    return Mono.fromCallable(() -> {
      return coreApi //
          .createNamespacedPersistentVolumeClaim(namespace, persistentVolumeClaim) //
          .execute();
    }).onErrorMap(this::errorMapper);
  }

  public Mono<V1Service> createNamespacedService(String namespace, V1Service service) {
    return Mono.fromCallable(() -> {
      return coreApi //
          .createNamespacedService(namespace, service) //
          .execute();
    }).onErrorMap(this::errorMapper);
  }

  public Mono<V1StatefulSet> createNamespacedStatefulSet(String namespace,
      V1StatefulSet statefulSet) {
    return Mono.fromCallable(() -> {
      return appsApi //
          .createNamespacedStatefulSet(namespace, statefulSet) //
          .execute();
    }).onErrorMap(this::errorMapper);
  }

  public Mono<V1StatefulSet> replaceNamespacedStatefulSet(String namespace, String statefulsetName,
      V1StatefulSet statefulSet) {
    return Mono.fromCallable(() -> {
      return appsApi //
          .replaceNamespacedStatefulSet(statefulsetName, namespace, statefulSet) //
          .execute();
    }).onErrorMap(this::errorMapper);
  }

  public Mono<V1Pod> deleteNamespacedPod(String namespace, String pod) {
    return Mono.fromCallable(() -> {
      return coreApi //
          .deleteNamespacedPod(pod, namespace) //
          .execute();
    }).onErrorMap(this::errorMapper);
  }

  public Mono<V1PersistentVolumeClaim> deleteNamespacedPersistentVolumeClaim(String namespace,
      String persistentVolumeClaim) {
    return Mono.fromCallable(() -> {
      return coreApi //
          .deleteNamespacedPersistentVolumeClaim(persistentVolumeClaim, namespace) //
          .execute();
    }).onErrorMap(this::errorMapper);
  }

  public Mono<V1Service> deleteNamespacedService(String namespace, String service) {
    return Mono.fromCallable(() -> {
      return coreApi //
          .deleteNamespacedService(service, namespace) //
          .execute();
    }).onErrorMap(this::errorMapper);
  }

  public Mono<V1Status> deleteNamespacedStatefulSet(String namespace, String statefulSet) {
    return Mono.fromCallable(() -> {
      return appsApi //
          .deleteNamespacedStatefulSet(statefulSet, namespace) //
          .execute();
    }).onErrorMap(this::errorMapper);
  }

  public Mono<V1Pod> deleteNamespacedPodInstantly(String namespace, String pod) {
    return Mono.fromCallable(() -> {
      return coreApi //
          .deleteNamespacedPod(pod, namespace) //
          .gracePeriodSeconds(0) //
          .execute();
    }).onErrorMap(this::errorMapper);
  }

  public Mono<String> execCommand(String namespace, String pod, String container, String[] cmd) {
    return Mono.fromCallable(() -> {
      Process proc = exec.exec(namespace, pod, cmd, false, false);
      String output = new String(proc.getInputStream().readAllBytes());
      proc.waitFor();
      proc.destroy();
      return output;
    }).onErrorMap(this::errorMapper);
  }

  public Flux<String> execStream(String namespace, String pod, String container, String[] cmd) {
    return Flux.<String>create(sink -> {
        Process process = null;
        try {
            process = exec.exec(namespace, pod, cmd, container, false, false);
            
            final Process finalProcess = process;
            sink.onCancel(finalProcess::destroy);
            sink.onDispose(finalProcess::destroy);

            LineIterator lineIterator =
                IOUtils.lineIterator(process.getInputStream(), StandardCharsets.UTF_8);
            while (!sink.isCancelled() && lineIterator.hasNext()) {
                sink.next(lineIterator.next());
            }
            sink.complete();
        } catch (Exception e) {
            if (!sink.isCancelled()) {
                sink.error(e);
            }
        } finally {
            if (process != null)
                process.destroy();
        }
    }).onErrorMap(this::errorMapper);
}

  private Throwable errorMapper(Throwable throwable) {
    if (throwable instanceof ApiException apiE) {
      if (apiE.getCode() == 404) {
        return new ResourceNotFoundException();
      }
      if (apiE.getCode() == 409) {
        return new ResourceAlreadyExistsException();
      }
    }

    return null;
  }


}
