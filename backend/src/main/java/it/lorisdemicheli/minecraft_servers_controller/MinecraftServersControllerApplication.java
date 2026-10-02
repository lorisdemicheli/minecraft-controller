package it.lorisdemicheli.minecraft_servers_controller;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MinecraftServersControllerApplication {

  public static void main(String[] args) {
    SpringApplication.run(MinecraftServersControllerApplication.class, args);
  }

}
