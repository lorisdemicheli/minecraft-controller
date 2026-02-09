package it.lorisdemicheli.minecraft_servers_controller.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class GeneralConfig {

  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper();
  }

}
