package it.lorisdemicheli.minecraft_servers_controller.service;

import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.storage.ServerStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Permanently removes deleted servers' data once the retention period is over. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrashSweeper {

  private final ServerStorage storage;
  private final MinecraftServerOptions options;

  @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
  void purge() {
    try {
      int purged = storage.purgeTrash(Duration.ofHours(options.getStorage().getTrashRetentionHours()));
      if (purged > 0) {
        log.info("Purged {} deleted server(s) from the trash", purged);
      }
    } catch (RuntimeException e) {
      log.warn("Could not purge the trash", e);
    }
  }
}
