package it.lorisdemicheli.server_controller.service;

import static it.lorisdemicheli.server_controller.config.KubernetesConstant.LABEL_MANAGED_BY;
import static it.lorisdemicheli.server_controller.config.KubernetesConstant.LABEL_SERVER_CPU;
import static it.lorisdemicheli.server_controller.config.KubernetesConstant.LABEL_SERVER_CURSEFORGE_URL;
import static it.lorisdemicheli.server_controller.config.KubernetesConstant.LABEL_SERVER_MEMORY;
import static it.lorisdemicheli.server_controller.config.KubernetesConstant.LABEL_SERVER_MINECRAFT_EULA;
import static it.lorisdemicheli.server_controller.config.KubernetesConstant.LABEL_SERVER_MINECRAFT_VERSION;
import static it.lorisdemicheli.server_controller.config.KubernetesConstant.LABEL_SERVER_MODRINTH_PROJECT_ID;
import static it.lorisdemicheli.server_controller.config.KubernetesConstant.LABEL_SERVER_NAME;
import static it.lorisdemicheli.server_controller.config.KubernetesConstant.LABEL_SERVER_TYPE;
import static it.lorisdemicheli.server_controller.config.KubernetesConstant.VALUE_MANAGED_BY;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import it.lorisdemicheli.server_controller.config.ServerControllerConfig;
import it.lorisdemicheli.server_controller.dto.ServerInstanceDto;
import it.lorisdemicheli.server_controller.dto.ServerInstanceInfoDto;
import it.lorisdemicheli.server_controller.dto.ServerInstanceInfoDto.Description;
import it.lorisdemicheli.server_controller.dto.ServerInstanceInfoDto.Players;
import it.lorisdemicheli.server_controller.dto.ServerInstanceInfoDto.Version;
import it.lorisdemicheli.server_controller.dto.ServerStateDto;
import it.lorisdemicheli.server_controller.dto.ServerTypeDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.RequiredArgsConstructor;

@ApplicationScoped
@RequiredArgsConstructor
public class ServerInstanceService {

  private final KubernetesClient kubernetesClient;
  private final ServerControllerConfig serverControllerConfig;
  private final ObjectMapper objectMapper;

  public ServerInstanceDto save(ServerInstanceDto server) {
    validate(server);

    //@formatter:off
    PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
        .withNewMetadata()
            .withName(String.format("%s-pvc", server.getName()))
            .withNamespace(serverControllerConfig.getNamespace())
            .withLabels(labels(server))
        .endMetadata()
        .withNewSpec()
            .withAccessModes("ReadWriteOnce")
            .withNewResources()
                .addToRequests("storage", new Quantity("10Gi"))
            .endResources()
        .endSpec()
        .build();

    Service service = new ServiceBuilder()
        .withNewMetadata()
            .withName(server.getName())
            .withNamespace(serverControllerConfig.getNamespace())
            .addToAnnotations("mc-router.itzg.me/externalServerName", String.format("%s.%s", server.getName(), serverControllerConfig.getBaseDomain()))
            .withLabels(labels(server))
        .endMetadata()
        .withNewSpec()
            .withType("ClusterIP")
            .addNewPort()
                .withPort(25565)
                .withTargetPort(new IntOrString(25565))
                .withProtocol("TCP")
            .endPort()
            .withSelector(labels(server))
        .endSpec()
        .build();

    // 3. CREAZIONE STATEFULSET
    StatefulSet sts = new StatefulSetBuilder()
        .withNewMetadata()
            .withName(server.getName())
            .withNamespace(serverControllerConfig.getNamespace())
            .withLabels(serverLabels(server))
        .endMetadata()
        .withNewSpec()
            .withReplicas(0)
            .withNewSelector()
                .withMatchLabels(labels(server))
            .endSelector()
            .withNewTemplate()
                .withNewMetadata()
                    .withLabels(labels(server))
                .endMetadata()
                .withNewSpec()
                    .addNewContainer()
                        .withName("minecraft")
                        .withImage("itzg/minecraft-server")
                        .withNewResources()
                            .addToLimits(serverLimits(server))
                        .endResources()
                        // PROBES
                        .withLivenessProbe(createProbe(60, 20, 3, 1))
                        .withReadinessProbe(createProbe(60, 20, 3, 1))
                        .withStartupProbe(createProbe(30, 10, 60, 1))
                        // ENV
                        .addAllToEnv(server.getType().getEnvs(server, serverControllerConfig))
                        .addNewEnv().withName("CREATE_CONSOLE_IN_PIPE").withValue("TRUE").endEnv()
                        .addNewEnv().withName("MEMORY").withValue(String.format("%dM", server.getMemory())).endEnv()
                        .addNewEnv().withName("JVM_OPTS").withValue("--enable-native-access=ALL-UNNAMED").endEnv()
                        // MOUNTS
                        .addNewVolumeMount()
                            .withName("data")
                            .withMountPath("/data")
                        .endVolumeMount()
                    .endContainer()
                    // VOLUMES
                    .addNewVolume()
                        .withName("data")
                        .withNewPersistentVolumeClaim()
                            .withClaimName(String.format("%s-pvc", server.getName()))
                        .endPersistentVolumeClaim()
                    .endVolume()
                .endSpec()
            .endTemplate()
        .endSpec()
        .build();
    //@formatter:on

    kubernetesClient.resource(pvc).serverSideApply();
    kubernetesClient.resource(service).serverSideApply();
    StatefulSet serverInstance = kubernetesClient.resource(sts).serverSideApply();

    return toInstance(serverInstance);
  }

  public ServerInstanceDto update(ServerInstanceDto server) {
    validate(server);

    StatefulSet existingSts = kubernetesClient.apps().statefulSets()
        .inNamespace(serverControllerConfig.getNamespace()).withName(server.getName()).get();
    if (existingSts == null) {
      throw new NotFoundException("Server instance not found");
    }

    //@formatter:off
    StatefulSet stsUpdate = new StatefulSetBuilder()
        .withNewMetadata()
            .withName(server.getName())
            .withNamespace(serverControllerConfig.getNamespace())
            .withLabels(serverLabels(server)) 
        .endMetadata()
        .withNewSpec()
            .withReplicas(existingSts.getSpec().getReplicas())
            .withNewSelector()
                .withMatchLabels(labels(server))
            .endSelector()
            .withNewTemplate()
                .withNewMetadata()
                    .withLabels(labels(server))
                .endMetadata()
                .withNewSpec()
                    .addNewContainer()
                        .withName("minecraft")
                        .withImage("itzg/minecraft-server")
                        .withNewResources()
                            .addToLimits(serverLimits(server)) 
                        .endResources()
                        .withLivenessProbe(createProbe(60, 20, 3, 1))
                        .withReadinessProbe(createProbe(60, 20, 3, 1))
                        .withStartupProbe(createProbe(30, 10, 60, 1))
                        .addAllToEnv(server.getType().getEnvs(server, serverControllerConfig))
                        .addNewEnv().withName("CREATE_CONSOLE_IN_PIPE").withValue("TRUE").endEnv()
                        .addNewEnv().withName("MEMORY").withValue(String.format("%dM", server.getMemory())).endEnv()
                        .addNewEnv().withName("JVM_OPTS").withValue("--enable-native-access=ALL-UNNAMED").endEnv()
                        .addNewEnv().withName("EULA").withValue(String.valueOf(server.isEula())).endEnv()
                        .addNewVolumeMount()
                            .withName("data")
                            .withMountPath("/data")
                        .endVolumeMount()
                    .endContainer()
                    .addNewVolume()
                        .withName("data")
                        .withNewPersistentVolumeClaim()
                            .withClaimName(String.format("%s-pvc", server.getName()))
                        .endPersistentVolumeClaim()
                    .endVolume()
                .endSpec()
            .endTemplate()
        .endSpec()
        .build();
      //@formatter:on

    StatefulSet updatedSts = kubernetesClient.resource(stsUpdate).serverSideApply();

    return toInstance(updatedSts);
  }

  public ServerInstanceDto getServer(String serverName) {
    StatefulSet sts = kubernetesClient.apps().statefulSets()
        .inNamespace(serverControllerConfig.getNamespace()).withName(serverName).get();

    if (sts == null) {
      throw new NotFoundException("Server instance not found");
    }

    return toInstance(sts);
  }

  public List<ServerInstanceDto> list() {
    return kubernetesClient.apps().statefulSets() //
        .inNamespace(serverControllerConfig.getNamespace()) //
        .withLabel(LABEL_MANAGED_BY, VALUE_MANAGED_BY) //
        .list() //
        .getItems() //
        .stream() //
        .map(this::toInstance) //
        .toList();
  }

  /**
   * Elimina StatefulSet, Service e PVC associati al server[cite: 49].
   */
  public boolean delete(String serverName) {
    // Elimina lo StatefulSet
    kubernetesClient.apps().statefulSets().inNamespace(serverControllerConfig.getNamespace())
        .withName(serverName).delete();

    // Elimina il Service
    kubernetesClient.services().inNamespace(serverControllerConfig.getNamespace())
        .withName(serverName).delete();

    // Elimina il PVC
    kubernetesClient.persistentVolumeClaims().inNamespace(serverControllerConfig.getNamespace())
        .withName(serverName + "-pvc").delete();

    return true;
  }

  /**
   * Avvia il server portando le repliche a 1
   */
  public void startServer(String serverName) {
    kubernetesClient.apps().statefulSets() //
        .inNamespace(serverControllerConfig.getNamespace()) //
        .withName(serverName) //
        .scale(1);
  }

  /**
   * Spegne il server portando le repliche a 0
   */
  public void stopServer(String serverName) {
    kubernetesClient.apps().statefulSets() //
        .inNamespace(serverControllerConfig.getNamespace()) //
        .withName(serverName) //
        .scale(0);
  }

  /**
   * Spegne il server e killa il pod immediatamente (grace period 0)
   */
  public void terminateServer(String serverName) {
    stopServer(serverName);

    kubernetesClient.pods() //
        .inNamespace(serverControllerConfig.getNamespace()) //
        .withName(serverName + "-0") //
        .withGracePeriod(0) //
        .delete(); //
  }

  public ServerInstanceInfoDto getServerInfo(String serverName) {
    ServerInstanceInfoDto info = new ServerInstanceInfoDto();

    StatefulSet sts = kubernetesClient.apps().statefulSets()
        .inNamespace(serverControllerConfig.getNamespace()).withName(serverName).get();

    if (sts == null || sts.getSpec().getReplicas() == 0) {
      info.setState(ServerStateDto.STOPPED);
      return info;
    }

    Pod pod = kubernetesClient.pods().inNamespace(serverControllerConfig.getNamespace())
        .withName(getPodName(serverName)) //
        .get();

    if (pod == null) {
      info.setState(ServerStateDto.STOPPED);
      return info;
    }

    if (pod.getMetadata().getDeletionTimestamp() != null) {
      info.setState(ServerStateDto.SHUTDOWN);
      return info;
    }

    boolean isReady = pod.getStatus().getConditions().stream()
        .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));

    if (!isReady) {
      info.setState(ServerStateDto.STARTING);
      return info;
    }

    info.setState(ServerStateDto.RUNNING);
    return fetchLiveMonitorData(info, serverName);
  }

  public void sendCommand(String serverName, String command) {
    try (ExecWatch watch = kubernetesClient.pods() //
        .inNamespace(serverControllerConfig.getNamespace()) //
        .withName(getPodName(serverName)) //
        .inContainer("minecraft") //
        .exec("gosu", "minecraft", "mc-send-to-console", command)) {

      Integer code = watch.exitCode().get(10, TimeUnit.SECONDS);

      if (code == null || code != 0) {
        throw new RuntimeException("Il comando è fallito con codice: " + code);
      }
    } catch (Exception e) {
      throw new RuntimeException("Impossibile inviare il comando al server", e);
    }
  }

  public Multi<String> logs(String serverName) {
    return Multi.createFrom().<String>emitter(emitter -> {
      // Spostiamo TUTTO nel worker pool, inclusa la chiamata .watchLog()
      Infrastructure.getDefaultWorkerPool().execute(() -> {
        LogWatch watch = null;
        try {
          watch = kubernetesClient.pods().inNamespace(serverControllerConfig.getNamespace())
              .withName(getPodName(serverName)).inContainer("minecraft").tailingLines(20)
              .watchLog();

          final LogWatch finalWatch = watch;
          emitter.onTermination(finalWatch::close);

          try (BufferedReader reader =
              new BufferedReader(new InputStreamReader(finalWatch.getOutput()))) {
            String line;
            while (!emitter.isCancelled() && (line = reader.readLine()) != null) {
              // tolgo i log non di minecraft
              if(line.startsWith("[")) {
                emitter.emit(line);
              }
            }
            emitter.complete();
          }
        } catch (Exception e) {
          if (!emitter.isCancelled()) {
            emitter.fail(e);
          }
        } finally {
          if (watch != null) {
            watch.close();
          }
        }
      });
    });
  }

  private String getPodName(String serverName) {
    return String.format("%s-0", serverName);
  }

  private ServerInstanceDto toInstance(StatefulSet serverInstance) {
    Map<String, String> labels = serverInstance.getMetadata().getLabels();

    ServerInstanceDto server = new ServerInstanceDto();
    server.setName(labels.get(LABEL_SERVER_NAME));
    server.setType(ServerTypeDto.valueOf(labels.get(LABEL_SERVER_TYPE)));
    server.setCpu(Integer.parseInt((labels.get(LABEL_SERVER_CPU))));
    server.setMemory(Integer.parseInt((labels.get(LABEL_SERVER_MEMORY))));
    server.setVersion(labels.get(LABEL_SERVER_MINECRAFT_VERSION));
    server.setModrinthProjectId(labels.get(LABEL_SERVER_MODRINTH_PROJECT_ID));
    server.setCurseforgePageUrl(labels.get(LABEL_SERVER_CURSEFORGE_URL));
    server.setEula(Boolean.valueOf(labels.get(LABEL_SERVER_MINECRAFT_EULA)));

    return server;
  }

  private Probe createProbe(int delay, int period, int failure, int success) {
    return new ProbeBuilder().withNewExec() //
        .addToCommand("mc-health") //
        .endExec() //
        .withInitialDelaySeconds(delay) //
        .withPeriodSeconds(period) //
        .withFailureThreshold(failure) //
        .withSuccessThreshold(success) //
        .withTimeoutSeconds(1) //
        .build();
  }

  private Map<String, String> serverLabels(ServerInstanceDto server) {
    Map<String, String> labels = new HashMap<>();
    labels.put(LABEL_SERVER_TYPE, server.getType().toString().toUpperCase());
    labels.put(LABEL_SERVER_CPU, Integer.toString(server.getCpu()));
    labels.put(LABEL_SERVER_MEMORY, Integer.toString(server.getMemory()));
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
    labels.putAll(labels);

    return labels;
  }

  private Map<String, String> labels(ServerInstanceDto server) {
    Map<String, String> labels = new HashMap<>();
    labels.put(LABEL_SERVER_NAME, server.getName());
    labels.put(LABEL_MANAGED_BY, VALUE_MANAGED_BY);

    return labels;
  }

  private Map<String, Quantity> serverLimits(ServerInstanceDto server) {
    String k8sMemory = (int) (server.getMemory() * 1.1) + "Mi";
    String k8sCpu = server.getCpu() + "m";
    return Map.of( //
        "cpu", new Quantity(k8sCpu), //
        "memory", new Quantity(k8sMemory) //
    );
  }

  private void validate(ServerInstanceDto server) {
    if (server.getCpu() <= 0) {
      throw new BadRequestException("Cpu must be potitive");
    }
    if (server.getMemory() <= 0) {
      throw new BadRequestException("Cpu must be potitive");
    }
    server.getType().validate(server);

  }

  private ServerInstanceInfoDto fetchLiveMonitorData(ServerInstanceInfoDto info,
      String serverName) {
    try (ExecWatch watch = kubernetesClient.pods() //
        .inNamespace(serverControllerConfig.getNamespace()) //
        .withName(getPodName(serverName)) //
        .inContainer("minecraft") //
        .redirectingOutput() //
        .exec("mc-monitor", "status", "--host", "localhost", "--port", "25565", "--json")) {

      // Leggiamo l'output dall'InputStream dell'ExecWatch
      StringBuilder sb = new StringBuilder();
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(watch.getOutput()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          sb.append(line);
        }
      }

      Integer exitCode = watch.exitCode().get(5, TimeUnit.SECONDS);

      if (exitCode != null && exitCode == 0 && sb.length() > 0) {
        JsonNode root = objectMapper.readTree(sb.toString());
        JsonNode serverInfo = root.get("server_info");

        if (serverInfo != null && !serverInfo.isNull()) {
          info.setVersion(objectMapper.treeToValue(serverInfo.get("version"), Version.class));
          info.setPlayers(objectMapper.treeToValue(serverInfo.get("players"), Players.class));
          info.setDescription(
              objectMapper.treeToValue(serverInfo.get("description"), Description.class));
        }
      }
    } catch (Exception e) {
      System.err.println("Errore fetch live data: " + e.getMessage());
    }
    return info;
  }
}
