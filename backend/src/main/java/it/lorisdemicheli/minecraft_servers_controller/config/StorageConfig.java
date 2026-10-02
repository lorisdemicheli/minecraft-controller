package it.lorisdemicheli.minecraft_servers_controller.config;

import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import it.lorisdemicheli.minecraft_servers_controller.storage.ServerStorage;

@Configuration
public class StorageConfig {

  @Bean
  ServerStorage serverStorage(MinecraftServerOptions options) {
    return new ServerStorage(Path.of(options.getStorage().getDataDir()));
  }
}
