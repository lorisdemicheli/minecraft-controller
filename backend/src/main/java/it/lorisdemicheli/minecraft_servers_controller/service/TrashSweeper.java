package it.lorisdemicheli.minecraft_servers_controller.service;

import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.storage.ServerStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Permanently removes deleted servers' data once the retention period is over. A retention of 0
 * hours disables it: nothing is ever deleted without an explicit retention policy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrashSweeper {

  private final ServerStorage storage;
  private final MinecraftServerOptions options;

  @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
  void purge() {
    int retentionHours = options.getStorage().getTrashRetentionHours();
    if (retentionHours <= 0) {
      return;
    }
    try {
      ServerStorage.PurgeResult result = storage.purgeTrash(Duration.ofHours(retentionHours));
      if (result.purged() > 0) {
        log.info("Purged {} deleted server(s) from the trash", result.purged());
      }
      if (result.failed() > 0) {
        log.warn("{} trash entries could not be purged, will retry", result.failed());
      }
    } catch (RuntimeException e) {
      log.warn("Could not purge the trash", e);
    }
  }
}
