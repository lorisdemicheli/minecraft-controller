package it.lorisdemicheli.minecraft_servers_controller.service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.Exec;
import io.kubernetes.client.PodLogs;
import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1ExecAction;
import io.kubernetes.client.openapi.models.V1LabelSelector;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimSpec;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimVolumeSource;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.openapi.models.V1Probe;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServicePort;
import io.kubernetes.client.openapi.models.V1ServiceSpec;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.kubernetes.client.openapi.models.V1StatefulSetList;
import io.kubernetes.client.openapi.models.V1StatefulSetSpec;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import io.kubernetes.client.openapi.models.V1VolumeResourceRequirements;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.domain.ConfigurableOptions;
import it.lorisdemicheli.minecraft_servers_controller.domain.Server;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInfo;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInfo.Description;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInfo.Players;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInfo.Version;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerState;
import it.lorisdemicheli.minecraft_servers_controller.domain.Type;
import it.lorisdemicheli.minecraft_servers_controller.exception.ApiRuntimeException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConfigurationException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceAlreadyExistsException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceNotFoundException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ServerException;
import jakarta.annotation.Nonnull;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Service
public class KubernetesServerInstanceService {

  @Autowired
  private ObjectMapper objectMapper;
  @Autowired
  private CoreV1Api coreApi;
  @Autowired
  private Exec exec;
  @Autowired
  private AppsV1Api appsApi;
  @Autowired
  private PodLogs podLogs;
  @Autowired
  private CustomObjectsApi customObjectsApi;
  @Autowired
  private MinecraftServerOptions serverOptions;
  
  private final static String LABEL_PREFIX = "it.lorisdemicheli/";

  private final static String LABEL_SERVER_NAME = LABEL_PREFIX + "app";
  private final static String LABEL_SERVER_TYPE = LABEL_PREFIX + "server-type";
  private final static String LABEL_SERVER_CPU = LABEL_PREFIX + "cpu";
  private final static String LABEL_SERVER_MEMORY = LABEL_PREFIX + "memory";

  private final static String LABEL_SERVER_MINECRAFT_EULA = LABEL_PREFIX + "eula";
  private final static String LABEL_SERVER_MINECRAFT_VERSION = LABEL_PREFIX + "version";
  private final static String LABEL_SERVER_MODRINTH_PROJECT_ID = LABEL_PREFIX + "modrinth-project-id";
  private final static String LABEL_SERVER_CURSEFORGE_URL = LABEL_PREFIX + "curseforge-url";

  private final Map<String, Flux<String>> activeStreams = new ConcurrentHashMap<>();

  public Server createServer(@Nonnull String serverName, @Nonnull Type type,
      @Nonnull ConfigurableOptions options) {
    if (serverExist(serverName)) {
      throw new ResourceAlreadyExistsException();
    }
    if (!options.isValid()) {
      throw new ConfigurationException(
          "Invalid configuration, set one of version, modrinthProjectId curseforgePageUrl");
    }

    // 1. Setup Labels
    Map<String, String> labels = new HashMap<>();
    labels.put(LABEL_SERVER_NAME, serverName);
    labels.put(LABEL_SERVER_TYPE, type.toString().toUpperCase());
    labels.put(LABEL_SERVER_CPU, Double.toString(options.getCpu()));
    labels.put(LABEL_SERVER_MEMORY, Double.toString(options.getMemory()));
    labels.put(LABEL_SERVER_MINECRAFT_EULA, Boolean.toString(options.isEula()));
    if (options.getVersion() != null) {
      labels.put(LABEL_SERVER_MINECRAFT_VERSION, options.getVersion());
    }
    if (options.getModrinthProjectId() != null) {
      labels.put(LABEL_SERVER_MODRINTH_PROJECT_ID, options.getModrinthProjectId());
    }
    if (options.getCurseforgePageUrl() != null) {
      labels.put(LABEL_SERVER_CURSEFORGE_URL, options.getCurseforgePageUrl());
    }
    labels.put("managed-by", "minecraft-controller");

    int memMb = (int) Math.floor(options.getMemory() * 1000);
    String javaMemory = memMb + "M";
    String k8sMemory = (int) (memMb * 1.2) + "Mi";

    // 3. Variabili d'Ambiente
    List<V1EnvVar> envs = type.createEnv(options, serverOptions);
    envs.add(new V1EnvVar().name("EULA").value(Boolean.toString(options.isEula())));
    envs.add(new V1EnvVar().name("CREATE_CONSOLE_IN_PIPE").value("TRUE"));
    envs.add(new V1EnvVar().name("MEMORY").value(javaMemory));
    envs.add(new V1EnvVar().name("JVM_OPTS").value("--enable-native-access=ALL-UNNAMED"));

    // 4. Risorse e Probes
    V1ResourceRequirements resources = new V1ResourceRequirements()
        .putLimitsItem("cpu", new Quantity(Double.toString(options.getCpu())))
        .putLimitsItem("memory", new Quantity(k8sMemory))
        .putRequestsItem("cpu", new Quantity(Double.toString(options.getCpu())))
        .putRequestsItem("memory", new Quantity(k8sMemory));

    V1Probe healthProbe = new V1Probe()
        .exec(new V1ExecAction().addCommandItem("mc-health"))
        .initialDelaySeconds(60)
        .periodSeconds(20);

    V1Probe startupProbe = new V1Probe()
        .exec(new V1ExecAction().addCommandItem("mc-health"))
        .initialDelaySeconds(30)
        .periodSeconds(10)
        .failureThreshold(60);

    Map<String, String> annotations = new HashMap<>();
    String externalDomain = String.format("%s.%s", serverName, serverOptions.getBaseDomain());
    annotations.put("mc-router.itzg.me/externalServerName", externalDomain);

    V1Service service = new V1Service() //
        .metadata(new V1ObjectMeta() //
            .name(serverName) //
            .labels(labels) //
            .annotations(annotations)) //
        .spec(new V1ServiceSpec() //
            .selector(labels) //
            .addPortsItem(new V1ServicePort() //
                .protocol("TCP") //
                .port(25565) //
                .targetPort(new IntOrString(25565))));

    // 6. Creazione StatefulSet
    V1StatefulSet statefulSet = new V1StatefulSet()
        .metadata(new V1ObjectMeta().name(serverName).labels(labels))
        .spec(new V1StatefulSetSpec()
            .serviceName(serverName)
            .replicas(0) //
            .selector(new V1LabelSelector().matchLabels(labels))
            .template(new V1PodTemplateSpec()
                .metadata(new V1ObjectMeta().labels(labels))
                .spec(new V1PodSpec()
                    .addContainersItem(new V1Container()
                        .name("minecraft")
                        .image("itzg/minecraft-server")
                        .env(envs)
                        .resources(resources)
                        .livenessProbe(healthProbe)
                        .readinessProbe(healthProbe)
                        .startupProbe(startupProbe)
                        .addVolumeMountsItem(new V1VolumeMount()
                            .name("data")
                            .mountPath("/data"))
                    )
                    .addVolumesItem(new V1Volume()
                        .name("data")
                        .persistentVolumeClaim(new V1PersistentVolumeClaimVolumeSource()
                            .claimName(serverName + "-pvc")))
                )));

    // 7. Creazione PVC
    V1PersistentVolumeClaim pvc = new V1PersistentVolumeClaim()
        .metadata(new V1ObjectMeta().name(serverName + "-pvc").labels(labels))
        .spec(new V1PersistentVolumeClaimSpec()
            .accessModes(Arrays.asList("ReadWriteOnce"))
            .resources(new V1VolumeResourceRequirements()
                .putRequestsItem("storage", new Quantity("10Gi"))));

    try {
      coreApi //
        .createNamespacedPersistentVolumeClaim(serverOptions.getNamespace(), pvc) //
        .execute();
      coreApi //
        .createNamespacedService(serverOptions.getNamespace(), service) //
        .execute(); // <--- AGGIUNTO
      V1StatefulSet createdSts = appsApi //
          .createNamespacedStatefulSet(serverOptions.getNamespace(), statefulSet) //
          .execute();
      
      return transform(createdSts);
    } catch (ApiException e) {
      throw new ApiRuntimeException(e);
    }
}

  public Server getServer(String serverName) {
    try {
      V1StatefulSet statefulSet =
          appsApi.readNamespacedStatefulSet(serverName, serverOptions.getNamespace()) //
              .execute();

      return transform(statefulSet);
    } catch (ApiException e) {
      if (e.getCode() == 404) {
        throw new ResourceNotFoundException();
      }
      throw new ApiRuntimeException(e);
    }
  }

  public List<Server> getServerList() {
    try {
      V1StatefulSetList list = appsApi //
          .listNamespacedStatefulSet(serverOptions.getNamespace()) //
          .labelSelector("managed-by=minecraft-controller") //
          .execute();

      return list.getItems().stream() //
          .map(this::transform) //
          .toList();
    } catch (ApiException e) {
      throw new ApiRuntimeException(e);
    }
  }

  public Server updateServer(Server server) {
    if(!serverExist(server.getName())) {
      throw new ResourceNotFoundException();
    }
    if (!server.isValid()) {
      throw new ConfigurationException(
          "Invalid configuration, set one of version, modrinthProjectId curseforgePageUrl");
    }

    try {
      V1StatefulSet existingSts = appsApi
          .readNamespacedStatefulSet(server.getName(), serverOptions.getNamespace()).execute();

      Map<String, String> labels = new HashMap<>();
      labels.put(LABEL_SERVER_NAME, server.getName());
      labels.put(LABEL_SERVER_TYPE, server.getType().toString().toUpperCase());
      labels.put(LABEL_SERVER_CPU, Double.toString(server.getCpu()));
      labels.put(LABEL_SERVER_MEMORY, Double.toString(server.getMemory()));
      labels.put(LABEL_SERVER_MINECRAFT_EULA, Boolean.toString(server.isEula()));
      if (server.getVersion() != null) {
        labels.put(LABEL_SERVER_MINECRAFT_VERSION, server.getVersion());
      }
      if (server.getModrinthProjectId() != null) {
        labels.put(LABEL_SERVER_MODRINTH_PROJECT_ID, server.getModrinthProjectId());
      }
      if (server.getCurseforgePageUrl() != null) {
        labels.put(LABEL_SERVER_CURSEFORGE_URL, server.getCurseforgePageUrl());
      }
      labels.put("managed-by", "minecraft-controller");

      existingSts.getMetadata().setLabels(labels);
      existingSts.getSpec().getSelector().setMatchLabels(labels);
      existingSts.getSpec().getTemplate().getMetadata().setLabels(labels);

      String memoryLimit = (int) Math.floor(server.getMemory() * 1000) + "Mi";
      List<V1EnvVar> envs = server.getType().createEnv(server, serverOptions);

      envs.add((new V1EnvVar().name("EULA").value(Boolean.toString(server.isEula()))));
      envs.add((new V1EnvVar().name("CREATE_CONSOLE_IN_PIPE").value("TRUE")));
      envs.add(new V1EnvVar().name("MEMORY").value(memoryLimit));

      V1Container container = existingSts.getSpec().getTemplate().getSpec().getContainers().get(0);
      container.setEnv(envs);

      V1ResourceRequirements resources = new V1ResourceRequirements() //
          .putLimitsItem("cpu", new Quantity(Double.toString(server.getCpu()))) //
          .putLimitsItem("memory", new Quantity(memoryLimit));

      container.setResources(resources);

      V1StatefulSet updatedSts = appsApi //
          .replaceNamespacedStatefulSet(server.getName(), serverOptions.getNamespace(), existingSts) //
          .execute();

      return transform(updatedSts);
    } catch (ApiException e) {
      if (e.getCode() == 404) {
        throw new ResourceNotFoundException();
      }
      throw new ApiRuntimeException(e);
    }
  }

  public boolean deleteServer(String serverName) {
    try {
      appsApi //
          .deleteNamespacedStatefulSet(serverName, serverOptions.getNamespace()) //
          .execute();

      coreApi //
          .deleteNamespacedPersistentVolumeClaim(serverName + "-pvc", serverOptions.getNamespace()) //
          .execute();
      
      coreApi //
      .deleteNamespacedService(serverName, serverOptions.getNamespace()) //
      .execute(); 


      customObjectsApi //
          .deleteNamespacedCustomObject( //
              "traefik.io", //
              "v1alpha1", //
              serverOptions.getNamespace(), //
              "ingressroutetcps", //
              serverName //
          ).execute();

      return true;
    } catch (ApiException e) {
      if (e.getCode() == 404) {
        throw new ResourceNotFoundException();
      }
      throw new ApiRuntimeException(e);
    }
  }

  public void startServer(String serverName) {
    try {
      V1StatefulSet statefulSet = appsApi //
          .readNamespacedStatefulSet(serverName, serverOptions.getNamespace()) //
          .execute();

      statefulSet.getSpec().setReplicas(1);

      appsApi //
          .replaceNamespacedStatefulSet( //
              serverName, //
              serverOptions.getNamespace(), //
              statefulSet) //
          .execute();
    } catch (ApiException e) {
      if (e.getCode() == 404) {
        throw new ResourceNotFoundException();
      }
      throw new ApiRuntimeException(e);
    }
  }

  public void stopServer(String serverName) {
    try {
      V1StatefulSet statefulSet = appsApi //
          .readNamespacedStatefulSet(serverName, serverOptions.getNamespace()) //
          .execute();

      statefulSet.getSpec().setReplicas(0);

      appsApi //
          .replaceNamespacedStatefulSet( //
              serverName, //
              serverOptions.getNamespace(), //
              statefulSet) //
          .execute();
    } catch (ApiException e) {
      if (e.getCode() == 404) {
        throw new ResourceNotFoundException();
      }
      throw new ApiRuntimeException(e);
    }
  }

  public void terminateServer(String serverName) {
    try {
      V1StatefulSet sts = appsApi //
          .readNamespacedStatefulSet(serverName, serverOptions.getNamespace()) //
          .execute();

      sts.getSpec().setReplicas(0);

      appsApi //
          .replaceNamespacedStatefulSet(serverName, serverOptions.getNamespace(), sts) //
          .execute();

      coreApi //
          .deleteNamespacedPod(getPodName(serverName), serverOptions.getNamespace()) //
          .gracePeriodSeconds(0) //
          .execute();

    } catch (ApiException e) {
      if (e.getCode() == 404) {
        throw new ResourceNotFoundException();
      }
      if (e.getCode() != 404) {
        throw new ApiRuntimeException(e);
      }
    }
  }

  public ServerInfo getServerInfo(String serverName) {
    ServerInfo info = new ServerInfo();

    try {
      V1StatefulSet sts = appsApi //
          .readNamespacedStatefulSet(serverName, serverOptions.getNamespace()) //
          .execute();
      Integer replicas = sts.getSpec().getReplicas();

      if (replicas == null || replicas == 0) {
        info.setState(ServerState.STOPPED);
        return info;
      }

      V1Pod pod;
      try {
        pod = coreApi //
            .readNamespacedPod(getPodName(serverName), serverOptions.getNamespace()) //
            .execute();
      } catch (ApiException e) {
        info.setState(ServerState.STOPPED);
        return info;
      }

      if (pod.getMetadata().getDeletionTimestamp() != null) {
        info.setState(ServerState.SHUTDOWN);
        return info;
      }

      boolean isReady = pod.getStatus().getConditions() //
          .stream().anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));

      if (!isReady) {
        info.setState(ServerState.STARTING);
        return info;
      }

      info.setState(ServerState.STARTED);
      return fetchLiveMonitorData(info, serverName);

    } catch (ApiException e) {
      if (e.getCode() == 404)
        throw new ResourceNotFoundException();
      throw new ApiRuntimeException(e);
    }
  }

  private ServerInfo fetchLiveMonitorData(ServerInfo info, String serverName) {
    try {
      String[] command =
          {"mc-monitor", "status", "--host", "localhost", "--port", "25565", "--json"};

      Process proc = exec.exec(serverOptions.getNamespace(), getPodName(serverName), command,
          "minecraft", false, false);

      StringBuilder output = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line);
        }
      }

      proc.waitFor(5, TimeUnit.SECONDS);

      if (proc.exitValue() == 0) {
        JsonNode root = objectMapper.readTree(output.toString());

        JsonNode serverInfo = root.get("server_info");

        if (serverInfo != null && !serverInfo.isNull()) {
          info.setVersion(objectMapper.treeToValue(serverInfo.get("version"), Version.class));
          info.setPlayers(objectMapper.treeToValue(serverInfo.get("players"), Players.class));
          info.setDescription(
              objectMapper.treeToValue(serverInfo.get("description"), Description.class));
        }
      }
      proc.destroy();
    } catch (Exception e) {
      throw new ServerException(e);
    }
    return info;
  }

  public Flux<String> logs(String serverName) {
    return activeStreams.computeIfAbsent(serverName, k -> Flux.<String>create(sink -> {
      try (InputStream is =
          podLogs.streamNamespacedPodLog(serverOptions.getNamespace(), getPodName(serverName), null)) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        String line;
        while (!sink.isCancelled() && (line = reader.readLine()) != null) {
          sink.next(line);
        }
        sink.complete();
      } catch (Exception e) {
        sink.error(e);
      } finally {
        activeStreams.remove(serverName);
      }
    }).subscribeOn(Schedulers.boundedElastic()) //
        .publish() //
        .refCount() //
        .cache(10) //
    );
  }

  public void sendCommand(String serverName, String command) {
    if(!serverExist(serverName)) {
      throw new ResourceNotFoundException();
    }
    try {
      String cmd[] = {"gosu","minecraft","mc-send-to-console", command};
      // TODO cambiare container
      Process proc = exec.exec(serverOptions.getNamespace(), getPodName(serverName), cmd,
          "minecraft", false, true);
      proc.waitFor();
    } catch (Exception e) {
      throw new RuntimeException("Err exec", e);
    }
  }

  private String getPodName(String serverName) {
    return serverName + "-0";
  }

  private Server transform(V1StatefulSet statefulSet) {
    V1ObjectMeta metadata = statefulSet.getMetadata();
    Map<String, String> labels = metadata.getLabels();

    Server server = new Server();
    server.setName(labels.get(LABEL_SERVER_NAME));
    server.setType(Type.valueOf(labels.get(LABEL_SERVER_TYPE)));
    server.setCpu(Double.parseDouble((labels.get(LABEL_SERVER_CPU))));
    server.setMemory(Double.parseDouble((labels.get(LABEL_SERVER_MEMORY))));
    server.setVersion(labels.get(LABEL_SERVER_MINECRAFT_VERSION));
    server.setModrinthProjectId(labels.get(LABEL_SERVER_MODRINTH_PROJECT_ID));
    server.setCurseforgePageUrl(labels.get(LABEL_SERVER_CURSEFORGE_URL));
    server.setEula(Boolean.valueOf(labels.get(LABEL_SERVER_MINECRAFT_EULA)));

    return server;
  }

  private boolean serverExist(String serverName) {
    try {
      appsApi.readNamespacedStatefulSet(serverName, serverOptions.getNamespace()) //
          .execute();
      return true;
    } catch (ApiException e) {
      if (e.getCode() == 404) {
        return false;
      }
      throw new ServerException(e);
    }
  }

}
