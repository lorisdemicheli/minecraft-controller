package it.lorisdemicheli.minecraft_servers_controller.service;

import org.springframework.stereotype.Service;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodBuilder;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class KubernetesExplorerService {

  private static final String EXPLORER_PREFIX = "explorer-";
  private static final String EXPLORER_CONTAINER = "explorer";
  private static final String EXPLORER_IMAGE = "busybox";

  private final KubernetesAsyncService kubernetesService;

  public Mono<V1Pod> createExplorerPod(String namespace, String serverName) {
    V1Pod pod = new V1PodBuilder() //
        .withNewMetadata() //
            .withName(getExplorerPodName(serverName)) //
            .withNamespace(namespace) //
        .endMetadata() //
        .withNewSpec() //
            .addNewContainer() //
                .withName(EXPLORER_CONTAINER) //
                .withImage(EXPLORER_IMAGE) //
                .withImagePullPolicy("IfNotPresent") //
                .withCommand("sh", "-c", "sleep infinity") //
                .addNewVolumeMount() //
                    .withName("data") //
                    .withMountPath("/data") //
                .endVolumeMount() //
            .endContainer() //
            .addNewVolume() //
                .withName("data") //
                .withNewPersistentVolumeClaim() //
                    .withClaimName(serverName) //
                .endPersistentVolumeClaim() //
            .endVolume() //
        .endSpec() //
        .build();
    return kubernetesService.createNamespacedPod(namespace, pod);
  }

  public Mono<V1Pod> deleteExplorerPod(String namespace, String serverName) {
    return kubernetesService.deleteNamespacedPod(namespace, getExplorerPodName(serverName));
  }
  
  public String getExplorerPodName(String serverName) {
    return EXPLORER_PREFIX + serverName ;
  }
  
  public String getContainerName() {
    return EXPLORER_IMAGE;
  }

}