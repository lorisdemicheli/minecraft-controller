package it.lorisdemicheli.minecraft_servers_controller.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ContainerPort;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1ExecAction;
import io.kubernetes.client.openapi.models.V1LabelSelector;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimVolumeSource;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.openapi.models.V1Probe;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServicePort;
import io.kubernetes.client.openapi.models.V1ServiceSpec;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.kubernetes.client.openapi.models.V1StatefulSetSpec;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerLabel;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerConfig;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceDto;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** Builds (and updates) the Kubernetes objects of a server. No cluster access here. */
@Component
@RequiredArgsConstructor
public class ServerManifestFactory {

  public static final String CONTAINER_NAME = "minecraft";
  public static final String SELECTOR = MinecraftServerLabel.LABEL_MANAGED_BY + "="
      + MinecraftServerLabel.MANAGED_BY_VALUE;

  private static final String DATA_VOLUME = "data";
  private static final int MINECRAFT_PORT = 25565;
  private static final int DEFAULT_ID = 1000;

  private final MinecraftServerOptions options;
  private final ObjectMapper objectMapper;

  public static String podName(String server) {
    return server + "-0";
  }

  // ---------------------------------------------------------------- build

  public V1Service service(ServerInstanceDto dto) {
    String name = dto.getName();
    return new V1Service() //
        .metadata(new V1ObjectMeta() //
            .name(name) //
            .labels(selectorLabels(name)) //
            .putAnnotationsItem(MinecraftServerLabel.ANNOTATION_ROUTER,
                name + "." + options.getBaseDomain()))
        .spec(new V1ServiceSpec() //
            .selector(selectorLabels(name)) //
            .addPortsItem(new V1ServicePort() //
                // mc-router only picks a port named "minecraft" or "mc-router"
                .name("minecraft") //
                .protocol("TCP") //
                .port(MINECRAFT_PORT) //
                .targetPort(new IntOrString(MINECRAFT_PORT))));
  }

  public V1StatefulSet statefulSet(ServerInstanceDto dto) {
    String name = dto.getName();

    V1Container container = new V1Container() //
        .name(CONTAINER_NAME) //
        .image(options.getServerImage()) //
        .addPortsItem(new V1ContainerPort().name("minecraft").containerPort(MINECRAFT_PORT)) //
        .env(envs(dto)) //
        .resources(resources(dto)) //
        // A big modpack can need a long time to install and start: nothing else is checked
        // (and nothing is killed) until the startup probe succeeds, up to 30 minutes.
        .startupProbe(mcHealth().initialDelaySeconds(30).periodSeconds(10).failureThreshold(180)) //
        .readinessProbe(mcHealth().periodSeconds(10)) //
        .livenessProbe(mcHealth().periodSeconds(30).failureThreshold(6)) //
        // every server gets its own folder (subPath) of the one shared volume
        .addVolumeMountsItem(
            new V1VolumeMount().name(DATA_VOLUME).mountPath("/data").subPath(name));

    return new V1StatefulSet() //
        .metadata(new V1ObjectMeta() //
            .name(name) //
            .labels(selectorLabels(name)) //
            .putAnnotationsItem(MinecraftServerLabel.ANNOTATION_CONFIG, toJson(dto)))
        .spec(new V1StatefulSetSpec() //
            .serviceName(name) //
            .replicas(0) //
            .selector(new V1LabelSelector().matchLabels(selectorLabels(name)))
            .template(new V1PodTemplateSpec() //
                .metadata(new V1ObjectMeta().labels(selectorLabels(name)))
                .spec(new V1PodSpec() //
                    .terminationGracePeriodSeconds(
                        (long) options.getTerminationGracePeriodSeconds())
                    .addContainersItem(container) //
                    .addVolumesItem(new V1Volume() //
                        .name(DATA_VOLUME) //
                        .persistentVolumeClaim(new V1PersistentVolumeClaimVolumeSource()
                            .claimName(options.getStorage().getClaimName())))))); 
  }

  /** Applies a new configuration to an existing StatefulSet (selector and storage untouched). */
  public void apply(V1StatefulSet sts, ServerInstanceDto dto) {
    sts.getMetadata().putAnnotationsItem(MinecraftServerLabel.ANNOTATION_CONFIG, toJson(dto));
    V1Container container = sts.getSpec().getTemplate().getSpec().getContainers().get(0);
    container.setEnv(envs(dto));
    container.setResources(resources(dto));
  }

  // ---------------------------------------------------------------- read

  public Optional<ServerConfig> readConfig(V1StatefulSet sts) {
    Map<String, String> annotations = sts.getMetadata().getAnnotations();
    String json = annotations == null ? null : annotations.get(MinecraftServerLabel.ANNOTATION_CONFIG);
    if (json == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(objectMapper.readValue(json, ServerConfig.class));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  // ---------------------------------------------------------------- pieces

  private List<V1EnvVar> envs(ServerInstanceDto dto) {
    List<V1EnvVar> envs = new ArrayList<>(dto.getType().getEnvs(dto, options));
    envs.add(env("EULA", Boolean.toString(dto.isEula())));
    envs.add(env("CREATE_CONSOLE_IN_PIPE", "TRUE"));
    envs.add(env("MEMORY", String.format("%dM", dto.getMemory())));
    envs.add(env("JVM_OPTS", "--enable-native-access=ALL-UNNAMED"));
    // files must stay owned by the same user the controller runs as
    if (options.getStorage().getUid() != DEFAULT_ID) {
      envs.add(env("UID", Integer.toString(options.getStorage().getUid())));
    }
    if (options.getStorage().getGid() != DEFAULT_ID) {
      envs.add(env("GID", Integer.toString(options.getStorage().getGid())));
    }
    return envs;
  }

  private static V1ResourceRequirements resources(ServerInstanceDto dto) {
    // the JVM heap is MEMORY; the container also needs room for metaspace, threads, ...
    String memory = String.format("%dM", (int) (dto.getMemory() * 1.15));
    String cpu = String.format("%dm", dto.getCpu());
    return new V1ResourceRequirements() //
        .putLimitsItem("cpu", new Quantity(cpu)) //
        .putLimitsItem("memory", new Quantity(memory));
  }

  private static V1Probe mcHealth() {
    return new V1Probe().exec(new V1ExecAction().addCommandItem("mc-health")).timeoutSeconds(10);
  }

  private static V1EnvVar env(String name, String value) {
    return new V1EnvVar().name(name).value(value);
  }

  private static Map<String, String> selectorLabels(String name) {
    return Map.of(MinecraftServerLabel.LABEL_SERVER_NAME, name, //
        MinecraftServerLabel.LABEL_MANAGED_BY, MinecraftServerLabel.MANAGED_BY_VALUE);
  }

  private String toJson(ServerInstanceDto dto) {
    return objectMapper.writeValueAsString(ServerConfig.from(dto));
  }
}
