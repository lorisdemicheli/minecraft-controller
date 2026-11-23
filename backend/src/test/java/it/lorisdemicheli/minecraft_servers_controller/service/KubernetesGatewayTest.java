package it.lorisdemicheli.minecraft_servers_controller.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.kubernetes.client.Exec;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.config.TestOptions;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;
import it.lorisdemicheli.minecraft_servers_controller.exception.KubernetesUnavailableException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceAlreadyExistsException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceNotFoundException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ServerException;

class KubernetesGatewayTest {

  AppsV1Api apps = mock(AppsV1Api.class);
  CoreV1Api core = mock(CoreV1Api.class);
  Exec exec = mock(Exec.class);
  ApiClient client = mock(ApiClient.class);
  MinecraftServerOptions options = TestOptions.valid();
  SimpleMeterRegistry meters = new SimpleMeterRegistry();
  KubernetesGateway gateway;

  @BeforeEach
  void setUp() {
    when(client.getJSON()).thenReturn(new JSON());
    options.getKubernetes().setExecTimeout(Duration.ofMillis(300));
    options.getKubernetes().setExecMaxOutputBytes(1024);
    gateway = new KubernetesGateway(apps, core, exec, client, options, meters,
        new KubernetesRetry(3, Duration.ofMillis(1), Duration.ofMillis(1), d -> {
        }, () -> 0));
  }

  // ---------------------------------------------------------------- exec

  @Test
  void execReturnsOutputAndExitCode() throws Exception {
    FakeProcess p = new FakeProcess(stream("hello"), stream("warn"), 0);
    when(exec.exec(anyString(), anyString(), any(String[].class), anyString(), anyBoolean(),
        anyBoolean())).thenReturn(p);

    ExecResult r = gateway.exec("mc", "a-0", "minecraft", "echo");

    assertTrue(r.ok());
    assertEquals("hello", r.stdout());
    assertEquals("warn", r.stderr());
    assertFalse(r.truncated());
    assertTrue(p.destroyed.get(), "the connection is always closed");
  }

  @Test
  void execKeepsOnlyTheConfiguredAmountOfOutput() throws Exception {
    // 5 MB of output: drained, only 1 KB kept in memory
    InputStream huge = new InputStream() {
      long left = 5L * 1024 * 1024;

      @Override
      public int read() {
        return left-- > 0 ? 'x' : -1;
      }

      @Override
      public int read(byte[] b, int off, int len) {
        if (left <= 0) {
          return -1;
        }
        int n = (int) Math.min(len, left);
        java.util.Arrays.fill(b, off, off + n, (byte) 'x');
        left -= n;
        return n;
      }
    };
    when(exec.exec(anyString(), anyString(), any(String[].class), anyString(), anyBoolean(),
        anyBoolean())).thenReturn(new FakeProcess(huge, stream(""), 0));

    ExecResult r = gateway.exec("mc", "a-0", "minecraft", "cat");

    assertEquals(1024, r.stdout().length());
    assertTrue(r.truncated());
  }

  @Test
  void execHasOneOverallDeadlineAndCleansUp() throws Exception {
    BlockingStream stdout = new BlockingStream();
    FakeProcess p = new FakeProcess(stdout, stream(""), 0);
    when(exec.exec(anyString(), anyString(), any(String[].class), anyString(), anyBoolean(),
        anyBoolean())).thenReturn(p);

    long start = System.nanoTime();
    ServerException e =
        assertThrows(ServerException.class, () -> gateway.exec("mc", "a-0", "minecraft", "sleep"));
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertTrue(e.getMessage().contains("timed out"));
    assertTrue(elapsedMs < 2000, "deadline respected, took " + elapsedMs + "ms");
    assertTrue(p.destroyed.get());
    assertTrue(stdout.closed.await(2, TimeUnit.SECONDS), "streams are closed on timeout");
    assertEquals(1.0, meters.counter("minecraft.controller.kubernetes.errors", "code",
        "exec-timeout").count());
  }

  @Test
  void execTimesOutWhenTheProcessDoesNotExit() throws Exception {
    FakeProcess p = new FakeProcess(stream("x"), stream(""), 0);
    p.exits = false;
    when(exec.exec(anyString(), anyString(), any(String[].class), anyString(), anyBoolean(),
        anyBoolean())).thenReturn(p);

    assertThrows(ServerException.class, () -> gateway.exec("mc", "a-0", "minecraft", "x"));
    assertTrue(p.destroyed.get());
  }

  @Test
  void execInterruptionIsPropagated() throws Exception {
    when(exec.exec(anyString(), anyString(), any(String[].class), anyString(), anyBoolean(),
        anyBoolean())).thenReturn(new FakeProcess(new BlockingStream(), stream(""), 0));

    Thread.currentThread().interrupt();
    assertThrows(ServerException.class, () -> gateway.exec("mc", "a-0", "minecraft", "x"));
    assertTrue(Thread.interrupted(), "interrupt flag restored");
  }

  @Test
  void execConnectionFailureIsUnavailable() throws Exception {
    when(exec.exec(anyString(), anyString(), any(String[].class), anyString(), anyBoolean(),
        anyBoolean())).thenThrow(new IOException("connection refused"));
    assertThrows(KubernetesUnavailableException.class,
        () -> gateway.exec("mc", "a-0", "minecraft", "x"));
  }

  // ---------------------------------------------------------------- calls and errors

  @Test
  void readsAreRetriedOnTransientErrors() throws Exception {
    var request = mock(AppsV1Api.APIreadNamespacedStatefulSetRequest.class);
    when(apps.readNamespacedStatefulSet("a", "mc")).thenReturn(request);
    V1StatefulSet sts = new V1StatefulSet().metadata(new V1ObjectMeta().name("a"));
    when(request.execute()).thenThrow(new ApiException(503, "busy")).thenReturn(sts);

    assertEquals(sts, gateway.getStatefulSet("mc", "a"));
    verify(request, times(2)).execute();
  }

  @Test
  void createsAreNotRetried() throws Exception {
    var request = mock(AppsV1Api.APIcreateNamespacedStatefulSetRequest.class);
    V1StatefulSet sts = new V1StatefulSet().metadata(new V1ObjectMeta().name("a"));
    when(apps.createNamespacedStatefulSet("mc", sts)).thenReturn(request);
    when(request.execute()).thenThrow(new ApiException(503, "busy"));

    assertThrows(KubernetesUnavailableException.class, () -> gateway.createStatefulSet("mc", sts));
    verify(request, times(1)).execute();
  }

  @Test
  void deletesAreIdempotent() throws Exception {
    var request = mock(AppsV1Api.APIdeleteNamespacedStatefulSetRequest.class);
    when(apps.deleteNamespacedStatefulSet("a", "mc")).thenReturn(request);
    when(request.execute()).thenThrow(new ApiException(404, "gone"));

    assertFalse(gateway.deleteStatefulSet("mc", "a"));
  }

  @Test
  void findReturnsEmptyOnNotFound() throws Exception {
    var request = mock(AppsV1Api.APIreadNamespacedStatefulSetRequest.class);
    when(apps.readNamespacedStatefulSet(eq("a"), eq("mc"))).thenReturn(request);
    when(request.execute()).thenThrow(new ApiException(404, "nope"));
    assertTrue(gateway.findStatefulSet("mc", "a").isEmpty());
  }

  @Test
  void translatesErrorsUsingTheStatusReason() {
    assertInstanceOf(ResourceNotFoundException.class, gateway.translate("x", new ApiException(404, "")));
    assertInstanceOf(ResourceAlreadyExistsException.class,
        gateway.translate("x", status(409, "AlreadyExists", "exists")));
    assertInstanceOf(ConflictException.class,
        gateway.translate("x", status(409, "Conflict", "object has been modified")));
    RuntimeException invalid = gateway.translate("x", status(422, "Invalid", "spec.replicas: bad"));
    assertInstanceOf(InvalidRequestException.class, invalid);
    assertTrue(invalid.getMessage().contains("spec.replicas: bad"));
    assertInstanceOf(KubernetesUnavailableException.class,
        gateway.translate("x", new ApiException(0, "timeout")));
    assertInstanceOf(KubernetesUnavailableException.class,
        gateway.translate("x", new ApiException(429, "")));
    RuntimeException forbidden = gateway.translate("x", status(403, "Forbidden", "secret detail"));
    assertInstanceOf(ServerException.class, forbidden);
    assertFalse(forbidden.getMessage().contains("secret detail"), "no API details to the client");
  }

  // ---------------------------------------------------------------- helpers

  private static ApiException status(int code, String reason, String message) {
    String body = "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"status\":\"Failure\",\"reason\":\""
        + reason + "\",\"message\":\"" + message + "\",\"code\":" + code + "}";
    return new ApiException("error", code, null, body);
  }

  private static InputStream stream(String s) {
    return new ByteArrayInputStream(s.getBytes());
  }

  /** Never returns data until closed. */
  static final class BlockingStream extends InputStream {
    final CountDownLatch closed = new CountDownLatch(1);

    @Override
    public int read() throws IOException {
      try {
        closed.await();
      } catch (InterruptedException e) {
        throw new java.io.InterruptedIOException();
      }
      return -1;
    }

    @Override
    public void close() {
      closed.countDown();
    }
  }

  static final class FakeProcess extends Process {
    final InputStream out;
    final InputStream err;
    final int exit;
    final AtomicBoolean destroyed = new AtomicBoolean();
    boolean exits = true;

    FakeProcess(InputStream out, InputStream err, int exit) {
      this.out = out;
      this.err = err;
      this.exit = exit;
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return out;
    }

    @Override
    public InputStream getErrorStream() {
      return err;
    }

    @Override
    public int waitFor() {
      return exit;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      if (!exits) {
        unit.sleep(timeout);
        return false;
      }
      return true;
    }

    @Override
    public int exitValue() {
      return exit;
    }

    @Override
    public void destroy() {
      destroyed.set(true);
    }
  }
}
