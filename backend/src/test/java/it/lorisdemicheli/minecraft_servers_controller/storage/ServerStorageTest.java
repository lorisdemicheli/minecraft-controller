package it.lorisdemicheli.minecraft_servers_controller.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceNotFoundException;

class ServerStorageTest {

  @TempDir
  Path tmp;

  ServerStorage storage;

  @BeforeEach
  void setUp() {
    storage = new ServerStorage(tmp.resolve("data"));
    storage.createServerDir("alpha");
    storage.createServerDir("beta");
  }

  private static ByteArrayInputStream bytes(String s) {
    return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
  }

  // ------------------------------------------------------------ confinement

  @Test
  void rejectsPathTraversal() {
    for (String bad : new String[] {"../beta", "/../beta", "a/../../beta", "..", "../../etc/passwd",
        "..\\beta", "x/../../../"}) {
      assertThrows(InvalidRequestException.class, () -> storage.list("alpha", bad), "path: " + bad);
      assertThrows(InvalidRequestException.class, () -> storage.readText("alpha", bad, 100), "path: " + bad);
      assertThrows(InvalidRequestException.class, () -> storage.delete("alpha", bad), "path: " + bad);
    }
  }

  @Test
  void rejectsInvalidServerNames() {
    assertThrows(InvalidRequestException.class, () -> storage.list("../beta", ""));
    assertThrows(InvalidRequestException.class, () -> storage.list(".trash", ""));
  }

  @Test
  void unknownServerIsNotFound() {
    assertThrows(ResourceNotFoundException.class, () -> storage.list("ghost", ""));
  }

  @Test
  void leadingSlashMeansServerRoot() {
    storage.writeText("alpha", "/a.txt", "hi");
    assertEquals("hi", storage.readText("alpha", "a.txt", 100));
    assertEquals("hi", storage.readText("alpha", "./a.txt", 100));
  }

  @Test
  void symlinkPointingOutsideIsRejected() throws IOException {
    Path secret = tmp.resolve("secret.txt");
    Files.writeString(secret, "top secret");
    Files.createSymbolicLink(storage.serverDir("alpha").resolve("evil-file"), secret);
    Files.createSymbolicLink(storage.serverDir("alpha").resolve("evil-dir"), storage.serverDir("beta"));

    assertThrows(InvalidRequestException.class, () -> storage.readText("alpha", "evil-file", 100));
    assertThrows(InvalidRequestException.class, () -> storage.list("alpha", "evil-dir"));
    assertThrows(InvalidRequestException.class, () -> storage.writeText("alpha", "evil-dir/x", "x"));
  }

  @Test
  void deletingASymlinkRemovesTheLinkNotTheTarget() throws IOException {
    Path secret = tmp.resolve("secret.txt");
    Files.writeString(secret, "top secret");
    Path link = storage.serverDir("alpha").resolve("evil-file");
    Files.createSymbolicLink(link, secret);

    storage.delete("alpha", "evil-file");

    assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));
    assertTrue(Files.exists(secret));
  }

  @Test
  void serverDirectoryThatIsASymlinkIsRejected() throws IOException {
    Files.createSymbolicLink(tmp.resolve("data").resolve("gamma"), tmp);
    assertThrows(ResourceNotFoundException.class, () -> storage.list("gamma", ""));
  }

  // ------------------------------------------------------------ operations

  @Test
  void listShowsDirectoriesFirstAndIncludesDotFiles() {
    storage.writeText("alpha", "b.txt", "1");
    storage.writeText("alpha", ".hidden", "1");
    storage.mkdir("alpha", "world");
    storage.mkdir("alpha", "Plugins");

    List<StoredFile> files = storage.list("alpha", "");
    assertEquals(List.of("Plugins", "world", ".hidden", "b.txt"),
        files.stream().map(StoredFile::name).toList());
    assertEquals(StoredFile.Type.DIRECTORY, files.get(0).type());
    assertEquals(StoredFile.Type.FILE, files.get(3).type());
    assertEquals(1, files.get(3).size());
  }

  @Test
  void writeReadAndSizeLimit() {
    storage.writeText("alpha", "server.properties", "motd=hi\n");
    assertEquals("motd=hi\n", storage.readText("alpha", "server.properties", 100));
    assertThrows(InvalidRequestException.class, () -> storage.readText("alpha", "server.properties", 3));
    assertThrows(ResourceNotFoundException.class, () -> storage.writeText("alpha", "nope/x.txt", "x"));
  }

  @Test
  void uploadIntoDirectoryOrToExplicitPath() {
    storage.mkdir("alpha", "mods");
    storage.upload("alpha", "mods", "a.jar", bytes("A"));
    storage.upload("alpha", "mods/b.jar", "ignored.jar", bytes("B"));
    storage.upload("alpha", "/", "C:\\evil\\..\\c.txt", bytes("C")); // browser sent a path

    assertEquals("A", storage.readText("alpha", "mods/a.jar", 10));
    assertEquals("B", storage.readText("alpha", "mods/b.jar", 10));
    assertEquals("C", storage.readText("alpha", "c.txt", 10));
    assertThrows(InvalidRequestException.class, () -> storage.upload("alpha", "mods", "..", bytes("x")));
    assertThrows(InvalidRequestException.class, () -> storage.upload("alpha", "../beta", "x", bytes("x")));
  }

  @Test
  void mkdirTouchDelete() {
    storage.mkdir("alpha", "a/b/c");
    storage.touch("alpha", "a/b/c/f.txt");
    assertTrue(Files.exists(storage.serverDir("alpha").resolve("a/b/c/f.txt")));
    assertThrows(ResourceNotFoundException.class, () -> storage.touch("alpha", "missing/f.txt"));
    assertThrows(ConflictException.class, () -> storage.mkdir("alpha", "a/b/c/f.txt"));

    storage.delete("alpha", "a");
    assertFalse(Files.exists(storage.serverDir("alpha").resolve("a")));
    assertThrows(ResourceNotFoundException.class, () -> storage.delete("alpha", "a"));
    assertThrows(InvalidRequestException.class, () -> storage.delete("alpha", ""));
    assertThrows(InvalidRequestException.class, () -> storage.delete("alpha", "/"));
    assertThrows(InvalidRequestException.class, () -> storage.delete("alpha", "x/.."));
    assertTrue(storage.exists("alpha"));
  }

  @Test
  void rename() {
    storage.writeText("alpha", "a.txt", "A");
    storage.writeText("alpha", "b.txt", "B");
    storage.rename("alpha", "a.txt", "c.txt");
    assertEquals("A", storage.readText("alpha", "c.txt", 10));
    assertThrows(ConflictException.class, () -> storage.rename("alpha", "c.txt", "b.txt"));
    assertThrows(InvalidRequestException.class, () -> storage.rename("alpha", "c.txt", "../x"));
    assertThrows(InvalidRequestException.class, () -> storage.rename("alpha", "", "x"));
  }

  @Test
  void copyFileAndDirectory() {
    storage.mkdir("alpha", "world/region");
    storage.writeText("alpha", "world/level.dat", "L");
    storage.writeText("alpha", "world/region/r.mca", "R");
    storage.mkdir("alpha", "backup");

    storage.copy("alpha", "world", "backup"); // into existing directory
    assertEquals("R", storage.readText("alpha", "backup/world/region/r.mca", 10));

    storage.copy("alpha", "world/level.dat", "level-copy.dat");
    assertEquals("L", storage.readText("alpha", "level-copy.dat", 10));

    assertThrows(ConflictException.class, () -> storage.copy("alpha", "world/level.dat", "level-copy.dat"));
    assertThrows(InvalidRequestException.class, () -> storage.copy("alpha", "world", "world/region"));
    assertThrows(InvalidRequestException.class, () -> storage.copy("alpha", "world", "../beta/world"));
    assertThrows(InvalidRequestException.class, () -> storage.copy("alpha", "../beta", "x"));
  }

  @Test
  void pathReturnsSafePathsOnly() {
    assertEquals(storage.serverDir("alpha").resolve("logs/latest.log"),
        storage.path("alpha", "logs/latest.log"));
    assertThrows(InvalidRequestException.class, () -> storage.path("alpha", "../beta/logs/latest.log"));
  }

  @Test
  void usage() {
    storage.writeText("alpha", "a.txt", "12345");
    storage.mkdir("alpha", "d");
    storage.writeText("alpha", "d/b.txt", "123");
    assertEquals(8, storage.usedBytes("alpha"));
    assertTrue(storage.freeBytes() > 0);
    assertTrue(storage.totalBytes() >= storage.freeBytes());
  }

  // ------------------------------------------------------------ trash

  @Test
  void trashMovesDataAndPurgesOnlyOldEntries() throws IOException {
    storage.writeText("alpha", "world.dat", "W");
    assertTrue(storage.moveToTrash("alpha"));
    assertFalse(storage.exists("alpha"));
    assertFalse(storage.moveToTrash("alpha")); // nothing left

    storage.createServerDir("alpha"); // same name can be reused immediately
    assertEquals(List.of(), storage.list("alpha", ""));

    Path trash = tmp.resolve("data").resolve(ServerStorage.TRASH_DIR);
    long old = System.currentTimeMillis() - Duration.ofHours(48).toMillis();
    Files.createDirectories(trash.resolve("ancient-" + old).resolve("sub"));
    Files.writeString(trash.resolve("ancient-" + old).resolve("sub/f"), "x");
    Files.createDirectories(trash.resolve("not-ours"));

    assertEquals(1, storage.purgeTrash(Duration.ofHours(24)));
    assertFalse(Files.exists(trash.resolve("ancient-" + old)));
    assertTrue(Files.exists(trash.resolve("not-ours")));
    assertEquals(1, Files.list(trash).filter(p -> p.getFileName().toString().startsWith("alpha-")).count());
  }
}
