package it.lorisdemicheli.minecraft_servers_controller.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.config.TestOptions;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerState;
import it.lorisdemicheli.minecraft_servers_controller.domain.SmartServerTypeDto;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;
import tools.jackson.databind.ObjectMapper;

class ServerServiceTest {

  KubernetesGateway k8s = mock(KubernetesGateway.class);
  ServerLifecycleManager lifecycle = mock(ServerLifecycleManager.class);
  MinecraftServerOptions options = TestOptions.valid();
  ServerManifestFactory factory = new ServerManifestFactory(options, new ObjectMapper());
  ServerService service = new ServerService(k8s, factory, lifecycle, options);

  static ServerInstanceDto dto() {
    ServerInstanceDto dto = new ServerInstanceDto();
    dto.setName("alpha");
    dto.setType(SmartServerTypeDto.VANILLA);
    dto.setEula(true);
    dto.setVersion("1.21.4");
    return dto;
  }

  @Test
  void validCreateIsDelegatedAndMapped() {
    when(lifecycle.create(any())).thenAnswer(i -> factory.statefulSet(i.getArgument(0)));
    ServerInstanceDto created = service.create(dto());
    assertEquals("alpha", created.getName());
    assertEquals(ServerState.STOPPED, created.getState());
  }

  @Test
  void invalidConfigurationsAreRefusedBeforeAnyChange() {
    ServerInstanceDto[] bad = new ServerInstanceDto[8];
    for (int i = 0; i < bad.length; i++) {
      bad[i] = dto();
    }
    bad[0].setName("Bad_Name");
    bad[1].setName("console"); // reserved
    bad[2].setCpu(50);
    bad[3].setMemory(100);
    bad[4].setEula(false);
    bad[5].setVersion("1.21; rm -rf /");
    bad[6].setType(SmartServerTypeDto.MODRINTH); // no project
    bad[7].setType(null);
    for (ServerInstanceDto d : bad) {
      assertThrows(RuntimeException.class, () -> service.create(d));
    }
    verify(lifecycle, never()).create(any());
  }

  @Test
  void acceptedVersions() {
    when(lifecycle.create(any())).thenAnswer(i -> factory.statefulSet(i.getArgument(0)));
    for (String v : new String[] {"LATEST", "snapshot", "1.20", "1.21.4", "1.21-pre1", "24w14a"}) {
      ServerInstanceDto d = dto();
      d.setVersion(v);
      assertDoesNotThrow(() -> service.create(d), v);
    }
  }

  @Test
  void curseForgeNeedsAValidPageAndAConfiguredKey() {
    ServerInstanceDto d = dto();
    d.setType(SmartServerTypeDto.CURSEFORGE);
    d.setCurseforgePageUrl("https://evil.example/modpack");
    assertThrows(InvalidRequestException.class, () -> service.create(d));
    d.setCurseforgePageUrl("https://www.curseforge.com/minecraft/modpacks/all-the-mods-9");
    assertThrows(ConflictException.class, () -> service.create(d), "no API key configured");
  }

  @Test
  void updateKeepsTheNameFromThePath() {
    when(lifecycle.update(any())).thenAnswer(i -> factory.statefulSet(i.getArgument(0)));
    ServerInstanceDto d = dto();
    d.setName("other");
    d.setEula(false); // not required for updates of existing servers
    assertEquals("alpha", service.update("alpha", d).getName());
  }

  @Test
  void listSkipsStatefulSetsWithoutConfiguration() {
    V1StatefulSet good = factory.statefulSet(dto());
    V1StatefulSet legacy = new V1StatefulSet().metadata(new V1ObjectMeta().name("legacy"));
    when(k8s.listStatefulSets(anyString(), anyString())).thenReturn(List.of(legacy, good));
    assertEquals(List.of("alpha"), service.list().stream().map(ServerInstanceDto::getName).toList());
  }

  @Test
  void lifecycleCallsValidateTheName() {
    assertThrows(InvalidRequestException.class, () -> service.start("../x"));
    assertThrows(InvalidRequestException.class, () -> service.delete("../x", true));
    service.stop("alpha");
    verify(lifecycle).stop("alpha");
  }
}
