package it.lorisdemicheli.minecraft_servers_controller.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.codec.ServerSentEvent;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.kubernetes.client.openapi.models.V1StatefulSetSpec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.config.TestOptions;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceInfoDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerState;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;
import it.lorisdemicheli.minecraft_servers_controller.exception.KubernetesUnavailableException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceNotFoundException;
import it.lorisdemicheli.minecraft_servers_controller.storage.ServerStorage;
import reactor.core.Disposable;
import reactor.test.scheduler.VirtualTimeScheduler;
import tools.jackson.databind.ObjectMapper;

class ServerConsoleServiceTest {

  @TempDir
  Path tmp;

  KubernetesGateway k8s = mock(KubernetesGateway.class);
  MinecraftServerOptions options = TestOptions.valid();
  VirtualTimeScheduler time = VirtualTimeScheduler.create();
  ServerStorage storage;
  ServerConsoleService console;

  @BeforeEach
  void setUp() {
    options.getConsole().setInfoPollInterval(Duration.ofSeconds(10));
    options.getConsole().setInfoStreamGracePeriod(Duration.ofSeconds(10));
    options.getConsole().setHeartbeatInterval(Duration.ofSeconds(15));
    options.getConsole().setLogPollInterval(Duration.ofMillis(500));
    storage = new ServerStorage(tmp.resolve("data"));
    ObjectMapper mapper = new ObjectMapper();
    console = new ServerConsoleService(k8s, new ServerManifestFactory(options, mapper), storage,
        options, mapper, new SimpleMeterRegistry(), time);
    // stopped server: info() needs no exec
    when(k8s.getStatefulSet("mc", "alpha")).thenReturn(stopped("alpha"));
  }

  @AfterEach
  void tearDown() {
    time.dispose();
  }

  private static V1StatefulSet stopped(String name) {
    return new V1StatefulSet().metadata(new V1ObjectMeta().name(name))
        .spec(new V1StatefulSetSpec().replicas(0));
  }

  private <T> Disposable collect(reactor.core.publisher.Flux<ServerSentEvent<T>> flux,
      List<ServerSentEvent<T>> into) {
    return flux.subscribe(into::add);
  }

  // ---------------------------------------------------------------- info stream

  @Test
  void viewersShareOnePollingLoop() {
    List<ServerSentEvent<ServerInstanceInfoDto>> a = new CopyOnWriteArrayList<>();
    List<ServerSentEvent<ServerInstanceInfoDto>> b = new CopyOnWriteArrayList<>();
    Disposable first = collect(console.infoStream("alpha"), a);
    Disposable second = collect(console.infoStream("alpha"), b);

    time.advanceTimeBy(Duration.ofSeconds(30)); // ticks at 0, 10, 20, 30

    assertEquals(1, console.activeInfoStreams());
    // 1 fail-fast check + 4 polls: not one poll per viewer
    verify(k8s, times(5)).getStatefulSet("mc", "alpha");
    assertTrue(a.stream().anyMatch(e -> e.data() != null));
    assertEquals(ServerState.STOPPED, b.stream().filter(e -> e.data() != null).findFirst()
        .orElseThrow().data().getState());
    assertEquals("alpha", a.get(0).event());
    first.dispose();
    second.dispose();
  }

  @Test
  void oneViewerLeavingDoesNotStopTheOthers() {
    List<ServerSentEvent<ServerInstanceInfoDto>> b = new CopyOnWriteArrayList<>();
    Disposable first = console.infoStream("alpha").subscribe();
    Disposable second = collect(console.infoStream("alpha"), b);
    time.advanceTimeBy(Duration.ofSeconds(1));

    first.dispose();
    int before = b.size();
    time.advanceTimeBy(Duration.ofSeconds(60));

    assertTrue(b.size() > before, "the remaining viewer keeps receiving");
    assertEquals(1, console.activeInfoStreams());
    second.dispose();
  }

  @Test
  void pollingStopsAfterTheGracePeriodAndTheStreamIsRemoved() {
    Disposable viewer = console.infoStream("alpha").subscribe();
    time.advanceTimeBy(Duration.ofSeconds(1));
    viewer.dispose();

    time.advanceTimeBy(Duration.ofSeconds(5)); // within the grace period: kept
    assertEquals(1, console.activeInfoStreams());

    time.advanceTimeBy(Duration.ofSeconds(6));
    assertEquals(0, console.activeInfoStreams(), "removed from the cache: no leak");

    int calls = org.mockito.Mockito.mockingDetails(k8s).getInvocations().size();
    time.advanceTimeBy(Duration.ofMinutes(5));
    assertEquals(calls, org.mockito.Mockito.mockingDetails(k8s).getInvocations().size(),
        "no more polling of Kubernetes");
  }

  @Test
  void aViewerReturningWithinTheGracePeriodReusesTheLoop() {
    console.infoStream("alpha").subscribe().dispose();
    time.advanceTimeBy(Duration.ofSeconds(5));
    Disposable again = console.infoStream("alpha").subscribe();
    time.advanceTimeBy(Duration.ofSeconds(30));
    assertEquals(1, console.activeInfoStreams());
    again.dispose();
    time.advanceTimeBy(Duration.ofSeconds(11));
    assertEquals(0, console.activeInfoStreams());
  }

  @Test
  void deletedServerCompletesTheStreamAndCleansUp() {
    boolean[] completed = {false};
    console.infoStream("alpha").subscribe(e -> {
    }, e -> {
    }, () -> completed[0] = true);
    time.advanceTimeBy(Duration.ofSeconds(1));

    when(k8s.getStatefulSet("mc", "alpha")).thenThrow(new ResourceNotFoundException("gone"));
    time.advanceTimeBy(Duration.ofSeconds(10));

    assertTrue(completed[0]);
    assertEquals(0, console.activeInfoStreams());
  }

  @Test
  void transientPollErrorsDoNotEndTheStream() {
    List<ServerSentEvent<ServerInstanceInfoDto>> events = new CopyOnWriteArrayList<>();
    collect(console.infoStream("alpha"), events);
    time.advanceTimeBy(Duration.ofSeconds(1));
    when(k8s.getStatefulSet("mc", "alpha")).thenThrow(new KubernetesUnavailableException("down"))
        .thenReturn(stopped("alpha"));

    time.advanceTimeBy(Duration.ofSeconds(20));

    assertEquals(1, console.activeInfoStreams());
    assertTrue(events.stream().filter(e -> e.data() != null).count() >= 2);
  }

  @Test
  void unknownServerFailsFast() {
    when(k8s.getStatefulSet("mc", "ghost")).thenThrow(new ResourceNotFoundException("nope"));
    assertThrows(ResourceNotFoundException.class, () -> console.infoStream("ghost"));
    assertEquals(0, console.activeInfoStreams());
  }

  @Test
  void idleStreamsGetHeartbeats() {
    List<ServerSentEvent<ServerInstanceInfoDto>> events = new CopyOnWriteArrayList<>();
    collect(console.infoStream("alpha"), events);
    time.advanceTimeBy(Duration.ofSeconds(31));
    assertTrue(events.stream().anyMatch(e -> "keepalive".equals(e.comment()) && e.data() == null));
  }

  @Test
  void concurrentJoinsAndLeavesKeepAConsistentCount() throws Exception {
    List<Thread> threads = new java.util.ArrayList<>();
    for (int i = 0; i < 50; i++) {
      threads.add(Thread.ofVirtual().start(() -> console.infoStream("alpha").subscribe().dispose()));
    }
    for (Thread t : threads) {
      t.join();
    }
    time.advanceTimeBy(Duration.ofSeconds(11));
    assertEquals(0, console.activeInfoStreams());
  }

  // ---------------------------------------------------------------- logs

  @Test
  void logStreamSendsTailThenNewLinesAndCutsHugeOnes() throws Exception {
    storage.createServerDir("alpha");
    Path log = storage.serverDir("alpha").resolve("logs/latest.log");
    Files.createDirectories(log.getParent());
    Files.writeString(log, "one\ntwo\n");
    options.getConsole().setMaxLogLineLength(256);

    List<ServerSentEvent<String>> events = new CopyOnWriteArrayList<>();
    Disposable d = collect(console.logStream("alpha"), events);
    time.advanceTimeBy(Duration.ZERO);
    Files.writeString(log, "three\n" + "x".repeat(1000) + "\n",
        java.nio.file.StandardOpenOption.APPEND);
    time.advanceTimeBy(Duration.ofSeconds(1));

    List<String> lines = events.stream().filter(e -> e.data() != null).map(ServerSentEvent::data)
        .toList();
    assertEquals(List.of("one", "two", "three"), lines.subList(0, 3));
    assertTrue(lines.get(3).length() < 300 && lines.get(3).endsWith("[truncated]"));
    d.dispose();
  }

  @Test
  void logStreamDoesNotFollowASymlinkedLogFile() throws Exception {
    storage.createServerDir("alpha");
    storage.createServerDir("beta");
    Files.writeString(storage.serverDir("beta").resolve("secret.txt"), "beta secret\n");
    Files.createDirectories(storage.serverDir("alpha").resolve("logs"));
    Files.createSymbolicLink(storage.serverDir("alpha").resolve("logs/latest.log"),
        storage.serverDir("beta").resolve("secret.txt"));

    List<ServerSentEvent<String>> events = new CopyOnWriteArrayList<>();
    collect(console.logStream("alpha"), events);
    time.advanceTimeBy(Duration.ofSeconds(2));

    assertTrue(events.stream().noneMatch(e -> e.data() != null), "nothing leaked from beta");
  }

  @Test
  void logStreamOfUnknownServerIs404() {
    assertThrows(ResourceNotFoundException.class, () -> console.logStream("ghost"));
  }

  // ---------------------------------------------------------------- commands

  @Test
  void commandsMustBeASingleLine() {
    for (String bad : new String[] {"", "  ", "say hi\nop attacker", "say\rhi", "x".repeat(1001)}) {
      assertThrows(InvalidRequestException.class, () -> console.sendCommand("alpha", bad));
    }
    verify(k8s, never()).exec(anyString(), anyString(), anyString(), any(String[].class));
  }

  @Test
  void commandsNeedARunningServer() {
    assertThrows(ConflictException.class, () -> console.sendCommand("alpha", "say hi"));
  }

  @Test
  void commandIsSentAsUserMinecraft() {
    V1StatefulSet running = stopped("alpha");
    running.getSpec().setReplicas(1);
    running.setStatus(new io.kubernetes.client.openapi.models.V1StatefulSetStatus().replicas(1)
        .readyReplicas(1));
    when(k8s.getStatefulSet("mc", "alpha")).thenReturn(running);
    when(k8s.exec(eq("mc"), eq("alpha-0"), eq("minecraft"), any(String[].class)))
        .thenReturn(new ExecResult(0, "", ""));

    console.sendCommand("alpha", "  say hi  ");

    verify(k8s).exec("mc", "alpha-0", "minecraft", "gosu", "minecraft", "mc-send-to-console",
        "say hi");
  }
}
