package it.lorisdemicheli.minecraft_servers_controller.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerLabel;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.config.TestOptions;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.SmartServerTypeDto;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;
import it.lorisdemicheli.minecraft_servers_controller.exception.KubernetesUnavailableException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceAlreadyExistsException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceNotFoundException;
import it.lorisdemicheli.minecraft_servers_controller.service.ServerLifecycleManager.DeleteOutcome;
import it.lorisdemicheli.minecraft_servers_controller.storage.ServerStorage;
import tools.jackson.databind.ObjectMapper;

class ServerLifecycleManagerTest {

  @TempDir
  Path tmp;

  KubernetesGateway k8s = mock(KubernetesGateway.class);
  MinecraftServerOptions options = TestOptions.valid();
  ServerManifestFactory factory = new ServerManifestFactory(options, new ObjectMapper());
  ServerStorage storage;
  MutableClock clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
  ServerLifecycleManager manager;

  @BeforeEach
  void setUp() {
    storage = new ServerStorage(tmp.resolve("data"));
    manager = new ServerLifecycleManager(k8s, factory, storage, options, new SimpleMeterRegistry(),
        clock);
    when(k8s.findStatefulSet(anyString(), anyString())).thenReturn(Optional.empty());
    when(k8s.findService(anyString(), anyString())).thenReturn(Optional.empty());
  }

  private static ServerInstanceDto dto(String name) {
    ServerInstanceDto dto = new ServerInstanceDto();
    dto.setName(name);
    dto.setType(SmartServerTypeDto.VANILLA);
    dto.setVersion("1.21.4");
    dto.setEula(true);
    return dto;
  }

  private static V1Service managedService(String name) {
    return new V1Service().metadata(new V1ObjectMeta().name(name)
        .putLabelsItem(MinecraftServerLabel.LABEL_MANAGED_BY, MinecraftServerLabel.MANAGED_BY_VALUE));
  }

  // ---------------------------------------------------------------- create

  @Test
  void createMakesDirectoryServiceAndStatefulSetInOrder() {
    V1StatefulSet created = factory.statefulSet(dto("alpha"));
    when(k8s.createStatefulSet(eq("mc"), any())).thenReturn(created);

    assertSame(created, manager.create(dto("alpha")));

    assertTrue(storage.exists("alpha"));
    InOrder order = inOrder(k8s);
    order.verify(k8s).createService(eq("mc"), any());
    order.verify(k8s).createStatefulSet(eq("mc"), any());
  }

  @Test
  void failedStatefulSetRollsBackServiceAndNewEmptyDirectory() {
    when(k8s.createStatefulSet(eq("mc"), any()))
        .thenThrow(new InvalidRequestException("Kubernetes rejected the request"));
    when(k8s.findService("mc", "alpha")).thenReturn(Optional.of(managedService("alpha")));

    assertThrows(InvalidRequestException.class, () -> manager.create(dto("alpha")));

    verify(k8s).deleteService("mc", "alpha");
    assertFalse(storage.exists("alpha"), "the directory created by this call is removed");
  }

  @Test
  void rollbackNeverRemovesExistingData() throws Exception {
    storage.createServerDir("alpha");
    Files.writeString(storage.serverDir("alpha").resolve("level.dat"), "world");
    when(k8s.createStatefulSet(eq("mc"), any())).thenThrow(new InvalidRequestException("bad"));

    assertThrows(InvalidRequestException.class, () -> manager.create(dto("alpha")));

    assertEquals("world", Files.readString(storage.serverDir("alpha").resolve("level.dat")));
  }

  @Test
  void rollbackKeepsTheDirectoryIfTheStatefulSetStateIsUnknown() {
    when(k8s.createStatefulSet(eq("mc"), any()))
        .thenThrow(new KubernetesUnavailableException("down"));
    // checking whether the create was applied works the first time, then the API is down
    when(k8s.findStatefulSet("mc", "alpha")).thenReturn(Optional.empty())
        .thenReturn(Optional.empty())
        .thenThrow(new KubernetesUnavailableException("down"));

    KubernetesUnavailableException e =
        assertThrows(KubernetesUnavailableException.class, () -> manager.create(dto("alpha")));

    assertEquals(1, e.getSuppressed().length, "the rollback failure is attached, not lost");
    assertTrue(storage.exists("alpha"), "kept: maybe a server is using it");
    verify(k8s, never()).deleteService(anyString(), anyString());
  }

  @Test
  void lostCreateAnswerIsResolvedByReadingTheState() {
    V1StatefulSet applied = factory.statefulSet(dto("alpha"));
    when(k8s.createStatefulSet(eq("mc"), any()))
        .thenThrow(new KubernetesUnavailableException("timeout"));
    when(k8s.findStatefulSet("mc", "alpha")).thenReturn(Optional.empty())
        .thenReturn(Optional.of(applied));

    assertSame(applied, manager.create(dto("alpha")));
    verify(k8s, never()).deleteService(anyString(), anyString());
  }

  @Test
  void orphanServiceOfAnInterruptedCreateIsAdopted() {
    when(k8s.createService(eq("mc"), any()))
        .thenThrow(new ResourceAlreadyExistsException("exists"));
    when(k8s.findService("mc", "alpha")).thenReturn(Optional.of(managedService("alpha")));
    when(k8s.createStatefulSet(eq("mc"), any())).thenReturn(factory.statefulSet(dto("alpha")));

    manager.create(dto("alpha"));
    verify(k8s).createStatefulSet(eq("mc"), any());
  }

  @Test
  void foreignServiceWithTheSameNameIsAConflict() {
    when(k8s.createService(eq("mc"), any()))
        .thenThrow(new ResourceAlreadyExistsException("exists"));
    when(k8s.findService("mc", "alpha"))
        .thenReturn(Optional.of(new V1Service().metadata(new V1ObjectMeta().name("alpha"))));

    assertThrows(ConflictException.class, () -> manager.create(dto("alpha")));
    verify(k8s, never()).createStatefulSet(anyString(), any());
    verify(k8s, never()).deleteService(anyString(), anyString());
  }

  @Test
  void existingServerIsRefusedBeforeTouchingAnything() {
    when(k8s.findStatefulSet("mc", "alpha")).thenReturn(Optional.of(new V1StatefulSet()));
    assertThrows(ResourceAlreadyExistsException.class, () -> manager.create(dto("alpha")));
    assertFalse(storage.exists("alpha"));
    verify(k8s, never()).createService(anyString(), any());
  }

  @Test
  void createIsRefusedWhileAPreviousDeletionIsPending() {
    storage.createServerDir("alpha");
    storage.markPendingDeletion("alpha");
    assertThrows(ConflictException.class, () -> manager.create(dto("alpha")));
  }

  @Test
  void createIsRefusedWithoutFreeSpace() {
    options.getStorage().setMinFreeGb(Integer.MAX_VALUE);
    assertThrows(ConflictException.class, () -> manager.create(dto("alpha")));
    assertFalse(storage.exists("alpha"));
  }

  // ---------------------------------------------------------------- delete

  @Test
  void deleteOfAStoppedServerTrashesTheDataImmediately() {
    storage.createServerDir("alpha");
    when(k8s.deleteStatefulSet("mc", "alpha")).thenReturn(true);
    when(k8s.findService("mc", "alpha")).thenReturn(Optional.of(managedService("alpha")));
    when(k8s.deleteService("mc", "alpha")).thenReturn(true);

    assertEquals(DeleteOutcome.DELETED, manager.delete("alpha", true));
    assertFalse(storage.exists("alpha"));
    assertFalse(storage.isPendingDeletion("alpha"));
  }

  @Test
  void dataOfARunningServerWaitsForThePodToStop() {
    storage.createServerDir("alpha");
    when(k8s.podExists("mc", "alpha-0")).thenReturn(true);
    when(k8s.deleteStatefulSet("mc", "alpha")).thenReturn(true);

    assertEquals(DeleteOutcome.DATA_PENDING, manager.delete("alpha", true));
    assertTrue(storage.exists("alpha"), "not moved while the pod may still write");
    assertTrue(storage.isPendingDeletion("alpha"));

    manager.reconcile(); // pod still there
    assertTrue(storage.exists("alpha"));

    when(k8s.podExists("mc", "alpha-0")).thenReturn(false);
    manager.reconcile(); // pod gone, but force-deleted pods need a safety delay
    assertTrue(storage.exists("alpha"));

    clock.advance(ServerLifecycleManager.POD_SHUTDOWN_SAFETY_DELAY.plusSeconds(1));
    manager.reconcile();
    assertFalse(storage.exists("alpha"));
    assertFalse(storage.isPendingDeletion("alpha"));
  }

  @Test
  void pendingDeletionIsDroppedIfTheServerExistsAgain() {
    storage.createServerDir("alpha");
    storage.markPendingDeletion("alpha", clock.instant());
    when(k8s.findStatefulSet("mc", "alpha")).thenReturn(Optional.of(new V1StatefulSet()));
    clock.advance(java.time.Duration.ofHours(1));

    manager.reconcile();

    assertTrue(storage.exists("alpha"), "data of an existing server is never trashed");
    assertFalse(storage.isPendingDeletion("alpha"));
  }

  @Test
  void deleteWithoutDataKeepsTheFolder() {
    storage.createServerDir("alpha");
    when(k8s.deleteStatefulSet("mc", "alpha")).thenReturn(true);
    assertEquals(DeleteOutcome.DATA_KEPT, manager.delete("alpha", false));
    assertTrue(storage.exists("alpha"));
    assertFalse(storage.isPendingDeletion("alpha"));
  }

  @Test
  void deleteIsIdempotentAfterAPartialFailure() {
    storage.createServerDir("alpha");
    when(k8s.deleteStatefulSet("mc", "alpha")).thenReturn(true).thenReturn(false);
    when(k8s.findService("mc", "alpha")).thenReturn(Optional.of(managedService("alpha")));
    when(k8s.deleteService("mc", "alpha")).thenThrow(new KubernetesUnavailableException("down"))
        .thenReturn(true);

    assertThrows(KubernetesUnavailableException.class, () -> manager.delete("alpha", true));
    assertTrue(storage.exists("alpha"), "nothing trashed after a failed step");
    assertFalse(storage.isPendingDeletion("alpha"));

    assertEquals(DeleteOutcome.DELETED, manager.delete("alpha", true)); // retry completes it
    assertFalse(storage.exists("alpha"));
  }

  @Test
  void deleteOfAnUnknownServerIsNotFound() {
    assertThrows(ResourceNotFoundException.class, () -> manager.delete("ghost", true));
  }

  @Test
  void unmanagedServiceIsNotDeleted() {
    when(k8s.deleteStatefulSet("mc", "alpha")).thenReturn(true);
    when(k8s.findService("mc", "alpha"))
        .thenReturn(Optional.of(new V1Service().metadata(new V1ObjectMeta().name("alpha"))));
    manager.delete("alpha", false);
    verify(k8s, never()).deleteService(anyString(), anyString());
  }

  // ---------------------------------------------------------------- start / stop / update

  @Test
  void startRecreatesAMissingFolderAndScales() {
    when(k8s.getStatefulSet("mc", "alpha")).thenReturn(new V1StatefulSet());
    manager.start("alpha");
    assertTrue(storage.exists("alpha"));
    verify(k8s).scaleStatefulSet("mc", "alpha", 1);
  }

  @Test
  void startOfAnUnknownServerDoesNotCreateAFolder() {
    when(k8s.getStatefulSet("mc", "ghost")).thenThrow(new ResourceNotFoundException("nope"));
    assertThrows(ResourceNotFoundException.class, () -> manager.start("ghost"));
    assertFalse(storage.exists("ghost"));
    verify(k8s, never()).scaleStatefulSet(anyString(), anyString(), anyInt());
  }

  @Test
  void stopAndTerminate() {
    manager.stop("alpha");
    verify(k8s).scaleStatefulSet("mc", "alpha", 0);

    when(k8s.deletePodNow("mc", "alpha-0")).thenReturn(false); // already gone: fine
    manager.terminate("alpha");
    verify(k8s, times(2)).scaleStatefulSet("mc", "alpha", 0);
    verify(k8s).deletePodNow("mc", "alpha-0");
  }

  @Test
  void updateRetriesOnConcurrentModification() {
    when(k8s.getStatefulSet("mc", "alpha")).thenAnswer(i -> factory.statefulSet(dto("alpha")));
    V1StatefulSet updated = new V1StatefulSet();
    when(k8s.replaceStatefulSet(eq("mc"), eq("alpha"), any()))
        .thenThrow(new ConflictException("modified")).thenReturn(updated);

    ServerInstanceDto change = dto("alpha");
    change.setMemory(4096);
    assertSame(updated, manager.update(change));
    verify(k8s, times(2)).getStatefulSet("mc", "alpha");
  }

  @Test
  void updateGivesUpAfterRepeatedConflicts() {
    when(k8s.getStatefulSet("mc", "alpha")).thenAnswer(i -> factory.statefulSet(dto("alpha")));
    when(k8s.replaceStatefulSet(eq("mc"), eq("alpha"), any()))
        .thenThrow(new ConflictException("modified"));
    assertThrows(ConflictException.class, () -> manager.update(dto("alpha")));
    verify(k8s, times(3)).replaceStatefulSet(eq("mc"), eq("alpha"), any());
  }

  // ---------------------------------------------------------------- reconcile

  @Test
  void reconcileRemovesOnlyOldOrphanServices() {
    V1Service oldOrphan = managedService("old");
    oldOrphan.getMetadata().creationTimestamp(OffsetDateTime.ofInstant(
        clock.instant().minus(java.time.Duration.ofHours(1)), ZoneOffset.UTC));
    V1Service freshOrphan = managedService("fresh"); // maybe a create in progress
    freshOrphan.getMetadata().creationTimestamp(OffsetDateTime.ofInstant(clock.instant(),
        ZoneOffset.UTC));
    V1Service withServer = managedService("alive");
    withServer.getMetadata().creationTimestamp(oldOrphan.getMetadata().getCreationTimestamp());
    when(k8s.listStatefulSets(eq("mc"), anyString())).thenReturn(
        List.of(new V1StatefulSet().metadata(new V1ObjectMeta().name("alive"))));
    when(k8s.listServices(eq("mc"), anyString()))
        .thenReturn(List.of(oldOrphan, freshOrphan, withServer));

    manager.reconcile();

    verify(k8s).deleteService("mc", "old");
    verify(k8s, never()).deleteService("mc", "fresh");
    verify(k8s, never()).deleteService("mc", "alive");
  }

  @Test
  void reconcileSurvivesKubernetesOutages() {
    when(k8s.listStatefulSets(anyString(), anyString()))
        .thenThrow(new KubernetesUnavailableException("down"));
    manager.reconcile(); // no exception
  }

  @Test
  void locksAreReleased() {
    when(k8s.createStatefulSet(eq("mc"), any())).thenThrow(new InvalidRequestException("bad"));
    assertThrows(InvalidRequestException.class, () -> manager.create(dto("alpha")));
    // a second operation on the same name does not block
    manager.stop("alpha");
    verify(k8s).scaleStatefulSet("mc", "alpha", 0);
  }

  // ---------------------------------------------------------------- helpers

  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(java.time.Duration d) {
      now = now.plus(d);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }
}
