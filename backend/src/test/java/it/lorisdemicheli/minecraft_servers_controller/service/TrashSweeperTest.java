package it.lorisdemicheli.minecraft_servers_controller.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.config.TestOptions;
import it.lorisdemicheli.minecraft_servers_controller.storage.ServerStorage;

class TrashSweeperTest {

  @TempDir
  Path tmp;

  @Test
  void purgesOnlyExpiredEntries() throws Exception {
    ServerStorage storage = new ServerStorage(tmp);
    Path trash = tmp.resolve(ServerStorage.TRASH_DIR);
    long old = System.currentTimeMillis() - Duration.ofHours(30).toMillis();
    long recent = System.currentTimeMillis() - Duration.ofHours(1).toMillis();
    Files.createDirectories(trash.resolve("old-" + old + "/world"));
    Files.writeString(trash.resolve("old-" + old + "/world/level.dat"), "x");
    Files.createDirectories(trash.resolve("recent-" + recent));
    Files.createDirectories(trash.resolve("manual-backup"));

    MinecraftServerOptions options = TestOptions.valid();
    options.getStorage().setTrashRetentionHours(24);
    new TrashSweeper(storage, options).purge();

    assertFalse(Files.exists(trash.resolve("old-" + old)));
    assertTrue(Files.exists(trash.resolve("recent-" + recent)));
    assertTrue(Files.exists(trash.resolve("manual-backup")), "not ours: never deleted");
  }

  @Test
  void zeroRetentionDisablesThePurge() throws Exception {
    ServerStorage storage = new ServerStorage(tmp);
    Path entry = tmp.resolve(ServerStorage.TRASH_DIR).resolve("old-1");
    Files.createDirectories(entry);
    MinecraftServerOptions options = TestOptions.valid();
    options.getStorage().setTrashRetentionHours(0);
    new TrashSweeper(storage, options).purge();
    assertTrue(Files.exists(entry));
    assertEquals(1, Files.list(entry.getParent()).count());
  }
}
