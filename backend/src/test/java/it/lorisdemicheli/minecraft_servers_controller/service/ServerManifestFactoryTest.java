package it.lorisdemicheli.minecraft_servers_controller.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.config.TestOptions;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerConfig;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.SmartServerTypeDto;
import tools.jackson.databind.ObjectMapper;

class ServerManifestFactoryTest {

  MinecraftServerOptions options = TestOptions.valid();
  ServerManifestFactory factory = new ServerManifestFactory(options, new ObjectMapper());

  static ServerInstanceDto dto() {
    ServerInstanceDto dto = new ServerInstanceDto();
    dto.setName("alpha");
    dto.setType(SmartServerTypeDto.VANILLA);
    dto.setCpu(2000);
    dto.setMemory(4096);
    dto.setEula(true);
    dto.setVersion("1.21.4");
    return dto;
  }

  static V1Container container(V1StatefulSet sts) {
    return sts.getSpec().getTemplate().getSpec().getContainers().get(0);
  }

  static long mib(Quantity q) {
    return q.getNumber().longValue() / (1024 * 1024);
  }

  @Test
  void defaultsKeepRequestsEqualToLimitsWithHeapOverhead() {
    V1ResourceRequirements r = factory.resources(dto());
    assertEquals(4096 + 614, mib(r.getLimits().get("memory"))); // 15% of the heap
    assertEquals(r.getLimits().get("memory"), r.getRequests().get("memory"));
    assertEquals(new Quantity("2000m").getNumber(), r.getLimits().get("cpu").getNumber());
    assertEquals(r.getLimits().get("cpu").getNumber(), r.getRequests().get("cpu").getNumber());
  }

  @Test
  void smallHeapsGetTheMinimumOverhead() {
    ServerInstanceDto small = dto();
    small.setMemory(512);
    assertEquals(512 + 256, mib(factory.resources(small).getLimits().get("memory")));
  }

  @Test
  void requestsCanBeLowerAndTheCpuLimitRemoved() {
    options.getServerResources().setCpuRequestPercent(25);
    options.getServerResources().setMemoryRequestPercent(50);
    options.getServerResources().setCpuLimitEnabled(false);
    V1ResourceRequirements r = factory.resources(dto());
    assertEquals(new Quantity("500m").getNumber(), r.getRequests().get("cpu").getNumber());
    assertNull(r.getLimits().get("cpu"));
    assertEquals((4096 + 614 + 1) / 2, mib(r.getRequests().get("memory")));
  }

  @Test
  void podIsHardenedWithoutBreakingTheImage() {
    V1StatefulSet sts = factory.statefulSet(dto());
    V1PodSpec pod = sts.getSpec().getTemplate().getSpec();
    assertFalse(pod.getAutomountServiceAccountToken());
    assertEquals("RuntimeDefault", pod.getSecurityContext().getSeccompProfile().getType());
    assertNull(pod.getSecurityContext().getRunAsNonRoot(), "the itzg image starts as root by default");
    assertFalse(container(sts).getSecurityContext().getPrivileged());
    assertFalse(container(sts).getSecurityContext().getAllowPrivilegeEscalation());
    assertEquals(0, sts.getSpec().getReplicas(), "created stopped");
    assertEquals("alpha", container(sts).getVolumeMounts().get(0).getSubPath());
    assertArrayEquals(new String[] {"gosu", "minecraft", "mc-send-to-console", "list"},
        factory.consoleCommand("list"));
  }

  @Test
  void nonRootModeDropsEverything() {
    options.getServerSecurity().setRunAsNonRoot(true);
    V1StatefulSet sts = factory.statefulSet(dto());
    var pod = sts.getSpec().getTemplate().getSpec().getSecurityContext();
    assertTrue(pod.getRunAsNonRoot());
    assertEquals(1000L, pod.getRunAsUser());
    assertEquals("ALL", container(sts).getSecurityContext().getCapabilities().getDrop().get(0));
    assertArrayEquals(new String[] {"mc-send-to-console", "list"}, factory.consoleCommand("list"));
  }

  @Test
  void curseForgeKeyComesFromTheSecretWhenConfigured() {
    options.setCurseForgeApiKey("plain-key");
    ServerInstanceDto cf = dto();
    cf.setType(SmartServerTypeDto.CURSEFORGE);
    cf.setCurseforgePageUrl("https://www.curseforge.com/minecraft/modpacks/all-the-mods-9");

    V1EnvVar plain = env(factory.statefulSet(cf), "CF_API_KEY");
    assertEquals("plain-key", plain.getValue());

    options.getCurseForgeApiKeySecret().setName("mc-auth");
    V1EnvVar fromSecret = env(factory.statefulSet(cf), "CF_API_KEY");
    assertNull(fromSecret.getValue(), "no clear text key in the StatefulSet");
    assertEquals("mc-auth", fromSecret.getValueFrom().getSecretKeyRef().getName());
    assertEquals("curseforge-api-key", fromSecret.getValueFrom().getSecretKeyRef().getKey());
  }

  @Test
  void configRoundTripsThroughTheAnnotationAndApplyUpdatesTheSpec() {
    V1StatefulSet sts = factory.statefulSet(dto());
    assertEquals(ServerConfig.from(dto()), factory.readConfig(sts).orElseThrow());

    ServerInstanceDto bigger = dto();
    bigger.setMemory(8192);
    factory.apply(sts, bigger);
    assertEquals(8192, factory.readConfig(sts).orElseThrow().memory());
    assertEquals("8192M", env(sts, "MEMORY").getValue());
  }

  @Test
  void brokenAnnotationIsIgnored() {
    V1StatefulSet sts = factory.statefulSet(dto());
    sts.getMetadata().putAnnotationsItem("it.lorisdemicheli/config", "{not json");
    assertTrue(factory.readConfig(sts).isEmpty());
  }

  private static V1EnvVar env(V1StatefulSet sts, String name) {
    return container(sts).getEnv().stream().filter(e -> name.equals(e.getName())).findFirst()
        .orElseThrow();
  }
}
