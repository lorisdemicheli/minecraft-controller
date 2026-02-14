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
import java.util.HashMap;
import java.util.Map;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import it.lorisdemicheli.server_controller.config.ServerControllerConfig;
import it.lorisdemicheli.server_controller.dto.ServerInstanceDto;
import it.lorisdemicheli.server_controller.dto.ServerTypeDto;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;

@ApplicationScoped
@RequiredArgsConstructor
public class ServerInstanceService {

  private final KubernetesClient kubernetesClient;
  private final ServerControllerConfig serverControllerConfig;

  public ServerInstanceDto createServer(ServerInstanceDto server) {
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
    StatefulSet serverInstance =  kubernetesClient.resource(sts).serverSideApply();
    
    return toInstance(serverInstance);
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
}
