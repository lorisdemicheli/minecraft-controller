package it.lorisdemicheli.minecraft_servers_controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "it.lorisdemicheli.minecraft-servers.security.password=test-password",
    "it.lorisdemicheli.minecraft-servers.security.jwt-secret=test-secret-test-secret-test-secret-123",
    "it.lorisdemicheli.minecraft-servers.storage.data-dir=target/test-data"})
class MinecraftServersControllerApplicationTests {

  @Test
  void contextLoads() {
  }

}
