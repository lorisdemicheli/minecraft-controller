package it.lorisdemicheli.minecraft_servers_controller.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import io.kubernetes.client.PodLogs;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentList;
import io.kubernetes.client.openapi.models.V1DeploymentSpec;
import io.kubernetes.client.openapi.models.V1LabelSelector;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import it.lorisdemicheli.minecraft_servers_controller.config.KubernetesOptions;
import it.lorisdemicheli.minecraft_servers_controller.domain.Server;
import it.lorisdemicheli.minecraft_servers_controller.domain.Type;

@Service
public class KubernetesService {

  @Lazy
  @Autowired
  private PodLogs podLogs;
  
  @Lazy
  @Autowired
  private AppsV1Api api;

  @Autowired
  private KubernetesOptions options;

  public List<Server> getServerList() {
    try {
      V1DeploymentList list = api.listNamespacedDeployment(options.getNamespace())
          .labelSelector("mc-server-name")
          .execute();

      return list.getItems().stream()
          .map(KubernetesService::transform)
          .toList();
    } catch (ApiException e) {
      throw new RuntimeException("Error searching deployments", e);
    }
  }

  public Server createServer(Server server) {
    if (server.getId() == null) {
      server.setId(java.util.UUID.randomUUID().toString());
    }

    // 2. Costruisci il Deployment Kubernetes
    V1Deployment deployment = new V1Deployment()
        .apiVersion("apps/v1")
        .kind("Deployment")
        .metadata(new V1ObjectMeta()
            .name("mc-server-" + server.getId())
            .putLabelsItem("mc-server-name", server.getName())
            .putLabelsItem("mc-server-id", server.getId())
            .putLabelsItem("mc-server-type", server.getType().name())
            .putAnnotationsItem("mc-server/version", server.getVersion())
            .putAnnotationsItem("mc-server/modpack-name", server.getModpackName())
            .putAnnotationsItem("mc-server/modpack-url", server.getModpackUrl())
            .putAnnotationsItem("mc-server/modpack-id", String.valueOf(server.getModpackId())))
        .spec(new V1DeploymentSpec()
            .selector(new V1LabelSelector().putMatchLabelsItem("mc-server-id", server.getId()))
            .template(new V1PodTemplateSpec()
                .metadata(new V1ObjectMeta().putLabelsItem("mc-server-id", server.getId()))
                .spec(new V1PodSpec()
                    .addContainersItem(new V1Container()
                        .name("minecraft-container")
                        .image("itzg/minecraft-server") // Immagine standard
                        .resources(new V1ResourceRequirements()
                            .putLimitsItem("memory", new io.kubernetes.client.custom.Quantity(server.getMemory())))))));

    try {
      V1Deployment created = api.createNamespacedDeployment(options.getNamespace(), deployment).execute();
      return transform(created);
    } catch (ApiException e) {
      throw new RuntimeException("Error creating deployment: " + e.getResponseBody(), e);
    }
  }

  public Server getById(String id) {
    try {
      // Cerchiamo usando il labelSelector dell'ID per essere precisi
      V1DeploymentList list = api.listNamespacedDeployment(options.getNamespace())
          .labelSelector("mc-server-id=" + id)
          .execute();

      return list.getItems().stream()
          .findFirst()
          .map(KubernetesService::transform)
          .orElseThrow(() -> new RuntimeException("Server not found: " + id));
    } catch (ApiException e) {
      throw new RuntimeException("Error getting server by id", e);
    }
  }

  public void deleteById(String id) {
    try {
      // Troviamo il nome del deployment associato all'ID
      V1DeploymentList list = api.listNamespacedDeployment(options.getNamespace())
          .labelSelector("mc-server-id=" + id)
          .execute();

      for (V1Deployment d : list.getItems()) {
        api.deleteNamespacedDeployment(d.getMetadata().getName(), options.getNamespace()).execute();
      }
    } catch (ApiException e) {
      throw new RuntimeException("Error deleting server", e);
    }
  }

  private static Server transform(V1Deployment deployment) {
    if (deployment == null || deployment.getMetadata() == null) {
      return null;
    }

    var metadata = deployment.getMetadata();
    Map<String, String> labels = metadata.getLabels() != null ? metadata.getLabels() : Collections.emptyMap();
    Map<String, String> annos = metadata.getAnnotations() != null ? metadata.getAnnotations() : Collections.emptyMap();

    Server server = new Server();
    server.setId(labels.get("mc-server-id"));
    server.setName(labels.get("mc-server-name"));

    String typeStr = labels.get("mc-server-type");
    if (typeStr != null) {
      try {
        server.setType(Type.valueOf(typeStr.toUpperCase()));
      } catch (IllegalArgumentException e) { /* ignore */ }
    }

    server.setVersion(annos.get("mc-server/version"));
    server.setModpackName(annos.get("mc-server/modpack-name"));
    server.setModpackUrl(annos.get("mc-server/modpack-url"));

    String modIdStr = annos.get("mc-server/modpack-id");
    if (modIdStr != null && !modIdStr.equals("null")) {
      try {
        server.setModpackId(Integer.parseInt(modIdStr));
      } catch (NumberFormatException e) {
        server.setModpackId(null);
      }
    }

    try {
      var spec = deployment.getSpec();
      if (spec != null && spec.getTemplate().getSpec() != null) {
        var containers = spec.getTemplate().getSpec().getContainers();
        if (containers != null && !containers.isEmpty()) {
          var resources = containers.get(0).getResources();
          if (resources != null && resources.getLimits() != null) {
            Map<String, ?> limits = resources.getLimits();
            Object memValue = limits.get("memory");
            if (memValue != null) {
              server.setMemory(memValue.toString());
            }
          }
        }
      }
    } catch (Exception e) {
      server.setMemory(annos.getOrDefault("mc-server/memory", "N/A"));
    }

    return server;
  }
}