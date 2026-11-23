package it.lorisdemicheli.minecraft_servers_controller.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceNotFoundException;

/** Confinement, symlinks, concurrency and large transfers of {@link ServerStorage}. */
class ServerStorageSecurityTest {

  @TempDir
  Path tmp;

  ServerStorage storage;
  Path alpha;
  Path secret;

  @BeforeEach
  void setUp() throws IOException {
    storage = new ServerStorage(tmp.resolve("data"));
    storage.createServerDir("alpha");
    storage.createServerDir("beta");
    alpha = storage.serverDir("alpha");
    secret = tmp.resolve("outside.txt");
    Files.writeString(secret, "outside");
  }

  private static InputStream bytes(String s) {
    return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
  }

  // ---------------------------------------------------------------- symlinks

  @Test
  void symlinkedIntermediateDirectoryIsNeverFollowed() throws IOException {
    Files.createSymbolicLink(alpha.resolve("plugins"), storage.serverDir("beta"));
    Files.writeString(storage.serverDir("beta").resolve("config.yml"), "beta");

    assertThrows(InvalidRequestException.class, () -> storage.readText("alpha", "plugins/config.yml", 100));
    assertThrows(InvalidRequestException.class, () -> storage.writeText("alpha", "plugins/x", "x"));
    assertThrows(InvalidRequestException.class,
        () -> storage.upload("alpha", "plugins/x.jar", "x.jar", bytes("x")));
    assertThrows(InvalidRequestException.class, () -> storage.delete("alpha", "plugins/config.yml"));
    assertThrows(InvalidRequestException.class, () -> storage.mkdir("alpha", "plugins/sub"));
    assertThrows(InvalidRequestException.class, () -> storage.touch("alpha", "plugins/t"));
    assertThrows(InvalidRequestException.class,
        () -> storage.copy("alpha", "plugins/config.yml", "copy.yml"));
    assertEquals("beta", Files.readString(storage.serverDir("beta").resolve("config.yml")));
    assertFalse(Files.exists(storage.serverDir("beta").resolve("x")));
    assertFalse(Files.exists(storage.serverDir("beta").resolve("sub")));
  }

  @Test
  void symlinkEvenPointingInsideIsNotFollowed() throws IOException {
    Files.writeString(alpha.resolve("real.txt"), "real");
    Files.createSymbolicLink(alpha.resolve("link.txt"), alpha.resolve("real.txt"));
    assertThrows(InvalidRequestException.class, () -> storage.readText("alpha", "link.txt", 100));
    // but it is listed (as OTHER) and can be renamed or removed
    assertEquals(StoredFile.Type.OTHER, storage.stat("alpha", "link.txt").type());
    storage.rename("alpha", "link.txt", "link2.txt");
    storage.delete("alpha", "link2.txt");
    assertEquals("real", Files.readString(alpha.resolve("real.txt")));
  }

  @Test
  void writingOverASymlinkIsRefusedAndTheTargetUntouched() throws IOException {
    Files.createSymbolicLink(alpha.resolve("server.properties"), secret);
    assertThrows(InvalidRequestException.class,
        () -> storage.writeText("alpha", "server.properties", "pwned"));
    assertThrows(InvalidRequestException.class,
        () -> storage.upload("alpha", "server.properties", "x", bytes("pwned")));
    assertEquals("outside", Files.readString(secret));
  }

  @Test
  void deletingADirectoryWithLinksInsideNeverFollowsThem() throws IOException {
    Path victim = tmp.resolve("victim");
    Files.createDirectories(victim);
    Files.writeString(victim.resolve("keep.txt"), "keep");
    Files.createDirectories(alpha.resolve("world"));
    Files.createSymbolicLink(alpha.resolve("world/escape"), victim);

    storage.delete("alpha", "world");

    assertFalse(Files.exists(alpha.resolve("world"), LinkOption.NOFOLLOW_LINKS));
    assertEquals("keep", Files.readString(victim.resolve("keep.txt")));
  }

  @Test
  void copySkipsLinksInsideDirectories() throws IOException {
    Files.createDirectories(alpha.resolve("src"));
    Files.writeString(alpha.resolve("src/a.txt"), "A");
    Files.createSymbolicLink(alpha.resolve("src/evil"), secret);

    storage.copy("alpha", "src", "dst");

    assertEquals("A", Files.readString(alpha.resolve("dst/a.txt")));
    assertFalse(Files.exists(alpha.resolve("dst/evil"), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void directorySwappedForASymlinkBetweenOperationsIsDetected() throws IOException {
    // what a malicious plugin would do between the check and the use (TOCTOU)
    Files.createDirectories(alpha.resolve("cfg"));
    storage.writeText("alpha", "cfg/a.txt", "1");
    storage.delete("alpha", "cfg");
    Files.createSymbolicLink(alpha.resolve("cfg"), storage.serverDir("beta"));

    assertThrows(InvalidRequestException.class, () -> storage.writeText("alpha", "cfg/a.txt", "2"));
    assertFalse(Files.exists(storage.serverDir("beta").resolve("a.txt")));
  }

  @Test
  void absoluteAndTraversalPathsStayInside() {
    storage.writeText("alpha", "/etc/../a.txt", "A"); // absolute = from the server root
    assertEquals("A", storage.readText("alpha", "a.txt", 10));
    for (String bad : new String[] {"../../outside.txt", "a/../../beta/x", "\\..\\beta"}) {
      assertThrows(InvalidRequestException.class, () -> storage.readText("alpha", bad, 10), bad);
    }
    assertThrows(InvalidRequestException.class, () -> storage.readText("alpha", "a\0.txt", 10));
  }

  @Test
  void fileNamesAreValidated() {
    for (String bad : new String[] {"..", ".", " ", "a\nb", "a\u0001", "x".repeat(256)}) {
      assertThrows(InvalidRequestException.class,
          () -> storage.upload("alpha", "/", bad, bytes("x")), bad);
    }
    assertThrows(InvalidRequestException.class, () -> storage.rename("alpha", "/", "x"));
  }

  @Test
  void logSourceRefusesLinksAndMissingFiles() throws IOException {
    assertNull(storage.logSource("alpha", "logs/latest.log").open());
    Files.createDirectories(alpha.resolve("logs"));
    Files.createSymbolicLink(alpha.resolve("logs/latest.log"), secret);
    assertNull(storage.logSource("alpha", "logs/latest.log").open());
  }

  // ---------------------------------------------------------------- atomic writes and concurrency

  @Test
  void concurrentWritesNeverProduceAMixedFile() throws Exception {
    int writers = 16;
    String[] contents = new String[writers];
    for (int i = 0; i < writers; i++) {
      contents[i] = String.valueOf((char) ('a' + i)).repeat(200_000);
    }
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < writers; i++) {
      String content = contents[i];
      futures.add(pool.submit(() -> {
        go.await();
        storage.writeText("alpha", "server.properties", content);
        return null;
      }));
    }
    go.countDown();
    for (Future<?> f : futures) {
      f.get();
    }
    pool.shutdown();

    String result = storage.readText("alpha", "server.properties", 1_000_000);
    assertTrue(List.of(contents).contains(result), "one complete write wins");
    try (Stream<Path> files = Files.list(alpha)) {
      assertEquals(List.of("server.properties"),
          files.map(p -> p.getFileName().toString()).toList(), "no temporary files left");
    }
  }

  @Test
  void readersNeverSeeAHalfWrittenFile() throws Exception {
    String a = "A".repeat(500_000);
    String b = "B".repeat(500_000);
    storage.writeText("alpha", "f.txt", a);
    Thread writer = Thread.ofVirtual().start(() -> {
      for (int i = 0; i < 30; i++) {
        storage.writeText("alpha", "f.txt", i % 2 == 0 ? b : a);
      }
    });
    while (writer.isAlive()) {
      String read = storage.readText("alpha", "f.txt", 1_000_000);
      assertTrue(read.equals(a) || read.equals(b));
    }
    writer.join();
  }

  @Test
  void writeKeepsThePermissionsOfTheReplacedFile() throws IOException {
    Path script = alpha.resolve("start.sh");
    Files.writeString(script, "#!/bin/sh");
    Set<PosixFilePermission> exec = PosixFilePermissions.fromString("rwxr-x---");
    Files.setPosixFilePermissions(script, exec);
    storage.writeText("alpha", "start.sh", "#!/bin/sh\necho hi");
    assertEquals(exec, Files.getPosixFilePermissions(script));
  }

  @Test
  void largeUploadIsStreamedToDisk() throws IOException {
    long size = 64L * 1024 * 1024; // more than we would ever want to hold in memory per request
    InputStream generated = new InputStream() {
      long left = size;

      @Override
      public int read() {
        return left-- > 0 ? 7 : -1;
      }

      @Override
      public int read(byte[] b, int off, int len) {
        if (left <= 0) {
          return -1;
        }
        int n = (int) Math.min(len, left);
        java.util.Arrays.fill(b, off, off + n, (byte) 7);
        left -= n;
        return n;
      }
    };
    storage.upload("alpha", "/", "big.zip", generated, size);
    assertEquals(size, Files.size(alpha.resolve("big.zip")));
  }

  @Test
  void failedUploadLeavesNothingBehind() throws IOException {
    storage.writeText("alpha", "mod.jar", "old");
    InputStream broken = new InputStream() {
      int n;

      @Override
      public int read() throws IOException {
        if (n++ > 10_000) {
          throw new IOException("client disconnected");
        }
        return 1;
      }
    };
    assertThrows(RuntimeException.class, () -> storage.upload("alpha", "mod.jar", "x", broken));
    assertEquals("old", storage.readText("alpha", "mod.jar", 10), "previous version intact");
    try (Stream<Path> files = Files.list(alpha)) {
      assertEquals(1, files.count(), "temporary file removed");
    }
  }

  @Test
  void uploadIsRefusedWhenItWouldFillTheVolume() {
    ServerStorage strict = new ServerStorage(tmp.resolve("data"), Long.MAX_VALUE / 2);
    assertThrows(ConflictException.class,
        () -> strict.upload("alpha", "/", "x.bin", bytes("x"), 1024));
  }

  // ---------------------------------------------------------------- usage, rollback helpers

  @Test
  void usageReportsUnreadableEntries() throws IOException {
    Files.writeString(alpha.resolve("a.txt"), "12345");
    Path locked = alpha.resolve("locked");
    Files.createDirectories(locked);
    Files.writeString(locked.resolve("b.txt"), "123");
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
    try {
      ServerStorage.DiskUsage usage = storage.usage("alpha");
      if (Files.isReadable(locked)) {
        return; // running as root: permissions are not enforced, nothing to check
      }
      assertFalse(usage.complete());
      assertEquals(1, usage.unreadableEntries());
      assertEquals(5, usage.bytes(), "a lower bound, flagged as partial");
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  void rollbackRemovesOnlyEmptyServerDirectories() throws IOException {
    assertTrue(storage.deleteServerDirIfEmpty("beta"));
    Files.writeString(alpha.resolve("level.dat"), "world");
    assertFalse(storage.deleteServerDirIfEmpty("alpha"));
    assertTrue(storage.exists("alpha"));
    assertFalse(storage.deleteServerDirIfEmpty("ghost"));
  }

  @Test
  void createServerDirTellsWhetherDataWasAdopted() {
    assertFalse(storage.createServerDir("alpha"));
    assertTrue(storage.createServerDir("gamma"));
  }

  @Test
  void pendingDeletionMarkersSurviveAndAreListed() {
    storage.markPendingDeletion("alpha");
    ServerStorage reopened = new ServerStorage(tmp.resolve("data"));
    assertTrue(reopened.isPendingDeletion("alpha"));
    assertEquals(List.of("alpha"), reopened.pendingDeletions());
    assertTrue(reopened.pendingDeletionSince("alpha").isPresent());
    reopened.clearPendingDeletion("alpha");
    assertFalse(reopened.isPendingDeletion("alpha"));
    // internal folders are not listed as servers
    assertThrows(ResourceNotFoundException.class, () -> storage.list("ghost", "/"));
  }

  @Test
  void zeroRetentionNeverPurges() throws IOException {
    storage.moveToTrash("alpha");
    assertEquals(0, storage.purgeTrash(java.time.Duration.ZERO).purged());
    try (Stream<Path> trash = Files.list(tmp.resolve("data").resolve(ServerStorage.TRASH_DIR))) {
      assertEquals(1, trash.count());
    }
  }
}
